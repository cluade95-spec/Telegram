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

    public static final class Line {
        public final long timeMs;
        public final String text;
        public final boolean timed;

        private Line(long timeMs, String text, boolean timed) {
            this.timeMs = timeMs;
            this.text = text;
            this.timed = timed;
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
            String text = WORD_TIMESTAMP.matcher(sourceLine.substring(end)).replaceAll("").trim();
            for (Long time : times) parsed.add(new ParsedLine(time, text, order++));
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
            result.add(new Line(parsed.get(i).timeMs, combined.toString(), true));
            i = j;
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
        final int order;

        ParsedLine(long timeMs, String text, int order) {
            this.timeMs = timeMs;
            this.text = text;
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
