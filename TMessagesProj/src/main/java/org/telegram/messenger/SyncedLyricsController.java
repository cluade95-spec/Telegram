/*
 * This is the source code of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */
package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local, client-only storage and parsing for timestamped music lyrics. */
public final class SyncedLyricsController {
    private static final Pattern TIMESTAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[\\.:](\\d{1,3}))?\\]");
    private static final SyncedLyricsController[] instances = new SyncedLyricsController[UserConfig.MAX_ACCOUNT_COUNT];
    private static final Lyrics EMPTY = new Lyrics(Collections.emptyList(), "");

    public enum State {
        NOT_LOADED, LOADING, LOADED, MISSING, READ_FAILED, WRITE_FAILED
    }

    public interface Completion {
        void run(boolean success);
    }

    private static final class Entry {
        State state;
        Lyrics lyrics;
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

        private Line(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    public static final class Lyrics {
        public final ArrayList<Line> lines;
        public final String source;

        private Lyrics(java.util.List<Line> lines, String source) {
            this.lines = new ArrayList<>(lines);
            this.source = source;
        }

        public int lineAt(long positionMs) {
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
    private final HashMap<String, Entry> cache = new HashMap<>();

    private SyncedLyricsController(int account) {
        this.account = account;
    }

    public static Lyrics parse(String source) {
        if (source == null) return EMPTY;
        ArrayList<ParsedLine> parsed = new ArrayList<>();
        String[] sourceLines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
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
                    times.add((minutes * 60 + seconds) * 1000 + millis);
                    end = matcher.end();
                } catch (RuntimeException ignore) {
                    times.clear();
                    break;
                }
            }
            if (times.isEmpty()) continue;
            String text = sourceLine.substring(end).trim();
            for (Long time : times) parsed.add(new ParsedLine(time, text, order++));
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
            result.add(new Line(parsed.get(i).timeMs, combined.toString()));
            i = j;
        }
        return new Lyrics(result, source);
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
                if (entry.state == State.NOT_LOADED) startLoading(key, entry);
                return entry.lyrics;
            }
            entry = new Entry(State.LOADING, EMPTY, 0);
            cache.put(key, entry);
            startLoading(key, entry);
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
                startLoading(key, entry);
            }
        }
    }

    private void startLoading(String key, Entry entry) {
        if (entry.state == State.LOADING && entry.generation != 0) return;
        entry.state = State.LOADING;
        final int generation = ++entry.generation;
        Utilities.globalQueue.postRunnable(() -> {
            Lyrics lyrics = EMPTY;
            State state = State.MISSING;
            try {
                File file = file(key);
                if (file.exists()) {
                    lyrics = read(file);
                    state = lyrics.lines.isEmpty() ? State.MISSING : State.LOADED;
                }
            } catch (Exception e) {
                FileLog.e(e);
                state = State.READ_FAILED;
            }
            final Lyrics loadedLyrics = lyrics;
            final State loadedState = state;
            AndroidUtilities.runOnUIThread(() -> {
                synchronized (cache) {
                    Entry current = cache.get(key);
                    if (current == null || current.generation != generation) return;
                    current.lyrics = loadedLyrics;
                    current.state = loadedState;
                }
                notifyChanged(key);
            });
        });
    }

    public boolean hasLyrics(MessageObject message) {
        return !getLyrics(message).lines.isEmpty();
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
                success = true;
            } catch (Exception e) {
                FileLog.e(e);
            }
            if (!success && temporary != null) temporary.delete();
            final boolean result = success;
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
                        entry.lyrics = result ? parsed : finalFallback;
                        entry.state = result ? (parsed.lines.isEmpty() ? State.MISSING : State.LOADED) : State.WRITE_FAILED;
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
            boolean success = !target.exists() || target.delete();
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
                        entry.state = success ? State.MISSING : State.WRITE_FAILED;
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

    private static Lyrics read(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return parse(new String(output.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    public static long positionMs(MessageObject message) {
        return message == null ? 0 : (long) (message.audioProgress * message.getDuration() * 1000L);
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
}
