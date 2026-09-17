/*
 * This is the source code of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */
package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;
import org.telegram.messenger.audioinfo.AudioInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
     * other word-timed formats are syllable-granular. A segment carries only what the source
     * actually stated - a genuine start time and the range of visible text it introduces. There is
     * deliberately no end time: Enhanced LRC states starts only, and the end of the last segment on
     * a line is not stated anywhere. A consumer that needs an end can derive one from the next
     * segment's start; this model never invents one.
     *
     * <p>Offsets are UTF-16 indices into {@link Line#text}, which is what every Android text
     * consumer ({@code String}, {@code Spannable}, {@code Layout}, {@code Canvas}) already indexes,
     * so emoji, Amharic, combining marks, RTL and CJK need no special handling. No offset is ever
     * allowed to fall between a surrogate pair.
     *
     * <p>Instances are immutable and hold parallel primitive arrays: a whole song is a few hundred
     * segments, and a per-frame consumer must not chase objects or allocate to read one.
     */
    public static final class Segments {
        private final int[] startOffsets;
        private final int[] endOffsets;
        private final long[] startTimes;

        private Segments(int[] startOffsets, int[] endOffsets, long[] startTimes) {
            this.startOffsets = startOffsets;
            this.endOffsets = endOffsets;
            this.startTimes = startTimes;
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
    }

    public static final class Line {
        public final long timeMs;
        public final String text;
        public final boolean timed;
        /**
         * Genuine inline timing for this line, or null when the source stated none. Null is the
         * overwhelmingly common case and the only one that existed before word timing: a line
         * without inline timing carries no empty arrays and no placeholder segments.
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

    public static Lyrics parse(String source) {
        if (source == null) return EMPTY;
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
        if (!result.isEmpty() && !hasUntimedContent) {
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

    /**
     * Fraction of a line's non-whitespace text that inline timing must actually address before the
     * line counts as word-timed. Two tagged words in a nine-word line are not karaoke, and calling
     * them karaoke would force a consumer to invent timing for the rest.
     */
    private static final float MIN_SEGMENT_COVERAGE = 0.8f;

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
        final int count;
        /** A tag matched the inline shape but stated an impossible time, so nothing here is trusted. */
        final boolean malformed;

        Candidate(int[] offsets, long[] times, int count, boolean malformed) {
            this.offsets = offsets;
            this.times = times;
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
     * Decides whether captured tags are genuine timing for the line they landed on. Everything here
     * either accepts what the source stated or discards it: no time is adjusted, reordered, or
     * filled in. Failing any rule costs the line its optional timing and nothing else - the line,
     * its timestamp and its text are untouched, so a broken karaoke extension can never damage
     * otherwise valid line-synced lyrics.
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
        int kept = 0;
        int covered = 0;
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
            kept++;
            covered += visible;
        }
        // One segment spanning the whole line says no more than the line's own timestamp does, and
        // is exactly what a converter emits when it only ever had line timing. Word timing has to
        // subdivide the line to be word timing at all, so a single segment is discarded rather than
        // presented as something it is not.
        if (kept < 2) return null;
        final int total = countNonWhitespace(text, 0, length);
        if (total == 0 || covered < total * MIN_SEGMENT_COVERAGE) return null;
        return new Segments(
                kept == count ? startOffsets : Arrays.copyOf(startOffsets, kept),
                kept == count ? endOffsets : Arrays.copyOf(endOffsets, kept),
                kept == count ? startTimes : Arrays.copyOf(startTimes, kept));
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
