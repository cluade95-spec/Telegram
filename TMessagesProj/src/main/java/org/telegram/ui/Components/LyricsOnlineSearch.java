/*
 * This is the source code of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */
package org.telegram.ui.Components;

import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SyncedLyricsController;
import org.telegram.messenger.Utilities;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Online lyrics, from one source only: Apple Music's word-timed lyrics through the public
 * Paxsenix endpoint. No other source, no fallback, and no private server or token.
 *
 * <ol>
 *     <li>The song is found with the public iTunes Search API
 *     ({@code https://itunes.apple.com/search?term=...&entity=song}). The first result is often a
 *     remix, a live cut or a different edit, so the result is chosen by title, artist and length
 *     against the playing track.</li>
 *     <li>Its trackId goes to {@code https://lyrics.paxsenix.org/apple-music/lyrics?id=...&ttml=true},
 *     which answers JSON with the TTML text in {@code content}, or {@code "error": true} with a
 *     message. "Track not found" means Apple Music has no lyrics for the song; "Apple Music
 *     temporarily unavailable" is a passing failure.</li>
 *     <li>The TTML is converted into the app's own lyrics text
 *     ({@link SyncedLyricsController#ttmlToText}), with singer labels removed.</li>
 * </ol>
 *
 * A passing failure (the network, a 5xx or 429, "temporarily unavailable") is retried
 * {@code LyricsTuning.ONLINE_RETRY_DELAYS_MS.length} times with growing waits, never endlessly,
 * and is never reported as "no lyrics".
 */
public final class LyricsOnlineSearch {

    public enum Error {
        /** Apple Music has no lyrics for the song, or no iTunes result is the song. */
        NOT_FOUND,
        /** The network failed on every try. */
        NETWORK,
        /** Paxsenix or Apple Music stayed temporarily unavailable on every try. */
        RATE_LIMITED,
        /** An answer that could not be read. */
        MALFORMED,
        /** Any other failure. */
        SERVER
    }

    public interface Callback {
        /** Exactly one of {@code lyrics} (the app's lyrics text) and {@code error} is non-null. */
        void onResult(String lyrics, Error error);
    }

    private static final String ITUNES_SEARCH = "https://itunes.apple.com/search";
    private static final String PAXSENIX_LYRICS = "https://lyrics.paxsenix.org/apple-music/lyrics";
    private static final String USER_AGENT = "Telegram-Android-Lyrics";
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private static final int MAX_QUERY_LENGTH = 200;

    private LyricsOnlineSearch() {
    }

    /** Handle for abandoning a search. After {@link #cancel()} the callback never runs. */
    public static final class Request {
        private volatile boolean cancelled;
        private volatile HttpURLConnection connection;

        public void cancel() {
            cancelled = true;
            final HttpURLConnection current = connection;
            connection = null;
            if (current != null) {
                try {
                    current.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    /** One try's outcome: lyrics, a final error, or a passing failure worth another try. */
    private static final class Outcome {
        String lyrics;
        Error error;
        boolean temporary;
        long retryAfterMs;

        static Outcome lyrics(String text) {
            final Outcome o = new Outcome();
            o.lyrics = text;
            return o;
        }

        static Outcome error(Error error) {
            final Outcome o = new Outcome();
            o.error = error;
            return o;
        }

        static Outcome temporary(Error error, long retryAfterMs) {
            final Outcome o = new Outcome();
            o.error = error;
            o.temporary = true;
            o.retryAfterMs = retryAfterMs;
            return o;
        }
    }

    /**
     * Finds and fetches lyrics off the UI thread and delivers one result on the UI thread, unless
     * the request is cancelled first.
     *
     * @param durationSeconds the playing track's length, or 0 when unknown
     */
    public static Request search(String artist, String title, double durationSeconds, Callback callback) {
        final Request request = new Request();
        final String cleanArtist = clean(artist);
        final String cleanTitle = clean(title);
        Utilities.externalNetworkQueue.postRunnable(() -> attempt(request, cleanArtist, cleanTitle, durationSeconds, callback, 0));
        return request;
    }

    private static void attempt(Request request, String artist, String title, double durationSeconds, Callback callback, int tries) {
        if (request.cancelled) return;
        Outcome outcome;
        try {
            outcome = lookUp(request, artist, title, durationSeconds);
        } catch (Throwable e) {
            FileLog.e(e);
            outcome = Outcome.error(Error.SERVER);
        }
        if (request.cancelled) return;
        if (outcome.temporary && tries < LyricsTuning.ONLINE_RETRY_DELAYS_MS.length) {
            final long delay = Math.max(LyricsTuning.ONLINE_RETRY_DELAYS_MS[tries],
                    Math.min(outcome.retryAfterMs, LyricsTuning.ONLINE_RETRY_AFTER_MAX_MS));
            Utilities.externalNetworkQueue.postRunnable(() -> attempt(request, artist, title, durationSeconds, callback, tries + 1), delay);
            return;
        }
        final String lyrics = outcome.lyrics;
        final Error error = lyrics != null ? null : outcome.error != null ? outcome.error : Error.SERVER;
        AndroidUtilities.runOnUIThread(() -> {
            if (!request.cancelled) callback.onResult(lyrics, error);
        });
    }

    private static Outcome lookUp(Request request, String artist, String title, double durationSeconds) {
        if (title.isEmpty()) return Outcome.error(Error.NOT_FOUND);
        final String term = (artist.isEmpty() ? "" : artist + " ") + stripDecorations(title);
        final String url = ITUNES_SEARCH + "?term=" + Uri.encode(term) + "&entity=song&limit=" + LyricsTuning.ONLINE_SEARCH_RESULTS;
        final Response search = get(request, url);
        if (search.temporary) return Outcome.temporary(Error.NETWORK, search.retryAfterMs);
        if (search.status != 200 || search.body == null) {
            return search.status == 429 || search.status >= 500
                    ? Outcome.temporary(Error.RATE_LIMITED, search.retryAfterMs) : Outcome.error(Error.SERVER);
        }
        final ArrayList<Candidate> candidates;
        try {
            candidates = rank(readCandidates(search.body), artist, title, durationSeconds);
        } catch (Exception e) {
            return Outcome.error(Error.MALFORMED);
        }
        if (candidates.isEmpty()) return Outcome.error(Error.NOT_FOUND);
        // Close runners-up are the same song on another release (a single and its album), and
        // Apple Music may have lyrics on one of them only.
        final double best = candidates.get(0).score;
        int asked = 0;
        for (Candidate candidate : candidates) {
            if (asked >= LyricsTuning.ONLINE_CANDIDATES || candidate.score < best - LyricsTuning.ONLINE_CANDIDATE_SCORE_SPREAD) break;
            asked++;
            final Outcome outcome = fetchLyrics(request, candidate.trackId);
            if (outcome.lyrics != null || outcome.temporary || outcome.error != Error.NOT_FOUND) return outcome;
            if (request.cancelled) return Outcome.error(Error.SERVER);
        }
        return Outcome.error(Error.NOT_FOUND);
    }

    private static Outcome fetchLyrics(Request request, long trackId) {
        final Response response = get(request, PAXSENIX_LYRICS + "?id=" + trackId + "&ttml=true");
        if (response.temporary) return Outcome.temporary(Error.NETWORK, response.retryAfterMs);
        final String body = response.body == null ? "" : response.body.trim();
        String ttml = null;
        if (body.startsWith("{")) {
            try {
                final JSONObject json = new JSONObject(new JSONTokener(body));
                if (json.optBoolean("error", false)) {
                    final String message = json.optString("message", json.optString("error_message", "")).toLowerCase(Locale.ROOT);
                    if (message.contains("not found")) return Outcome.error(Error.NOT_FOUND);
                    // "Apple Music temporarily unavailable", and anything else the service
                    // reports as an error without saying the lyrics do not exist.
                    return Outcome.temporary(Error.RATE_LIMITED, response.retryAfterMs);
                }
                for (String key : new String[]{"content", "ttml", "lyrics", "data"}) {
                    final Object value = json.opt(key);
                    if (value instanceof String && ((String) value).trim().length() > 0) {
                        ttml = (String) value;
                        break;
                    }
                }
            } catch (Exception e) {
                return Outcome.error(Error.MALFORMED);
            }
        } else if (body.startsWith("<") || body.startsWith("﻿<")) {
            ttml = body;
        }
        if (ttml == null) {
            if (response.status == 404) return Outcome.error(Error.NOT_FOUND);
            if (response.status == 429 || response.status >= 500) return Outcome.temporary(Error.RATE_LIMITED, response.retryAfterMs);
            return Outcome.error(response.status == 200 ? Error.MALFORMED : Error.SERVER);
        }
        final String text = SyncedLyricsController.ttmlToText(ttml);
        if (text == null) return Outcome.error(Error.MALFORMED);
        return Outcome.lyrics(SyncedLyricsController.stripSingerLabels(text));
    }

    // --- HTTP --------------------------------------------------------------------------------

    private static final class Response {
        int status;
        String body;
        boolean temporary;
        long retryAfterMs;
    }

    private static Response get(Request request, String url) {
        final Response response = new Response();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            request.connection = connection;
            if (request.cancelled) {
                response.status = -1;
                return response;
            }
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(LyricsTuning.ONLINE_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(LyricsTuning.ONLINE_READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");
            response.status = connection.getResponseCode();
            response.retryAfterMs = parseRetryAfterMs(connection.getHeaderField("Retry-After"));
            final InputStream stream = response.status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            response.body = stream == null ? null : readBounded(stream);
            if (response.status == 429 || response.status >= 500) response.temporary = response.body == null || !response.body.trim().startsWith("{");
        } catch (IOException e) {
            // DNS, connect, TLS, a timeout or a dropped stream: all passing.
            response.temporary = true;
        } finally {
            request.connection = null;
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
        return response;
    }

    private static String readBounded(InputStream stream) throws IOException {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[16 * 1024];
            int total = 0, count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_BODY_BYTES) throw new IOException("Response too large");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static long parseRetryAfterMs(String header) {
        if (TextUtils.isEmpty(header)) return 0;
        try {
            return Math.max(0, Long.parseLong(header.trim())) * 1000L;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // --- Choosing the iTunes result ------------------------------------------------------------

    private static final class Candidate {
        long trackId;
        String title;
        String artist;
        long durationMs;
        double score;
    }

    private static ArrayList<Candidate> readCandidates(String body) throws Exception {
        final JSONObject root = new JSONObject(new JSONTokener(body));
        final JSONArray results = root.optJSONArray("results");
        final ArrayList<Candidate> list = new ArrayList<>();
        if (results == null) return list;
        for (int i = 0; i < results.length(); i++) {
            final JSONObject row = results.optJSONObject(i);
            if (row == null) continue;
            final long id = row.optLong("trackId", 0);
            final String name = row.optString("trackName", "");
            if (id <= 0 || name.isEmpty()) continue;
            if (!"song".equals(row.optString("kind", "song"))) continue;
            final Candidate candidate = new Candidate();
            candidate.trackId = id;
            candidate.title = name;
            candidate.artist = row.optString("artistName", "");
            candidate.durationMs = row.optLong("trackTimeMillis", 0);
            list.add(candidate);
        }
        return list;
    }

    /**
     * Keeps the results that are this song and orders them best first: the title and the
     * artist must match, a version the playing title does not name (remix, live, acoustic...)
     * counts against a result, and so does a different length.
     */
    private static ArrayList<Candidate> rank(ArrayList<Candidate> candidates, String artist, String title, double durationSeconds) {
        final String wantTitle = normalize(stripDecorations(title));
        final String wantTitleFull = normalize(title);
        final String wantArtist = normalize(artist);
        final long wantMs = durationSeconds > 0 ? Math.round(durationSeconds * 1000) : 0;
        final ArrayList<Candidate> kept = new ArrayList<>();
        for (Candidate candidate : candidates) {
            final double titleScore = Math.max(similarity(wantTitle, normalize(stripDecorations(candidate.title))),
                    similarity(wantTitleFull, normalize(candidate.title)));
            if (titleScore < LyricsTuning.ONLINE_MIN_TITLE_SIMILARITY) continue;
            double artistScore = 1;
            if (!wantArtist.isEmpty()) {
                final String have = normalize(candidate.artist);
                artistScore = have.contains(wantArtist) || wantArtist.contains(have) ? 1 : similarity(wantArtist, have);
                for (String part : ARTIST_SEPARATOR.split(have)) {
                    final String p = part.trim();
                    if (!p.isEmpty()) artistScore = Math.max(artistScore, similarity(wantArtist, p));
                }
                if (artistScore < LyricsTuning.ONLINE_MIN_ARTIST_SIMILARITY) continue;
            }
            double durationScore = 0.5;
            if (wantMs > 0 && candidate.durationMs > 0) {
                final long diff = Math.abs(wantMs - candidate.durationMs);
                if (diff > LyricsTuning.ONLINE_MAX_DURATION_DIFF_MS) continue;
                durationScore = diff <= LyricsTuning.ONLINE_DURATION_EXACT_MS ? 1
                        : 1 - (diff - LyricsTuning.ONLINE_DURATION_EXACT_MS)
                        / (double) (LyricsTuning.ONLINE_MAX_DURATION_DIFF_MS - LyricsTuning.ONLINE_DURATION_EXACT_MS);
            }
            double score = 0.55 * titleScore + 0.30 * artistScore + 0.15 * durationScore;
            final String haveFull = normalize(candidate.title);
            for (String word : VERSION_WORDS) {
                if (containsWord(haveFull, word) && !containsWord(wantTitleFull, word)) score -= LyricsTuning.ONLINE_VERSION_PENALTY;
            }
            candidate.score = score;
            kept.add(candidate);
        }
        kept.sort((a, b) -> Double.compare(b.score, a.score));
        return kept;
    }

    /** Words that name a different recording than the plain song. */
    private static final String[] VERSION_WORDS = {
            "remix", "mix", "live", "acoustic", "instrumental", "karaoke", "sped up", "slowed",
            "reverb", "acapella", "a cappella", "cover", "demo", "rehearsal", "session", "orchestral",
            "piano version", "extended", "nightcore", "8d"
    };

    private static final Pattern ARTIST_SEPARATOR = Pattern.compile("\\s*(?:,|&|\\band\\b|\\bx\\b|\\bfeat\\b|\\bft\\b|\\bwith\\b|/)\\s*");
    private static final Pattern DECORATION = Pattern.compile("\\s*(?:\\([^)]*\\)|\\[[^]]*]|\\s-\\s.*$)");
    private static final Pattern FEATURING = Pattern.compile("(?i)\\s*\\b(?:feat|ft)\\b\\.?.*$");

    /** The title without bracketed parts, " - Remastered..." tails and "feat." credits. */
    private static String stripDecorations(String title) {
        if (title == null) return "";
        String stripped = DECORATION.matcher(title).replaceAll("");
        stripped = FEATURING.matcher(stripped).replaceAll("").trim();
        return stripped.isEmpty() ? title.trim() : stripped;
    }

    private static boolean containsWord(String text, String word) {
        int from = 0;
        while (true) {
            final int at = text.indexOf(word, from);
            if (at < 0) return false;
            final int end = at + word.length();
            final boolean startOk = at == 0 || text.charAt(at - 1) == ' ';
            final boolean endOk = end == text.length() || text.charAt(end) == ' ';
            if (startOk && endOk) return true;
            from = at + 1;
        }
    }

    /** Lowercase, accents removed, apostrophes dropped, punctuation to single spaces. */
    private static String normalize(String value) {
        if (TextUtils.isEmpty(value)) return "";
        final String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        final StringBuilder builder = new StringBuilder(decomposed.length());
        boolean pendingSpace = false;
        for (int a = 0; a < decomposed.length(); a++) {
            final char c = decomposed.charAt(a);
            if (Character.getType(c) == Character.NON_SPACING_MARK) continue;
            if (c == '\'' || c == '’') continue;
            if (!Character.isLetterOrDigit(c)) {
                pendingSpace = builder.length() > 0;
                continue;
            }
            if (pendingSpace) {
                builder.append(' ');
                pendingSpace = false;
            }
            builder.append(c);
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    /** 1 minus the edit distance over the longer length. */
    private static double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return a.equals(b) ? 1 : 0;
        if (a.equals(b)) return 1;
        final int n = a.length(), m = b.length();
        int[] previous = new int[m + 1];
        int[] current = new int[m + 1];
        for (int j = 0; j <= m; j++) previous[j] = j;
        for (int i = 1; i <= n; i++) {
            current[0] = i;
            for (int j = 1; j <= m; j++) {
                final int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            final int[] swap = previous;
            previous = current;
            current = swap;
        }
        return 1 - previous[m] / (double) Math.max(n, m);
    }

    private static String clean(String value) {
        if (value == null) return "";
        final String trimmed = value.replaceAll("\\s+", " ").trim();
        return trimmed.length() > MAX_QUERY_LENGTH ? trimmed.substring(0, MAX_QUERY_LENGTH) : trimmed;
    }
}
