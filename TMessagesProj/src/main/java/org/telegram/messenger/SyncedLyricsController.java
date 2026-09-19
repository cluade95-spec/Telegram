/*
 * This is the source code of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */
package org.telegram.messenger;

import android.text.TextUtils;
import android.util.Xml;

import org.telegram.tgnet.TLRPC;
import org.telegram.messenger.audioinfo.AudioInfo;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local, client-only storage and parsing for synced LRC and plain music lyrics. */
public final class SyncedLyricsController implements NotificationCenter.NotificationCenterDelegate {
    private static final Pattern TIMESTAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[\\.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET = Pattern.compile("(?i)\\[offset\\s*:\\s*([+-]?\\d+)\\s*\\]");
    private static final Pattern WORD_TIMESTAMP = Pattern.compile("<\\d{1,3}:\\d{1,2}(?:[\\.:]\\d{1,3})?>");
    private static final Pattern METADATA = Pattern.compile("(?i)^\\[[a-z][a-z0-9_-]{0,31}\\s*:.*]$");
    private static final Pattern LOOKS_TIMED = Pattern.compile("^\\s*(?:\\[\\d{1,3}:|<\\d{1,3}:).*");
    private static final SyncedLyricsController[] instances = new SyncedLyricsController[UserConfig.MAX_ACCOUNT_COUNT];
    private static final int MAX_EMBEDDED_LYRICS_LENGTH = 1024 * 1024;
    private static final int MAX_EMBEDDED_LYRICS_LINES = 10000;
    private static final Lyrics EMPTY = new Lyrics(Collections.emptyList(), "", Kind.MISSING, Source.NONE);

    public enum Kind {
        MISSING, SYNCED, PLAIN, MALFORMED
    }

    public enum Source {
        NONE, LOCAL, EMBEDDED
    }

    public enum State {
        NOT_LOADED, LOADING, WAITING_FILE, LOADED, MISSING, MALFORMED, READ_FAILED, WRITE_FAILED
    }

    public interface Completion {
        void run(boolean success);
    }

    private static final class Entry {
        State state;
        Lyrics lyrics;
        Lyrics embeddedLyrics = EMPTY;
        boolean embeddedChecked;
        boolean suppressed;
        MessageObject message;
        int generation;

        Entry(State state, Lyrics lyrics, int generation) {
            this.state = state;
            this.lyrics = lyrics;
            this.generation = generation;
        }
    }

    public static SyncedLyricsController getInstance(int account) {
        synchronized (instances) {
            if (instances[account] == null) {
                instances[account] = new SyncedLyricsController(account);
            }
            return instances[account];
        }
    }

    /**
     * Genuine inline timing captured from an Enhanced LRC line, addressing ranges of the line's
     * own visible {@link Line#text}.
     *
     * <p>The unit is a <em>timed segment</em>, not a word: Enhanced LRC tags may sit mid-word, and
     * other word-timed formats are syllable-granular. A segment count is therefore not a word
     * count, and two tags on a line do not mean the line has two words. A segment carries only what
     * the source actually stated - a genuine start time and the range of visible text it
     * introduces, however the author chose to divide the line. There is
     * deliberately no end time: Enhanced LRC states starts only, and the end of the last segment on
     * a line is not stated anywhere. A consumer that needs an end can derive one from the next
     * segment's start; this model never invents one.
     *
     * <p>Offsets are UTF-16 indices into {@link Line#text}, which is what every Android text
     * consumer ({@code String}, {@code Spannable}, {@code Layout}, {@code Canvas}) already indexes,
     * so the text needs no rewriting or normalisation to be addressed. An offset is never allowed
     * to fall between a surrogate pair, so no single character is ever cut in half.
     *
     * <p>That is an integrity guarantee, not a typographic one. A boundary can be valid UTF-16 and
     * still be a poor place to split visually - between a base character and its combining mark,
     * inside a ZWJ emoji sequence, or mid-run in bidirectional text. Whoever renders these ranges
     * has to decide what to do about that; storing them faithfully is this layer's job.
     *
     * <p>Instances are immutable, never empty - a line with nothing to state carries no Segments at
     * all rather than an empty one - and hold parallel primitive arrays: a whole song is a few
     * hundred segments, and a per-frame consumer must not chase objects or allocate to read one.
     */
    public static final class Segments {
        private final int[] startOffsets;
        private final int[] endOffsets;
        private final long[] startTimes;
        /**
         * Stated end times, or null when the format cannot state them. Enhanced LRC states starts
         * only, so it is always null there; TTML can state an end per span, and where it does the
         * value is kept. An individual entry is -1 when that one segment stated no end. Nothing
         * here is ever filled in from a neighbour or a duration.
         */
        private final long[] endTimes;

        private Segments(int[] startOffsets, int[] endOffsets, long[] startTimes, long[] endTimes) {
            this.startOffsets = startOffsets;
            this.endOffsets = endOffsets;
            this.startTimes = startTimes;
            this.endTimes = endTimes;
        }

        public int size() {
            return startTimes.length;
        }

        /** Inclusive UTF-16 start of this segment's range in {@link Line#text}. */
        public int startOffset(int index) {
            return startOffsets[index];
        }

        /** Exclusive UTF-16 end of this segment's range in {@link Line#text}. */
        public int endOffset(int index) {
            return endOffsets[index];
        }

        /** Absolute start, in the same timebase as {@link Line#timeMs}. Stated by the source. */
        public long startTimeMs(int index) {
            return startTimes[index];
        }

        /** True when the source stated an end time for this segment. Never inferred. */
        public boolean hasEndTime(int index) {
            return endTimes != null && endTimes[index] >= 0;
        }

        /**
         * The stated end of this segment, valid only where {@link #hasEndTime} is true. It is the
         * one thing a consumer may use to know how long a word was held, because it is the only
         * one the source said.
         */
        public long endTimeMs(int index) {
            return endTimes[index];
        }

        /**
         * Index of the last segment whose stated start time has been reached at {@code positionMs},
         * or -1 when none has. Nothing between two stated times is attributed to either of them:
         * the answer is always a segment the source actually started.
         */
        public int indexAt(long positionMs) {
            int low = 0, high = startTimes.length - 1, result = -1;
            while (low <= high) {
                final int middle = (low + high) >>> 1;
                if (startTimes[middle] <= positionMs) {
                    result = middle;
                    low = middle + 1;
                } else {
                    high = middle - 1;
                }
            }
            return result;
        }
    }

    public static final class Line {
        public final long timeMs;
        public final String text;
        public final boolean timed;
        /**
         * The timed ranges the source stated inline for this line, or null when it stated none.
         *
         * <p>This records only what was captured. It is not a claim that the line is fully
         * word-timed, that every word has its own timestamp, or that there is enough here to
         * animate: a line may carry one segment, or a few covering part of its text, with the rest
         * legitimately untimed. A consumer decides what it can do with that.
         *
         * <p>Null is the overwhelmingly common case and the only one that existed before inline
         * timing was captured: such a line carries no empty arrays and no placeholder segments.
         */
        public final Segments segments;

        private Line(long timeMs, String text, boolean timed) {
            this(timeMs, text, timed, null);
        }

        private Line(long timeMs, String text, boolean timed, Segments segments) {
            this.timeMs = timeMs;
            this.text = text;
            this.timed = timed;
            this.segments = segments;
        }

        private Line withSegments(Segments segments) {
            return new Line(timeMs, text, timed, segments);
        }
    }

    /**
     * How much of one line the source says has been sung at a given playback position.
     *
     * <p>Everything here is read straight out of {@link Line#segments}: a boundary is always an
     * offset the source stated, and it moves only when a time the source stated has been reached.
     * No time is divided, interpolated or inferred from how long a word looks, and a line without
     * genuine inline timing resolves to inactive so its consumer keeps whatever line-level
     * behaviour it already had.
     *
     * <p>One instance is reused per consumer: this is read on every frame and must not allocate.
     */
    public static final class Karaoke {
        /** True when this line has genuine inline timing to show at the resolved position. */
        public boolean active;
        /**
         * Exclusive UTF-16 end, in {@link Line#text}, of the text the source has reached. Always a
         * segment boundary, so it never falls inside a surrogate pair.
         */
        public int sungEnd;
        /**
         * Start of the segment that most recently began, so a consumer can treat that one segment
         * differently while it arrives. Equals {@link #sungEnd} when no segment has begun yet.
         */
        public int fadeStart;
        /**
         * 0..1 across the arrival of the segment at {@link #fadeStart}, reaching 1 once it has
         * settled. This is a fixed-length appearance transition anchored to a stated start time -
         * a consumer may ease a segment in over it - not an estimate of how long that segment
         * lasts and not a position within it. The boundaries above never depend on it.
         */
        public float fadeProgress;
        /**
         * The time the source said this word is held until, or -1 when it said nothing. Enhanced
         * LRC always leaves this at -1; TTML sets it where a span stated an end. It is never
         * derived from the next word, from the line, or from a duration.
         */
        public long heldUntilMs;

        public void clear() {
            active = false;
            sungEnd = 0;
            fadeStart = 0;
            fadeProgress = 1f;
            heldUntilMs = -1;
        }

        /**
         * Resolves this holder for one line of a document, given which line the position is
         * currently inside. This is the whole of the decision: a line the position has already
         * left has had every range it states started, so all of it has been sung; a line the
         * position has not reached yet states nothing that has happened, so none of it has, even
         * if a player is already moving it into view. Only the current line is resolved against
         * the clock.
         *
         * <p>Returning false means this line has no genuine inline timing at all, and is the
         * signal to render it exactly the way it was rendered before word timing existed.
         */
        public boolean resolveRow(Line line, int lineIndex, int currentLine, long positionMs, long transitionMs) {
            return resolveRow(line, lineIndex, currentLine, positionMs, transitionMs, Long.MAX_VALUE);
        }

        public boolean resolveRow(Line line, int lineIndex, int currentLine, long positionMs, long transitionMs, long nextLineTimeMs) {
            clear();
            if (line == null || line.segments == null || line.text.isEmpty()) return false;
            if (lineIndex == currentLine) {
                if (resolve(line, positionMs, transitionMs, nextLineTimeMs)) return true;
                clear(); // the line's own timestamp has not been reached, so nothing has happened
            } else if (lineIndex < currentLine) {
                // Already left behind: every range it states has started, so all of it is sung.
                sungEnd = fadeStart = line.text.length();
            }
            // Everything else is a line the position has not reached - including one a player is
            // already moving into view - and clear() has left both boundaries at zero.
            active = true;
            return true;
        }

        /**
         * Resolves this holder against one line at one playback position, and reports whether the
         * line has genuine timing to show. {@code transitionMs} is the consumer's own appearance
         * transition length; it never changes a boundary.
         *
         * <p>Returning false is the fallback path: a line with no inline timing, a line whose own
         * timestamp has not been reached, and an untimed line all land there, and the consumer is
         * expected to fall back to line-level behaviour rather than invent anything.
         */
        public boolean resolve(Line line, long positionMs, long transitionMs) {
            return resolve(line, positionMs, transitionMs, Long.MAX_VALUE);
        }

        /**
         * @param nextLineTimeMs the stated start of the line after this one, or
         *                       {@link Long#MAX_VALUE} when there is none. It bounds the last
         *                       word's transition, which is the difference between a final word
         *                       being seen and being swallowed by the line change.
         */
        public boolean resolve(Line line, long positionMs, long transitionMs, long nextLineTimeMs) {
            clear();
            if (line == null || line.segments == null || line.text.isEmpty()) return false;
            if (!line.timed || positionMs < line.timeMs) return false;
            final Segments segments = line.segments;
            final int index = segments.indexAt(positionMs);
            if (index < 0) {
                // The line is current but its first stated tag has not been reached. Any text
                // before that tag carries no timing of its own; it belongs to the line and takes
                // the line's own granularity, which is the only thing stated about it.
                sungEnd = fadeStart = segments.startOffset(0);
                active = true;
                return true;
            }
            fadeStart = segments.startOffset(index);
            sungEnd = segments.endOffset(index);
            final long start = segments.startTimeMs(index);
            // Every bound below is a time the source stated. The transition is never allowed to
            // outlive the thing it is announcing, which is what made a quick final word arrive too
            // late to be seen: it still had most of its transition to run when the line changed.
            long window = transitionMs;
            if (index + 1 < segments.size()) {
                // The real gap to the next stated start: closely spaced syllables arrive crisply
                // instead of overlapping.
                window = Math.min(window, segments.startTimeMs(index + 1) - start);
            } else if (nextLineTimeMs != Long.MAX_VALUE) {
                // The last word of a line is bounded by when the line itself ends.
                window = Math.min(window, nextLineTimeMs - start);
            }
            if (segments.hasEndTime(index)) {
                // A format that states how long the word was held bounds it by that, too.
                window = Math.min(window, segments.endTimeMs(index) - start);
            }
            heldUntilMs = segments.hasEndTime(index) ? segments.endTimeMs(index) : -1;
            final long elapsed = positionMs - start;
            fadeProgress = window <= 0 ? 1f : Math.max(0f, Math.min(1f, elapsed / (float) window));
            active = true;
            return true;
        }
    }

    public static final class Lyrics {
        public final ArrayList<Line> lines;
        public final String source;
        public final Kind kind;
        public final Source origin;

        private Lyrics(java.util.List<Line> lines, String source, Kind kind, Source origin) {
            this.lines = new ArrayList<>(lines);
            this.source = source;
            this.kind = kind;
            this.origin = origin;
        }

        public boolean isSynced() {
            return hasTimedLines();
        }

        public boolean hasTimedLines() {
            return !lines.isEmpty() && lines.get(0).timed;
        }

        private Lyrics withOrigin(Source origin) {
            return new Lyrics(lines, source, kind, origin);
        }

        public int lineAt(long positionMs) {
            if (!isSynced()) return -1;
            int low = 0, high = lines.size() - 1, result = -1;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                if (lines.get(middle).timeMs <= positionMs) {
                    result = middle;
                    low = middle + 1;
                } else {
                    high = middle - 1;
                }
            }
            return result;
        }

        public String textAt(long positionMs) {
            int index = lineAt(positionMs);
            return index < 0 ? null : lines.get(index).text;
        }
    }

    private final int account;
    private boolean lyricsModePreferred;
    private final LinkedHashMap<String, SyncedLyricsController.Entry> cache = new LinkedHashMap<String, SyncedLyricsController.Entry>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SyncedLyricsController.Entry> eldest) {
            return size() > 64 && eldest.getValue().state != State.LOADING;
        }
    };

    private SyncedLyricsController(int account) {
        this.account = account;
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoaded);
    }

    public boolean isLyricsModePreferred() {
        return lyricsModePreferred;
    }

    public void setLyricsModePreferred(boolean preferred) {
        lyricsModePreferred = preferred;
    }

    /**
     * Turns captured lines into the final model, and is deliberately the only place that does:
     * both the LRC and the TTML front ends hand their lines here, so ordering, the merging of
     * lines that share a timestamp, and every timing-integrity rule in {@link #qualify} apply
     * identically whichever format the source was written in.
     */
    // region ttml

    /**
     * TTML, the one lyric format in use that can state where a word <em>ends</em> as well as where
     * it starts. Parsed into exactly the same model as LRC and put through exactly the same timing
     * validation: the only difference that reaches a consumer is that a segment may now carry a
     * genuine end time, and only where the document stated one.
     *
     * <p>The dialect is the AMLL TTML specification, which is Apple's word-timed TTML with an
     * {@code amll:} metadata block: {@code <body>} holds {@code <div>} blocks, which hold
     * {@code <p begin end>} lines, which hold {@code <span begin end>} words or syllables.
     * {@code ttm:role} marks content that is not the line being sung - a translation, a
     * romanisation, or background vocals - and those subtrees are skipped whole rather than
     * interleaved into the text.
     */
    private static final String TTML_METADATA_NS = "http://www.w3.org/ns/ttml#metadata";

    /**
     * True when this source is a TTML document rather than lyrics text. Only an XML declaration,
     * a comment, a doctype or whitespace may precede the root, and the root must be {@code tt}, so
     * an Enhanced LRC line that happens to start with an inline tag cannot be mistaken for one.
     */
    private static boolean looksLikeTtml(String source) {
        final int limit = Math.min(source.length(), 8192);
        int a = 0;
        while (a < limit) {
            final char c = source.charAt(a);
            if (Character.isWhitespace(c) || c == '﻿') {
                a++;
                continue;
            }
            if (c != '<') return false;
            if (source.startsWith("<?", a)) {
                final int end = source.indexOf("?>", a);
                if (end < 0) return false;
                a = end + 2;
                continue;
            }
            if (source.startsWith("<!--", a)) {
                final int end = source.indexOf("-->", a);
                if (end < 0) return false;
                a = end + 3;
                continue;
            }
            if (source.startsWith("<!", a)) {
                final int end = source.indexOf('>', a);
                if (end < 0) return false;
                a = end + 1;
                continue;
            }
            if (!source.startsWith("<tt", a)) return false;
            final int after = a + 3;
            return after >= source.length() || source.charAt(after) == '>' || source.charAt(after) == '/'
                    || Character.isWhitespace(source.charAt(after));
        }
        return false;
    }

    /**
     * Reads a TTML document into timed lines. A document that cannot be read at all, or that
     * carries no usable line, comes back {@link Kind#MALFORMED} with its source intact, exactly as
     * a broken LRC document does - the text is never discarded and never rewritten.
     */
    private static Lyrics parseTtml(String source) {
        final ArrayList<ParsedLine> parsed = new ArrayList<>();
        try {
            final XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
            parser.setInput(new StringReader(source));
            int order = 0;
            boolean inBody = false;
            // Per-line state, live only between <p> and </p>.
            boolean inLine = false;
            long lineTimeMs = -1;
            final StringBuilder text = new StringBuilder();
            final boolean[] pendingSpace = new boolean[1];
            int[] offsets = new int[16];
            long[] times = new long[16];
            long[] ends = new long[16];
            int count = 0;
            boolean malformed = false;
            int skipDepth = -1;
            int timedDepth = -1;
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (parsed.size() > MAX_EMBEDDED_LYRICS_LINES) return malformedTtml(source);
                if (event == XmlPullParser.START_TAG) {
                    final String name = parser.getName();
                    if (skipDepth >= 0) {
                        event = parser.next();
                        continue;
                    }
                    if ("body".equals(name)) {
                        inBody = true;
                    } else if (inBody && "p".equals(name)) {
                        inLine = true;
                        lineTimeMs = parseTtmlTime(parser.getAttributeValue(null, "begin"));
                        text.setLength(0);
                        pendingSpace[0] = false;
                        count = 0;
                        malformed = false;
                        timedDepth = -1;
                    } else if (inLine && "span".equals(name)) {
                        final String role = parser.getAttributeValue(TTML_METADATA_NS, "role");
                        if (isNonLyricRole(role)) {
                            // A translation, a romanisation or a background part: not this line.
                            skipDepth = parser.getDepth();
                        } else if (timedDepth < 0) {
                            final String beginText = parser.getAttributeValue(null, "begin");
                            final long begin = parseTtmlTime(beginText);
                            if (beginText != null && begin < 0) {
                                // A span that claims a time it cannot state is exactly the case an
                                // impossible inline LRC tag covers: nothing on this line is
                                // trusted, rather than trusting whichever spans happened to parse.
                                // A span with no begin at all is different - that is ordinary
                                // untimed text, and it stays untimed.
                                malformed = true;
                            }
                            if (begin >= 0) {
                                // Only the outermost timed span of a nest becomes a segment, so a
                                // parent and its child can never both claim the same text.
                                timedDepth = parser.getDepth();
                                if (pendingSpace[0] && text.length() > 0) {
                                    text.append(' ');
                                    pendingSpace[0] = false;
                                }
                                if (count == offsets.length) {
                                    offsets = Arrays.copyOf(offsets, count * 2);
                                    times = Arrays.copyOf(times, count * 2);
                                    ends = Arrays.copyOf(ends, count * 2);
                                }
                                offsets[count] = text.length();
                                times[count] = begin;
                                ends[count] = parseTtmlTime(parser.getAttributeValue(null, "end"));
                                count++;
                            }
                        }
                    }
                } else if (event == XmlPullParser.TEXT) {
                    if (skipDepth < 0 && inLine) {
                        appendTtmlText(text, parser.getText(), pendingSpace);
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    final String name = parser.getName();
                    if (skipDepth >= 0) {
                        if (parser.getDepth() <= skipDepth) skipDepth = -1;
                    } else if (inLine && "span".equals(name)) {
                        if (timedDepth >= 0 && parser.getDepth() <= timedDepth) timedDepth = -1;
                    } else if (inLine && "p".equals(name)) {
                        inLine = false;
                        final int begin = trimStart(text);
                        final String lineText = text.substring(begin, trimEnd(text, begin));
                        if (lineTimeMs >= 0) {
                            long startTimeMs = lineTimeMs;
                            Candidate candidate = null;
                            if (count > 0) {
                                final int[] rebased = Arrays.copyOf(offsets, count);
                                for (int a = 0; a < count; a++) {
                                    rebased[a] = Math.max(0, Math.min(lineText.length(), rebased[a] - begin));
                                }
                                candidate = new Candidate(rebased, Arrays.copyOf(times, count),
                                        Arrays.copyOf(ends, count), count, malformed);
                                // A word cannot start before the line that contains it. Where the
                                // document disagrees with itself the line takes the earlier of the
                                // two times it stated, rather than losing its word timing.
                                startTimeMs = Math.min(startTimeMs, times[0]);
                            }
                            parsed.add(new ParsedLine(startTimeMs, lineText, candidate, order++));
                        }
                    } else if ("body".equals(name)) {
                        inBody = false;
                    }
                }
                event = parser.next();
            }
        } catch (Throwable e) {
            return malformedTtml(source);
        }
        if (parsed.isEmpty()) return malformedTtml(source);
        final ArrayList<Line> result = assemble(parsed);
        if (result.isEmpty()) return malformedTtml(source);
        return new Lyrics(result, source, Kind.SYNCED, Source.NONE);
    }

    private static Lyrics malformedTtml(String source) {
        return new Lyrics(Collections.emptyList(), source, Kind.MALFORMED, Source.NONE);
    }

    /** Roles that mark content which is not the line being sung. */
    private static boolean isNonLyricRole(String role) {
        return "x-translation".equals(role) || "x-roman".equals(role) || "x-bg".equals(role);
    }

    /**
     * Appends document text, collapsing every run of whitespace to one space. TTML is usually
     * pretty-printed, so the indentation between two spans is markup rather than lyric text; left
     * alone it would put newlines inside a line and shift every offset after it.
     */
    private static void appendTtmlText(StringBuilder text, String chunk, boolean[] pendingSpace) {
        if (chunk == null) return;
        for (int a = 0; a < chunk.length(); a++) {
            final char c = chunk.charAt(a);
            if (Character.isWhitespace(c)) {
                pendingSpace[0] = text.length() > 0;
                continue;
            }
            if (pendingSpace[0]) {
                text.append(' ');
                pendingSpace[0] = false;
            }
            text.append(c);
        }
    }

    private static int trimStart(StringBuilder text) {
        int begin = 0;
        while (begin < text.length() && text.charAt(begin) <= ' ') begin++;
        return begin;
    }

    private static int trimEnd(StringBuilder text, int begin) {
        int last = text.length();
        while (last > begin && text.charAt(last - 1) <= ' ') last--;
        return last;
    }

    /**
     * Parses a TTML time expression into milliseconds, or -1 when it states nothing usable.
     *
     * <p>The specification allows a clock time - {@code HH:MM:SS.fff}, {@code MM:SS.fff} or
     * {@code SS.fff} - and an offset with a seconds suffix, {@code 12.3s}. In a colon form the
     * minute and second fields must be below 60, which is enforced here rather than folded over:
     * a document that states 90 seconds in a minute field is wrong about something, and guessing
     * which would be inventing timing.
     */
    private static long parseTtmlTime(String value) {
        if (value == null) return -1;
        final String trimmed = value.trim();
        if (trimmed.isEmpty()) return -1;
        try {
            if (trimmed.endsWith("s") || trimmed.endsWith("S")) {
                final double seconds = Double.parseDouble(trimmed.substring(0, trimmed.length() - 1));
                if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds < 0) return -1;
                return Math.round(seconds * 1000);
            }
            final String[] parts = trimmed.split(":", -1);
            if (parts.length > 3) return -1;
            double total = 0;
            for (int a = 0; a < parts.length; a++) {
                if (parts[a].isEmpty()) return -1;
                final double part = Double.parseDouble(parts[a]);
                if (Double.isNaN(part) || Double.isInfinite(part) || part < 0) return -1;
                // In a colon form every field must be below 60 except the hours field, which
                // only exists when all three are present. A minute field of 90 is a document that
                // is wrong about something, and folding it over would be guessing which.
                if (parts.length > 1 && part >= 60 && !(a == 0 && parts.length == 3)) return -1;
                total = total * 60 + part;
            }
            return Math.round(total * 1000);
        } catch (RuntimeException ignore) {
            return -1;
        }
    }

    // endregion

    private static ArrayList<Line> assemble(ArrayList<ParsedLine> parsed) {
        parsed.sort(Comparator.comparingLong((ParsedLine line) -> line.timeMs).thenComparingInt(line -> line.order));
        ArrayList<Line> result = new ArrayList<>();
        ArrayList<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < parsed.size();) {
            int j = i + 1;
            while (j < parsed.size() && parsed.get(j).timeMs == parsed.get(i).timeMs) j++;
            StringBuilder combined = new StringBuilder();
            for (int k = i; k < j; k++) {
                String text = parsed.get(k).text;
                if (text.length() == 0) {
                    combined.setLength(0);
                } else {
                    if (combined.length() > 0) combined.append('\n');
                    combined.append(text);
                }
            }
            // Inline timing survives only when this line came from exactly one source line. A
            // combined line interleaves two texts, so the captured offsets would no longer address
            // the text they were measured against; dropping them is the only honest option, and it
            // leaves the visible text untouched either way.
            candidates.add(j - i == 1 ? parsed.get(i).candidate : null);
            result.add(new Line(parsed.get(i).timeMs, combined.toString(), true));
            i = j;
        }
        for (int i = 0; i < result.size(); i++) {
            final Line line = result.get(i);
            final long nextTimeMs = i + 1 < result.size() ? result.get(i + 1).timeMs : Long.MAX_VALUE;
            final Segments segments = qualify(candidates.get(i), line.text, line.timeMs, nextTimeMs);
            if (segments != null) result.set(i, line.withSegments(segments));
        }
        return result;
    }

    public static Lyrics parse(String source) {
        if (source == null) return EMPTY;
        if (looksLikeTtml(source)) return parseTtml(source);
        ArrayList<ParsedLine> parsed = new ArrayList<>();
        boolean hasMalformedTiming = false;
        boolean hasUntimedContent = false;
        boolean hasTimingSyntax = false;
        String[] sourceLines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        long offset = 0;
        for (String sourceLine : sourceLines) {
            Matcher offsetMatcher = OFFSET.matcher(sourceLine.trim());
            if (offsetMatcher.matches()) {
                try {
                    offset = Long.parseLong(offsetMatcher.group(1));
                } catch (RuntimeException ignore) {}
            }
        }
        int order = 0;
        for (String sourceLine : sourceLines) {
            Matcher matcher = TIMESTAMP.matcher(sourceLine);
            ArrayList<Long> times = new ArrayList<>();
            int end = 0;
            while (matcher.find() && (matcher.start() == end || TextUtils.isEmpty(sourceLine.substring(end, matcher.start()).trim()))) {
                try {
                    long minutes = Long.parseLong(matcher.group(1));
                    long seconds = Long.parseLong(matcher.group(2));
                    if (seconds >= 60) {
                        times.clear();
                        break;
                    }
                    String fraction = matcher.group(3);
                    long millis = fraction == null ? 0 : Long.parseLong(fraction) * (fraction.length() == 1 ? 100 : fraction.length() == 2 ? 10 : 1);
                    times.add(Math.max(0, (minutes * 60 + seconds) * 1000 + millis + offset));
                    end = matcher.end();
                } catch (RuntimeException ignore) {
                    times.clear();
                    break;
                }
            }
            if (times.isEmpty()) continue;
            // Identical visible text to a plain strip-and-trim, plus the positions the stripped
            // tags occupied in it. The text is what it always was; the timing is what used to be
            // thrown away here.
            Stripped stripped = stripWordTimestamps(sourceLine.substring(end), offset);
            for (Long time : times) parsed.add(new ParsedLine(time, stripped.text, stripped.candidate, order++));
        }
        for (String sourceLine : sourceLines) {
            String text = sourceLine.trim();
            if (text.isEmpty() || METADATA.matcher(text).matches()) continue;
            hasTimingSyntax |= LOOKS_TIMED.matcher(sourceLine).matches();
            boolean validTimedLine = hasValidLeadingTimestamp(sourceLine);
            if (!validTimedLine) {
                hasUntimedContent = true;
                hasMalformedTiming |= LOOKS_TIMED.matcher(sourceLine).matches();
            }
        }
        ArrayList<Line> result = assemble(parsed);        if (!result.isEmpty() && !hasUntimedContent) {
            return new Lyrics(result, source, Kind.SYNCED, Source.NONE);
        }
        if (!result.isEmpty() || hasMalformedTiming || hasTimingSyntax) {
            return new Lyrics(result, source, Kind.MALFORMED, Source.NONE);
        }
        ArrayList<Line> plain = new ArrayList<>();
        boolean pendingBlank = false;
        for (String sourceLine : sourceLines) {
            String text = sourceLine.trim();
            if (METADATA.matcher(text).matches()) continue;
            if (text.isEmpty()) {
                pendingBlank = !plain.isEmpty();
                continue;
            }
            if (pendingBlank) plain.add(new Line(-1, "", false));
            plain.add(new Line(-1, text, false));
            pendingBlank = false;
        }
        return plain.isEmpty() ? new Lyrics(Collections.emptyList(), source, Kind.MISSING, Source.NONE) : new Lyrics(plain, source, Kind.PLAIN, Source.NONE);
    }

    private static final class ParsedLine {
        final long timeMs;
        final String text;
        final Candidate candidate;
        final int order;

        ParsedLine(long timeMs, String text, Candidate candidate, int order) {
            this.timeMs = timeMs;
            this.text = text;
            this.candidate = candidate;
            this.order = order;
        }
    }

    private static boolean hasValidLeadingTimestamp(String sourceLine) {
        Matcher timestamp = TIMESTAMP.matcher(sourceLine);
        if (!timestamp.find() || !TextUtils.isEmpty(sourceLine.substring(0, timestamp.start()).trim())) return false;
        try {
            return Long.parseLong(timestamp.group(2)) < 60;
        } catch (RuntimeException ignore) {
            return false;
        }
    }

    /** A line's visible text, plus whatever genuine inline timing was stripped out of it. */
    private static final class Stripped {
        final String text;
        final Candidate candidate;

        Stripped(String text, Candidate candidate) {
            this.text = text;
            this.candidate = candidate;
        }
    }

    /** Raw captured tags, before they are judged against the line they ended up on. */
    private static final class Candidate {
        final int[] offsets;
        final long[] times;
        /** Stated end per tag, or null when the format states none. -1 marks one that did not. */
        final long[] ends;
        final int count;
        /** A tag matched the inline shape but stated an impossible time, so nothing here is trusted. */
        final boolean malformed;

        Candidate(int[] offsets, long[] times, int count, boolean malformed) {
            this(offsets, times, null, count, malformed);
        }

        Candidate(int[] offsets, long[] times, long[] ends, int count, boolean malformed) {
            this.offsets = offsets;
            this.times = times;
            this.ends = ends;
            this.count = count;
            this.malformed = malformed;
        }
    }

    /**
     * Removes inline {@code <mm:ss.xx>} tags exactly as a plain strip-and-trim would, and records
     * where each removed tag sat in the resulting visible text.
     *
     * <p>The returned text is character-for-character what this parser produced before inline
     * timing was captured: the same tags are removed, and {@link String#trim()}'s own bounds are
     * reproduced so the captured offsets can be rebased onto it.
     */
    private static Stripped stripWordTimestamps(String remainder, long offset) {
        final Matcher matcher = WORD_TIMESTAMP.matcher(remainder);
        if (!matcher.find()) {
            return new Stripped(remainder.trim(), null);
        }
        final StringBuilder stripped = new StringBuilder(remainder.length());
        int[] offsets = new int[8];
        long[] times = new long[8];
        int count = 0;
        boolean malformed = false;
        int consumed = 0;
        do {
            stripped.append(remainder, consumed, matcher.start());
            consumed = matcher.end();
            final long time = parseWordTimeMs(matcher.group(), offset);
            if (time < 0) {
                // Stripped from the text either way - the visible result must not depend on
                // whether a tag was well formed - but never recorded as timing.
                malformed = true;
                continue;
            }
            if (count == offsets.length) {
                offsets = Arrays.copyOf(offsets, count * 2);
                times = Arrays.copyOf(times, count * 2);
            }
            offsets[count] = stripped.length();
            times[count] = time;
            count++;
        } while (matcher.find());
        stripped.append(remainder, consumed, remainder.length());
        // String.trim() drops every character <= ' ' from both ends; reproducing its bounds here
        // yields the identical string and, unlike trim() itself, tells us how far the text shifted.
        int begin = 0;
        int last = stripped.length();
        while (begin < last && stripped.charAt(begin) <= ' ') begin++;
        while (last > begin && stripped.charAt(last - 1) <= ' ') last--;
        final String text = stripped.substring(begin, last);
        for (int a = 0; a < count; a++) {
            offsets[a] = Math.max(0, Math.min(text.length(), offsets[a] - begin));
        }
        return new Stripped(text, new Candidate(offsets, times, count, malformed));
    }

    /**
     * Reads one inline tag. The pattern has already fixed its shape, so only the stated values can
     * still be out of range. Returns a negative value for a tag that cannot be trusted; the caller
     * strips it from the text regardless. The arithmetic, including {@code [offset:]}, is the same
     * as for line timestamps so both live in one timebase.
     */
    private static long parseWordTimeMs(String tag, long offset) {
        try {
            final int colon = tag.indexOf(':');
            final long minutes = Long.parseLong(tag.substring(1, colon));
            int end = colon + 1;
            while (end < tag.length() && tag.charAt(end) >= '0' && tag.charAt(end) <= '9') end++;
            final long seconds = Long.parseLong(tag.substring(colon + 1, end));
            if (seconds >= 60) return -1;
            long millis = 0;
            if (end < tag.length() - 1) {
                final String fraction = tag.substring(end + 1, tag.length() - 1);
                millis = Long.parseLong(fraction) * (fraction.length() == 1 ? 100 : fraction.length() == 2 ? 10 : 1);
            }
            return Math.max(0, (minutes * 60 + seconds) * 1000 + millis + offset);
        } catch (RuntimeException ignore) {
            return -1;
        }
    }

    /**
     * Keeps the captured tags that this line can represent faithfully, and discards the rest.
     *
     * <p>Every rule here is about integrity, not usefulness: whether a time can belong to this line
     * at all, and whether an offset addresses its text safely. How much of a line is timed, and
     * whether that is enough to animate, is not decided here - that is a judgement for whatever
     * eventually consumes the metadata, and making it here would throw away timing the source
     * genuinely stated.
     *
     * <p>Nothing is adjusted, reordered or filled in. Failing a rule costs the line its optional
     * timing and nothing else - the line, its timestamp and its text are untouched, so a broken
     * karaoke extension can never damage otherwise valid line-synced lyrics.
     */
    private static Segments qualify(Candidate candidate, String text, long lineTimeMs, long nextTimeMs) {
        if (candidate == null || candidate.malformed || candidate.count == 0) return null;
        // A timed blank has no text to address, so it has nothing to time.
        if (text.isEmpty()) return null;
        final int count = candidate.count;
        for (int a = 0; a < count; a++) {
            final long time = candidate.times[a];
            // Stated out of its own line's interval, or running backwards: not this line's timing.
            if (time < lineTimeMs || time >= nextTimeMs) return null;
            if (a > 0 && time < candidate.times[a - 1]) return null;
            if (a > 0 && candidate.offsets[a] < candidate.offsets[a - 1]) return null;
            if (splitsSurrogatePair(text, candidate.offsets[a])) return null;
        }
        final int length = text.length();
        int[] startOffsets = new int[count];
        int[] endOffsets = new int[count];
        long[] startTimes = new long[count];
        long[] endTimes = candidate.ends == null ? null : new long[count];
        int kept = 0;
        boolean anyEnd = false;
        for (int a = 0; a < count; a++) {
            final int start = candidate.offsets[a];
            final int end = a + 1 < count ? candidate.offsets[a + 1] : length;
            // A tag that introduces no visible text - two tags in a row, or one trailing the line -
            // cannot be highlighted, so it is dropped rather than kept as an empty range.
            final int visible = countNonWhitespace(text, start, end);
            if (visible == 0) continue;
            startOffsets[kept] = start;
            endOffsets[kept] = end;
            startTimes[kept] = candidate.times[a];
            if (endTimes != null) {
                // Only a stated end survives, and only when it is actually after its own start and
                // still inside this line. Anything else is recorded as "not stated" rather than
                // repaired, because a repaired end would be an invented one.
                final long stated = candidate.ends[a];
                final boolean usable = stated > candidate.times[a] && stated <= nextTimeMs;
                endTimes[kept] = usable ? stated : -1;
                anyEnd |= usable;
            }
            kept++;
        }
        if (kept == 0) return null;
        return new Segments(
                kept == count ? startOffsets : Arrays.copyOf(startOffsets, kept),
                kept == count ? endOffsets : Arrays.copyOf(endOffsets, kept),
                kept == count ? startTimes : Arrays.copyOf(startTimes, kept),
                !anyEnd ? null : kept == count ? endTimes : Arrays.copyOf(endTimes, kept));
    }

    /** An offset between a high and a low surrogate would cut one character in half. */
    private static boolean splitsSurrogatePair(String text, int offset) {
        return offset > 0 && offset < text.length()
                && Character.isHighSurrogate(text.charAt(offset - 1))
                && Character.isLowSurrogate(text.charAt(offset));
    }

    private static int countNonWhitespace(String text, int start, int end) {
        int count = 0;
        for (int a = start; a < end; a++) {
            if (!Character.isWhitespace(text.charAt(a))) count++;
        }
        return count;
    }

    public State getState(MessageObject message) {
        String key = key(message);
        if (key == null) return State.MISSING;
        synchronized (cache) {
            Entry entry = cache.get(key);
            return entry == null ? State.NOT_LOADED : entry.state;
        }
    }

    public Lyrics getLyrics(MessageObject message) {
        String key = key(message);
        if (key == null) return EMPTY;
        synchronized (cache) {
            Entry entry = cache.get(key);
            if (entry != null) {
                if (entry.state == State.NOT_LOADED) startLoading(key, message, entry);
                return entry.lyrics;
            }
            entry = new Entry(State.LOADING, EMPTY, 0);
            cache.put(key, entry);
            startLoading(key, message, entry);
        }
        return EMPTY;
    }

    public void retryIfFailed(MessageObject message) {
        String key = key(message);
        if (key == null) return;
        synchronized (cache) {
            Entry entry = cache.get(key);
            if (entry != null && entry.state == State.READ_FAILED) {
                entry.state = State.NOT_LOADED;
                startLoading(key, message, entry);
            }
        }
    }

    private void startLoading(String key, MessageObject message, Entry entry) {
        if (entry.state == State.LOADING && entry.generation != 0) return;
        entry.state = State.LOADING;
        entry.message = message;
        final int generation = ++entry.generation;
        Utilities.globalQueue.postRunnable(() -> {
            Lyrics lyrics = EMPTY;
            Lyrics embeddedLyrics = EMPTY;
            boolean embeddedChecked = false;
            boolean suppressed = false;
            State state = State.MISSING;
            try {
                File local = file(key);
                suppressed = suppressionFile(key).exists();
                if (local.exists()) {
                    lyrics = read(local).withOrigin(Source.LOCAL);
                    state = lyrics.kind == Kind.MALFORMED ? State.MALFORMED : lyrics.lines.isEmpty() ? State.MISSING : State.LOADED;
                    File media = mediaFile(message);
                    if (isMediaReady(message, media)) {
                        embeddedLyrics = extractEmbedded(media);
                        embeddedChecked = true;
                    }
                } else {
                    File media = mediaFile(message);
                    if (!isMediaReady(message, media)) {
                        state = State.WAITING_FILE;
                    } else {
                        Lyrics embedded = extractEmbedded(media);
                        embeddedLyrics = embedded;
                        embeddedChecked = true;
                        if (!suppressed) {
                            lyrics = embedded;
                            state = embedded.kind == Kind.MALFORMED ? State.MALFORMED : embedded.lines.isEmpty() ? State.MISSING : State.LOADED;
                        }
                        if (suppressed) state = State.MISSING;
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
                state = State.READ_FAILED;
            }
            final Lyrics loadedLyrics = lyrics;
            final Lyrics loadedEmbeddedLyrics = embeddedLyrics;
            final boolean didCheckEmbedded = embeddedChecked;
            final boolean isSuppressed = suppressed;
            final State loadedState = state;
            AndroidUtilities.runOnUIThread(() -> {
                synchronized (cache) {
                    Entry current = cache.get(key);
                    if (current == null || current.generation != generation) return;
                    current.lyrics = loadedLyrics;
                    current.embeddedLyrics = loadedEmbeddedLyrics;
                    current.embeddedChecked = didCheckEmbedded;
                    current.suppressed = isSuppressed;
                    current.state = loadedState;
                }
                notifyChanged(key);
            });
        });
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.fileLoaded || args.length == 0) return;
        String loadedName = (String) args[0];
        ArrayList<MessageObject> retry = new ArrayList<>();
        synchronized (cache) {
            for (Entry entry : cache.values()) {
                if (entry.message == null || !TextUtils.equals(entry.message.getFileName(), loadedName)) continue;
                // Only entries whose embedded extraction never completed need the media again.
                // A finished EMBEDDED result stays cached: re-parsing it on every playback start
                // re-ran AudioInfo over the whole file for no gain.
                if (entry.state == State.WAITING_FILE || !entry.embeddedChecked) {
                    entry.state = State.WAITING_FILE;
                    retry.add(entry.message);
                }
            }
        }
        for (MessageObject message : retry) retryEmbeddedIfFileAvailable(message);
    }

    public boolean hasLyrics(MessageObject message) {
        return !getLyrics(message).lines.isEmpty();
    }

    public boolean isEmbedded(MessageObject message) {
        return getLyrics(message).origin == Source.EMBEDDED;
    }

    public boolean canRestoreEmbedded(MessageObject message) {
        String key = key(message);
        if (key == null) return false;
        synchronized (cache) {
            Entry entry = cache.get(key);
            return entry != null && entry.suppressed && entry.embeddedChecked
                && !entry.embeddedLyrics.lines.isEmpty() && entry.lyrics.origin != Source.LOCAL;
        }
    }

    public boolean hasEmbeddedLyrics(MessageObject message) {
        String key = key(message);
        if (key == null) return false;
        synchronized (cache) {
            Entry entry = cache.get(key);
            return entry != null && entry.embeddedChecked && !entry.embeddedLyrics.lines.isEmpty();
        }
    }

    public boolean isEmbeddedSuppressed(MessageObject message) {
        String key = key(message);
        if (key == null) return false;
        synchronized (cache) {
            Entry entry = cache.get(key);
            return entry != null && entry.suppressed;
        }
    }

    public void retryEmbeddedIfFileAvailable(MessageObject message) {
        String key = key(message);
        if (key == null) return;
        synchronized (cache) {
            Entry entry = cache.get(key);
            if (entry != null && entry.state == State.WAITING_FILE) {
                entry.state = State.NOT_LOADED;
                startLoading(key, message, entry);
            }
        }
    }

    public void save(MessageObject message, String source, Completion completion) {
        String key = key(message);
        if (key == null) {
            if (completion != null) completion.run(false);
            return;
        }
        Lyrics parsed = parse(source);
        final int generation;
        final Lyrics previous;
        synchronized (cache) {
            Entry entry = cache.get(key);
            if (entry == null) cache.put(key, entry = new Entry(State.NOT_LOADED, EMPTY, 0));
            previous = entry.lyrics;
            generation = ++entry.generation;
        }
        Utilities.globalQueue.postRunnable(() -> {
            boolean success = false;
            boolean suppressionCleared = false;
            File temporary = null;
            File target = file(key);
            try {
                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("Could not create lyrics directory");
                temporary = new File(target.getPath() + ".tmp");
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    output.write(source.getBytes(StandardCharsets.UTF_8));
                    output.getFD().sync();
                }
                if (!temporary.renameTo(target)) throw new IllegalStateException("Could not replace lyrics file");
                File marker = suppressionFile(key);
                // A local override replaces the embedded source; drop the suppression marker so a
                // later cold load does not resurrect a stale "suppressed" flag.
                suppressionCleared = !marker.exists() || marker.delete();
                success = true;
            } catch (Exception e) {
                FileLog.e(e);
            }
            if (!success && temporary != null) temporary.delete();
            final boolean result = success;
            final boolean clearedSuppression = suppressionCleared;
            Lyrics fallback = previous;
            if (!result && fallback == EMPTY && target.exists()) {
                try {
                    fallback = read(target);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            final Lyrics finalFallback = fallback;
            AndroidUtilities.runOnUIThread(() -> {
                synchronized (cache) {
                    Entry entry = cache.get(key);
                    if (entry != null && entry.generation == generation) {
                        entry.lyrics = result ? parsed.withOrigin(Source.LOCAL) : finalFallback;
                        if (result) entry.suppressed = !clearedSuppression;
                        entry.state = result ? (parsed.kind == Kind.MALFORMED ? State.MALFORMED : parsed.lines.isEmpty() ? State.MISSING : State.LOADED) : State.WRITE_FAILED;
                    }
                }
                notifyChanged(key);
                if (completion != null) completion.run(result);
            });
        });
    }

    public void delete(MessageObject message, Completion completion) {
        String key = key(message);
        if (key == null) {
            if (completion != null) completion.run(false);
            return;
        }
        final int generation;
        final Lyrics previous;
        synchronized (cache) {
            Entry entry = cache.get(key);
            if (entry == null) cache.put(key, entry = new Entry(State.NOT_LOADED, EMPTY, 0));
            previous = entry.lyrics;
            generation = ++entry.generation;
        }
        Utilities.globalQueue.postRunnable(() -> {
            File target = file(key);
            File marker = suppressionFile(key);
            final boolean markerExisted = marker.exists();
            // Durable suppression first: if the marker cannot be written we must not have destroyed
            // the user's local override, otherwise the embedded lyrics silently come back while the
            // UI reports a failure.
            boolean deleted = false;
            if (writeSuppression(key)) {
                if (!target.exists() || target.delete()) {
                    deleted = true;
                } else if (!markerExisted) {
                    marker.delete(); // roll back the marker this attempt created
                }
            }
            final boolean success = deleted;
            final boolean suppressedNow = marker.exists();
            Lyrics fallback = previous;
            if (!success && fallback == EMPTY && target.exists()) {
                try {
                    fallback = read(target);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            final Lyrics finalFallback = fallback;
            AndroidUtilities.runOnUIThread(() -> {
                synchronized (cache) {
                    Entry entry = cache.get(key);
                    if (entry != null && entry.generation == generation) {
                        entry.lyrics = success ? EMPTY : finalFallback;
                        entry.state = success ? State.NOT_LOADED : State.WRITE_FAILED;
                        entry.suppressed = suppressedNow;
                        if (success) {
                            startLoading(key, message, entry);
                        }
                    }
                }
                notifyChanged(key);
                if (completion != null) completion.run(success);
            });
        });
    }

    public void restoreEmbedded(MessageObject message, Completion completion) {
        String key = key(message);
        if (key == null) {
            if (completion != null) completion.run(false);
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            boolean success = !suppressionFile(key).exists() || suppressionFile(key).delete();
            AndroidUtilities.runOnUIThread(() -> {
                synchronized (cache) {
                    Entry entry = cache.get(key);
                    if (success && entry != null) {
                        entry.suppressed = false;
                        if (entry.embeddedChecked) {
                            entry.lyrics = entry.embeddedLyrics;
                            entry.state = entry.lyrics.kind == Kind.MALFORMED ? State.MALFORMED : entry.lyrics.lines.isEmpty() ? State.MISSING : State.LOADED;
                        } else {
                            entry.state = State.NOT_LOADED;
                            startLoading(key, message, entry);
                        }
                    }
                }
                notifyChanged(key);
                if (completion != null) completion.run(success);
            });
        });
    }

    private void notifyChanged(String key) {
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.syncedLyricsChanged, key);
    }

    public void clear() {
        synchronized (cache) {
            cache.clear();
            lyricsModePreferred = false;
        }
        Utilities.globalQueue.postRunnable(() -> deleteRecursively(new File(ApplicationLoader.applicationContext.getFilesDir(), "lyrics/" + account)));
    }

    private static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        file.delete();
    }

    private static Lyrics read(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return parse(new String(output.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private boolean writeSuppression(String key) {
        File target = suppressionFile(key);
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
            if (target.exists()) return true;
            try (FileOutputStream output = new FileOutputStream(target)) {
                output.write(1);
                output.getFD().sync();
            }
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    private File mediaFile(MessageObject message) {
        if (message == null) return null;
        if (!TextUtils.isEmpty(message.messageOwner.attachPath)) {
            File attached = new File(message.messageOwner.attachPath);
            if (attached.exists()) return attached;
        }
        return FileLoader.getInstance(account).getPathToMessage(message.messageOwner);
    }

    private boolean isMediaReady(MessageObject message, File media) {
        return message != null && media != null && media.exists() && media.length() > 0 && !FileLoader.getInstance(account).isLoadingFile(message.getFileName());
    }

    private Lyrics extractEmbedded(File media) {
        AudioInfo info = AudioInfo.getAudioInfo(media);
        String source = info == null ? null : info.getLyrics();
        if (source == null || source.length() > MAX_EMBEDDED_LYRICS_LENGTH) return EMPTY;
        Lyrics embedded = parse(source).withOrigin(Source.EMBEDDED);
        if (embedded.lines.size() > MAX_EMBEDDED_LYRICS_LINES) {
            return new Lyrics(Collections.emptyList(), source, Kind.MALFORMED, Source.EMBEDDED);
        }
        return embedded;
    }

    public static long positionMs(MessageObject message) {
        if (message == null) return 0;
        long progress = MediaController.getInstance().getProgressMs(message);
        return progress >= 0 ? progress : (long) (message.audioProgress * message.getDuration() * 1000L);
    }

    private String key(MessageObject message) {
        if (message == null) return null;
        TLRPC.Document document = message.getDocument();
        if (document != null && document.id != 0) return "d_" + document.dc_id + "_" + document.id;
        return "m_" + message.getDialogId() + "_" + message.getId();
    }

    private File file(String key) {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), "lyrics/" + account + "/" + key + ".lrc");
    }

    private File suppressionFile(String key) {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), "lyrics/" + account + "/" + key + ".suppressed");
    }
}
