/*
 * This is the source code of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */
package org.telegram.messenger;

import android.text.TextUtils;
import android.util.Xml;

import org.telegram.tgnet.TLRPC;
import org.telegram.messenger.audioinfo.AudioInfo;
import org.telegram.ui.Components.LyricsOnlineSearch;
import org.telegram.ui.Components.LyricsTuning;

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
    // region background vocals
    /**
     * The same line laid out for display with its background vocals - the text inside
     * parentheses - moved onto a second line: "main\nbackground". Returns {@code line} itself
     * when there is nothing to move (no parentheses, or nothing left outside them).
     *
     * <p>Inline timing moves with the text: every segment keeps its own time and covers the same
     * characters at their new offsets, so a consumer that reads the display line sees exactly what
     * the source stated. Segments stay in time order, which may now differ from text order.
     * Parentheses are dropped, the space they leave in the main line is collapsed, and several
     * parenthesised parts are joined with one space.
     */
    public static Line splitBackgroundVocals(Line line) {
        if (line == null || isEmptyText(line.text)) return line;
        final String text = line.text;
        if (text.indexOf('(') < 0 && text.indexOf('\uFF08') < 0) return line;
        final int length = text.length();
        // Which characters are background (inside the outermost parentheses), and which are the
        // parentheses themselves.
        final boolean[] inside = new boolean[length];
        final boolean[] paren = new boolean[length];
        int depth = 0;
        for (int i = 0; i < length; i++) {
            final char c = text.charAt(i);
            if (c == '(' || c == '\uFF08') {
                paren[i] = true;
                depth++;
            } else if ((c == ')' || c == '\uFF09') && depth > 0) {
                paren[i] = true;
                depth--;
            } else {
                inside[i] = depth > 0;
            }
        }
        if (depth != 0) return line; // unbalanced: leave the line as the source wrote it
        final int[] map = new int[length];
        final StringBuilder main = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            map[i] = -1;
            if (paren[i] || inside[i]) continue;
            final char c = text.charAt(i);
            if (Character.isWhitespace(c) && (main.length() == 0 || Character.isWhitespace(main.charAt(main.length() - 1)))) continue;
            map[i] = main.length();
            main.append(c);
        }
        while (main.length() > 0 && Character.isWhitespace(main.charAt(main.length() - 1))) main.setLength(main.length() - 1);
        final StringBuilder background = new StringBuilder();
        final int[] bgMap = new int[length];
        boolean inGroup = false;
        for (int i = 0; i < length; i++) {
            bgMap[i] = -1;
            if (!inside[i]) {
                inGroup = false;
                continue;
            }
            if (!inGroup && background.length() > 0 && !Character.isWhitespace(background.charAt(background.length() - 1))) {
                background.append(' '); // between two parenthesised parts
            }
            inGroup = true;
            final char c = text.charAt(i);
            if (Character.isWhitespace(c) && (background.length() == 0 || Character.isWhitespace(background.charAt(background.length() - 1)))) continue;
            bgMap[i] = background.length();
            background.append(c);
        }
        while (background.length() > 0 && Character.isWhitespace(background.charAt(background.length() - 1))) background.setLength(background.length() - 1);
        if (main.length() == 0 || background.length() == 0) return line;
        final int bgOffset = main.length() + 1;
        for (int i = 0; i < length; i++) {
            if (map[i] >= main.length()) map[i] = -1; // trimmed away
            if (bgMap[i] >= 0) map[i] = bgMap[i] < background.length() ? bgOffset + bgMap[i] : -1;
        }
        final String display = main + "\n" + background;
        Segments segments = null;
        if (line.segments != null) {
            final Segments source = line.segments;
            final int n = source.size();
            final int[] starts = new int[n];
            final int[] ends = new int[n];
            final long[] times = new long[n];
            final long[] endTimes = source.endTimes != null ? new long[n] : null;
            int count = 0;
            for (int k = 0; k < n; k++) {
                final int from = Math.max(0, Math.min(length, source.startOffset(k)));
                final int to = Math.max(from, Math.min(length, source.endOffset(k)));
                // The segment moves to where its first kept character went, and covers its kept
                // characters on that same side of the break.
                int first = -1;
                for (int i = from; i < to; i++) {
                    if (map[i] >= 0) {
                        first = i;
                        break;
                    }
                }
                if (first < 0) continue; // only parentheses or spaces: nothing left to fill
                final boolean firstBackground = map[first] >= bgOffset;
                int last = first;
                for (int i = first; i < to; i++) {
                    if (map[i] >= 0 && (map[i] >= bgOffset) == firstBackground) last = i;
                }
                starts[count] = map[first];
                ends[count] = map[last] + 1;
                times[count] = source.startTimeMs(k);
                if (endTimes != null) endTimes[count] = source.endTimes[k];
                count++;
            }
            if (count > 0) {
                segments = new Segments(java.util.Arrays.copyOf(starts, count), java.util.Arrays.copyOf(ends, count),
                        java.util.Arrays.copyOf(times, count), endTimes != null ? java.util.Arrays.copyOf(endTimes, count) : null);
            }
        }
        return new Line(line.timeMs, display, line.timed, segments);
    }
    // endregion

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

    /**
     * Converts a TTML document (Apple Music's word-timed lyrics) into this app's own lyrics text,
     * so it can be opened and edited as text like any other lyrics. Nothing the document states
     * is lost:
     *
     * <ul>
     *     <li>Every word or syllable keeps its begin as an inline tag before it, and its end as a
     *     tag right after it. The end tag is left out only where it equals the next tag's time,
     *     which the parser reads back as the same end.</li>
     *     <li>Background vocals ({@code ttm:role="x-bg"}) follow the main words in parentheses,
     *     with their own word timing, and are shown as their own small line.</li>
     *     <li>A line is stamped with the earliest time it states, so a word is never before its
     *     line. Its end is its last word's end, background vocals included, and may be after the
     *     next line's start.</li>
     * </ul>
     *
     * Translations and romanisations are skipped, and singer agents ({@code ttm:agent}) are
     * ignored. Returns null when the document cannot be read or states no line.
     */
    public static String ttmlToText(String source) {
        if (source == null) return null;
        String ttml = source;
        if (ttml.length() > 0 && ttml.charAt(0) == '\ufeff') ttml = ttml.substring(1);
        if (!looksLikeTtml(ttml)) return null;
        final StringBuilder out = new StringBuilder();
        try {
            final XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
            parser.setInput(new StringReader(ttml));
            boolean inBody = false;
            boolean inLine = false;
            long lineBegin = -1;
            final TtmlPart main = new TtmlPart();
            final TtmlPart background = new TtmlPart();
            int skipDepth = -1;
            int backgroundDepth = -1;
            int timedDepth = -1;
            int lines = 0;
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
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
                        lineBegin = parseTtmlTime(parser.getAttributeValue(null, "begin"));
                        main.reset();
                        background.reset();
                        backgroundDepth = -1;
                        timedDepth = -1;
                    } else if (inLine && "span".equals(name)) {
                        final String role = parser.getAttributeValue(TTML_METADATA_NS, "role");
                        if ("x-translation".equals(role) || "x-roman".equals(role)) {
                            skipDepth = parser.getDepth();
                        } else if ("x-bg".equals(role)) {
                            if (backgroundDepth < 0) backgroundDepth = parser.getDepth();
                        } else if (timedDepth < 0) {
                            final long begin = parseTtmlTime(parser.getAttributeValue(null, "begin"));
                            if (begin >= 0) {
                                timedDepth = parser.getDepth();
                                final TtmlPart part = backgroundDepth >= 0 ? background : main;
                                part.begin(begin, parseTtmlTime(parser.getAttributeValue(null, "end")));
                            }
                        }
                    }
                } else if (event == XmlPullParser.TEXT) {
                    if (skipDepth < 0 && inLine) {
                        final TtmlPart part = backgroundDepth >= 0 ? background : main;
                        part.text(parser.getText(), backgroundDepth >= 0);
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    final String name = parser.getName();
                    if (skipDepth >= 0) {
                        if (parser.getDepth() <= skipDepth) skipDepth = -1;
                    } else if (inLine && "span".equals(name)) {
                        if (timedDepth >= 0 && parser.getDepth() <= timedDepth) timedDepth = -1;
                        if (backgroundDepth >= 0 && parser.getDepth() <= backgroundDepth) backgroundDepth = -1;
                    } else if (inLine && "p".equals(name)) {
                        inLine = false;
                        long time = lineBegin;
                        if (main.first >= 0 && (time < 0 || main.first < time)) time = main.first;
                        if (background.first >= 0 && (time < 0 || background.first < time)) time = background.first;
                        final String mainText = main.finish();
                        final String backgroundText = background.finish();
                        if (time >= 0 && (mainText.length() > 0 || backgroundText.length() > 0)) {
                            out.append('[').append(formatTextTime(time)).append(']').append(mainText);
                            if (backgroundText.length() > 0) {
                                if (mainText.length() > 0) out.append(' ');
                                out.append('(').append(backgroundText).append(')');
                            }
                            out.append('\n');
                            lines++;
                        }
                    } else if ("body".equals(name)) {
                        inBody = false;
                    }
                }
                event = parser.next();
            }
            if (lines == 0) return null;
        } catch (Throwable e) {
            return null;
        }
        return out.toString();
    }

    /** One part of a TTML line (the main words, or the background vocals) being written as text. */
    private static final class TtmlPart {
        final StringBuilder text = new StringBuilder();
        long first;
        long pendingEnd;
        boolean pendingSpace;

        void reset() {
            text.setLength(0);
            first = -1;
            pendingEnd = -1;
            pendingSpace = false;
        }

        void begin(long begin, long end) {
            if (first < 0) first = begin;
            // The previous word's end, unless it is exactly this word's start (read back as such).
            if (pendingEnd >= 0 && pendingEnd != begin) text.append('<').append(formatTextTime(pendingEnd)).append('>');
            if (pendingSpace && text.length() > 0) text.append(' ');
            pendingSpace = false;
            text.append('<').append(formatTextTime(begin)).append('>');
            pendingEnd = end > begin ? end : -1;
        }

        void text(String chunk, boolean background) {
            if (chunk == null) return;
            for (int a = 0; a < chunk.length(); a++) {
                char c = chunk.charAt(a);
                // The background part is written inside one pair of parentheses of its own.
                if (background && (c == '(' || c == ')' || c == '\uFF08' || c == '\uFF09')) continue;
                // Characters the lyrics text reads as markup would change the timing if kept.
                if (c == '<' || c == '>' || c == '[' || c == ']') continue;
                if (Character.isWhitespace(c)) {
                    pendingSpace = text.length() > 0;
                    continue;
                }
                if (pendingSpace) {
                    text.append(' ');
                    pendingSpace = false;
                }
                text.append(c);
            }
        }

        String finish() {
            if (pendingEnd >= 0) text.append('<').append(formatTextTime(pendingEnd)).append('>');
            pendingEnd = -1;
            return text.toString().trim();
        }
    }

    /** mm:ss.xxx, the millisecond precision TTML states. */
    private static String formatTextTime(long ms) {
        final long minutes = ms / 60000;
        final long seconds = (ms / 1000) % 60;
        final long millis = ms % 1000;
        return String.format(java.util.Locale.US, "%02d:%02d.%03d", minutes, seconds, millis);
    }

    private static final Pattern SINGER_LABEL = Pattern.compile(
            "(?m)^((?:[ \\t]*(?:\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?]|<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>))*[ \\t]*)v\\d+:[ \\t]?");

    /**
     * Removes singer labels ("v1:", "v2:", "v1000:") from the start of each line, including
     * right after the line's time tags, and nothing else.
     */
    public static String stripSingerLabels(String text) {
        if (text == null || text.indexOf(':') < 0) return text;
        return SINGER_LABEL.matcher(text).replaceAll("$1");
    }

    /**
     * What an imported file becomes in the editor: a TTML document converted into the app's own
     * lyrics text (so it can be edited), and in every format the singer labels removed.
     */
    public static String prepareImportedLyrics(String source) {
        if (source == null) return null;
        String text = source;
        final String converted = ttmlToText(source);
        if (converted != null) text = converted;
        return stripSingerLabels(text);
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
            final long afterNextTimeMs = i + 2 < result.size() ? result.get(i + 2).timeMs : Long.MAX_VALUE;
            final Segments segments = qualify(candidates.get(i), line.text, line.timeMs, nextTimeMs, afterNextTimeMs);
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
            while (matcher.find() && (matcher.start() == end || isEmptyText(sourceLine.substring(end, matcher.start()).trim()))) {
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

    /** {@code TextUtils.isEmpty}, kept local so parsing needs nothing from the platform (and runs in a plain JVM test). */
    private static boolean isEmptyText(CharSequence text) {
        return text == null || text.length() == 0;
    }

    private static boolean hasValidLeadingTimestamp(String sourceLine) {
        Matcher timestamp = TIMESTAMP.matcher(sourceLine);
        if (!timestamp.find() || !isEmptyText(sourceLine.substring(0, timestamp.start()).trim())) return false;
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
    private static Segments qualify(Candidate candidate, String text, long lineTimeMs, long nextTimeMs, long afterNextTimeMs) {
        if (candidate == null || candidate.malformed || candidate.count == 0) return null;
        // A timed blank has no text to address, so it has nothing to time.
        if (text.isEmpty()) return null;
        final int count = candidate.count;
        final int length = text.length();
        // A tag that introduces no text of its own (only spaces or parentheses before the next
        // tag or the end of the line) is an END tag: it states when the word before it ends.
        // Lyrics written without end tags parse exactly as before; a trailing tag used to be
        // dropped, and is now the stated end of the line's last word.
        final boolean[] endTag = new boolean[count];
        for (int a = 0; a < count; a++) {
            final int start = candidate.offsets[a];
            final int end = a + 1 < count ? candidate.offsets[a + 1] : length;
            endTag[a] = countLyricChars(text, start, end) == 0;
        }
        long lastMain = Long.MIN_VALUE, lastBackground = Long.MIN_VALUE;
        for (int a = 0; a < count; a++) {
            if (endTag[a]) continue;
            final long time = candidate.times[a];
            // Stated out of its own line's interval, or running backwards: not this line's timing.
            // Lines may overlap (Apple Music's lyrics often do: an echo in parentheses, or the
            // end of a line, sung over the start of the next one), so a main word may start after
            // the next line has started, but never after the one after it. Main words and
            // background words are two independent timed parts, each in time order on its own; a
            // background part may start before the main words written ahead of it have, and,
            // being an echo that can be sung over any number of later lines, it is not bounded by
            // the lines that follow (a word of one may start after the line after next has).
            final boolean background = isBackgroundVocalAt(text, candidate.offsets[a]);
            if (time < lineTimeMs || !background && time >= afterNextTimeMs) return null;
            if (background ? time < lastBackground : time < lastMain) return null;
            if (background) lastBackground = time;
            else lastMain = time;
            if (a > 0 && candidate.offsets[a] < candidate.offsets[a - 1]) return null;
            if (splitsSurrogatePair(text, candidate.offsets[a])) return null;
        }
        int[] startOffsets = new int[count];
        int[] endOffsets = new int[count];
        long[] startTimes = new long[count];
        long[] endTimes = new long[count];
        int kept = 0;
        boolean anyEnd = false;
        for (int a = 0; a < count; a++) {
            if (endTag[a]) continue;
            final int start = candidate.offsets[a];
            final int end = a + 1 < count ? candidate.offsets[a + 1] : length;
            // A tag that introduces no visible text - two tags in a row, or one trailing the line -
            // cannot be highlighted, so it is dropped rather than kept as an empty range.
            if (countNonWhitespace(text, start, end) == 0) continue;
            startOffsets[kept] = start;
            endOffsets[kept] = end;
            startTimes[kept] = candidate.times[a];
            // Only a stated end survives: TTML's own end attribute, or an end tag right after
            // the word. It must be after the word's start and, for a main word, no later than the
            // line after next (a line may run on over the start of the next one); a background
            // word's part is unbounded like its starts. Anything else is "not stated" rather than
            // repaired, because a repaired end would be an invented one.
            long stated = candidate.ends != null ? candidate.ends[a] : -1;
            if (a + 1 < count && endTag[a + 1]) stated = candidate.times[a + 1];
            final boolean usable = stated > candidate.times[a]
                    && (stated <= afterNextTimeMs || isBackgroundVocalAt(text, start));
            endTimes[kept] = usable ? stated : -1;
            anyEnd |= usable;
            kept++;
        }
        if (kept == 0) return null;
        startOffsets = Arrays.copyOf(startOffsets, kept);
        endOffsets = Arrays.copyOf(endOffsets, kept);
        startTimes = Arrays.copyOf(startTimes, kept);
        endTimes = anyEnd ? Arrays.copyOf(endTimes, kept) : null;
        // Consumers find the current segment by time (a binary search), so segments are kept in
        // time order. That differs from text order only where a background part starts before
        // the main words written ahead of it have; a stable sort keeps everything else as is.
        boolean sorted = true;
        for (int a = 1; a < kept && sorted; a++) sorted = startTimes[a] >= startTimes[a - 1];
        if (!sorted) {
            final Integer[] order = new Integer[kept];
            for (int a = 0; a < kept; a++) order[a] = a;
            final long[] times = startTimes;
            Arrays.sort(order, (x, y) -> Long.compare(times[x], times[y]));
            final int[] so = new int[kept], eo = new int[kept];
            final long[] st = new long[kept], et = endTimes == null ? null : new long[kept];
            for (int a = 0; a < kept; a++) {
                so[a] = startOffsets[order[a]];
                eo[a] = endOffsets[order[a]];
                st[a] = startTimes[order[a]];
                if (et != null) et[a] = endTimes[order[a]];
            }
            startOffsets = so;
            endOffsets = eo;
            startTimes = st;
            endTimes = et;
        }
        return new Segments(startOffsets, endOffsets, startTimes, endTimes);
    }

    /** Characters that make a tag's range a word: anything but whitespace and parentheses. */
    private static int countLyricChars(String text, int start, int end) {
        int count = 0;
        for (int a = start; a < end; a++) {
            final char c = text.charAt(a);
            if (!Character.isWhitespace(c) && c != '(' && c != ')' && c != '\uFF08' && c != '\uFF09') count++;
        }
        return count;
    }

    /**
     * True when the text a tag at {@code offset} introduces is inside parentheses (ASCII or
     * full-width), or opens them: background vocals, as {@link #splitBackgroundVocals} shows them.
     */
    private static boolean isBackgroundVocalAt(String text, int offset) {
        int depth = 0;
        for (int i = 0; i < offset && i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '(' || c == '\uFF08') depth++;
            else if ((c == ')' || c == '\uFF09') && depth > 0) depth--;
        }
        int i = offset;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        if (i >= text.length()) return false;
        final char first = text.charAt(i);
        return depth > 0 ? first != ')' && first != '\uFF09' : first == '(' || first == '\uFF08';
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
            boolean notFoundOnline = false;
            State state = State.MISSING;
            try {
                File local = file(key);
                suppressed = suppressionFile(key).exists();
                final File notFound = notFoundFile(key);
                notFoundOnline = notFound.exists()
                        && System.currentTimeMillis() - notFound.lastModified() < LyricsTuning.ONLINE_NOT_FOUND_TTL_MS;
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
            final boolean skipOnline = notFoundOnline;
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
                // No lyrics on the device and none in the file (and the user did not delete
                // them): ask Apple Music, unless it said "not found" for this song recently.
                if (loadedState == State.MISSING && !isSuppressed && didCheckEmbedded && !skipOnline) {
                    fetchOnline(key, message);
                }
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

    /** Marks a song Apple Music has no lyrics for; its age is how long ago that was learned. */
    private File notFoundFile(String key) {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), "lyrics/" + account + "/" + key + ".notfound");
    }

    private final java.util.HashSet<String> onlineFetching = new java.util.HashSet<>();
    private static final Pattern AUDIO_EXTENSION = Pattern.compile("(?i)\\.(mp3|m4a|mp4|aac|flac|ogg|oga|opus|wav|wma|alac|aiff?)$");

    /**
     * Fetches the playing song's lyrics from Apple Music (Paxsenix; see LyricsOnlineSearch) and
     * keeps them on the device as the song's own lyrics file, so they open in the editor like any
     * other and are never fetched again. A real "not found" is remembered for
     * ONLINE_NOT_FOUND_TTL_MS; a passing failure is retried inside the search and otherwise simply
     * tried again the next time the song's lyrics are loaded.
     */
    private void fetchOnline(String key, MessageObject message) {
        if (message == null || onlineFetching.contains(key)) return;
        final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null || !key.equals(key(playing))) return;
        String title = message.getMusicTitle(false);
        String artist = message.getMusicAuthor(false);
        title = title == null ? "" : title.trim();
        artist = artist == null ? "" : artist.trim();
        if (title.equals(LocaleController.getString(R.string.AudioUnknownTitle))) title = "";
        if (artist.equals(LocaleController.getString(R.string.AudioUnknownArtist))) artist = "";
        title = AUDIO_EXTENSION.matcher(title).replaceFirst("").trim();
        if (title.isEmpty()) return;
        onlineFetching.add(key);
        LyricsOnlineSearch.search(artist, title, message.getDuration(), (lyrics, error, detail) -> {
            onlineFetching.remove(key);
            final boolean stillMissing;
            synchronized (cache) {
                final Entry entry = cache.get(key);
                stillMissing = entry != null && entry.state == State.MISSING && !entry.suppressed
                        && entry.lyrics.lines.isEmpty();
            }
            if (lyrics != null) {
                if (stillMissing) save(message, lyrics, null);
            } else if (error == LyricsOnlineSearch.Error.NOT_FOUND) {
                Utilities.globalQueue.postRunnable(() -> {
                    try {
                        final File marker = notFoundFile(key);
                        final File parent = marker.getParentFile();
                        if (parent != null && !parent.exists()) parent.mkdirs();
                        new FileOutputStream(marker).close();
                        marker.setLastModified(System.currentTimeMillis());
                    } catch (Exception ignored) {
                        // Without the marker the song is simply asked for again next time.
                    }
                });
            }
        });
    }

    private File suppressionFile(String key) {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), "lyrics/" + account + "/" + key + ".suppressed");
    }
}
