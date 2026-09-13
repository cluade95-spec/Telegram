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

/**
 * Read-only client for LRCLIB (<a href="https://lrclib.net">lrclib.net</a>), used as an input
 * source for the lyrics editor.
 *
 * <p>The contract implemented here is taken from the current LRCLIB server implementation
 * (<a href="https://github.com/tranxuanthang/lrclib">tranxuanthang/lrclib</a>):
 *
 * <ul>
 *   <li>{@code GET /api/get} requires both {@code track_name} and {@code artist_name} (each at
 *       least one character) and validates {@code duration} to the inclusive range 1..3600
 *       seconds. A miss is HTTP 404 with {@code "name": "TrackNotFound"}.</li>
 *   <li>{@code GET /api/search} takes {@code q}, {@code track_name}, {@code artist_name} and
 *       {@code album_name}, all optional; it does <em>not</em> accept {@code duration}. It answers
 *       200 with a JSON array capped at 20 rows and never 404s.</li>
 *   <li>Overload is reported as HTTP 503 with {@code Retry-After}. Public frontends may also
 *       answer 429, so both are treated the same way.</li>
 *   <li>The client identifies itself with the {@code Lrclib-Client} header.</li>
 * </ul>
 *
 * <p>Only the two read endpoints above are ever contacted: there is no code path here that can
 * reach {@code /api/publish}, {@code /api/flag} or {@code /api/request-challenge}. Nothing is
 * uploaded, no credential is sent, and the request carries no account, chat or message identity.
 *
 * <p>A search never persists anything. The caller receives raw lyrics text and is responsible for
 * deciding what to do with it.
 */
public final class LyricsOnlineSearch {

    /** Which lyric flavour the user explicitly asked for. There is deliberately no automatic mode. */
    public enum Type {
        /** Only {@code syncedLyrics} is acceptable. */
        SYNCED,
        /** Only {@code plainLyrics} is acceptable. */
        PLAIN
    }

    /** Distinguishable failure reasons, so the UI never has to collapse everything into "failed". */
    public enum Error {
        /** Nothing matched the artist/title at all. */
        NOT_FOUND,
        /** The track exists on LRCLIB but not in the flavour the user asked for. */
        TYPE_UNAVAILABLE,
        /** DNS, connect, TLS, timeout or a dropped stream. */
        NETWORK,
        /** 429/503, either after the single allowed retry or with no usable {@code Retry-After}. */
        RATE_LIMITED,
        /** 5xx, an unexpected status, or a redirect away from the fixed host. */
        SERVER,
        /** Unparseable body, or a request the server rejected as invalid (400). */
        MALFORMED
    }

    /** Exactly one of {@code lyrics} / {@code error} is non-null. Always called on the UI thread. */
    public interface Callback {
        void onResult(String lyrics, Error error);
    }

    private static final String HOST = "https://lrclib.net";
    private static final String GET_PATH = "/api/get";
    private static final String SEARCH_PATH = "/api/search";

    /**
     * Static, non-identifying client identity. LRCLIB reads {@code Lrclib-Client} in preference to
     * {@code X-User-Agent} and {@code User-Agent}. It must never carry anything that identifies
     * this installation or its user.
     */
    private static final String CLIENT_ID = "Telegram-Android-Lyrics (https://github.com/DrKLO/Telegram)";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    /** Hard ceiling on a response body; anything larger is a clean failure, never an allocation. */
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    /**
     * The ceiling the editor's Import path already enforces: 1 MiB of <em>UTF-8 bytes</em> read
     * from the source, not a UTF-16 character count. A character count would let a multi-byte
     * document through at up to three or four times that size, so the same byte limit is applied
     * here to whatever LRCLIB returns.
     */
    public static final int MAX_LYRICS_BYTES = 1024 * 1024;
    /** Query fields are user-editable; keep the URL bounded. */
    private static final int MAX_QUERY_FIELD_LENGTH = 200;
    /** LRCLIB's documented duration validation range, in seconds. */
    private static final double MIN_DURATION_SECONDS = 1.0;
    private static final double MAX_DURATION_SECONDS = 3600.0;
    /** No usable duration: absent, null, NaN or infinite. Never compares as close to anything. */
    private static final double INVALID_DURATION = -1;
    /** The server's own cap on /api/search output ({@code SEARCH_RESULT_LIMIT} in search_index.rs). */
    private static final int MAX_SEARCH_RESULTS = 20;
    /** A retry is only worth making when the server asks for a short wait. */
    private static final long MAX_RETRY_AFTER_MS = 5_000;

    private LyricsOnlineSearch() {
    }

    /** Handle for the caller to abandon an in-flight search. */
    public static final class Request {
        private volatile boolean cancelled;
        private volatile HttpURLConnection connection;

        /**
         * Abandons the search. After this returns, the callback is guaranteed never to run, and a
         * connection blocked in read is torn down rather than left to its timeout.
         */
        public void cancel() {
            cancelled = true;
            HttpURLConnection current = connection;
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

        private void attach(HttpURLConnection current) {
            connection = current;
        }

        private void detach() {
            connection = null;
        }
    }

    /**
     * Starts a search. Runs entirely off the UI thread on Telegram's queue for non-Telegram network
     * work, and delivers exactly one result back on the UI thread unless the returned handle has
     * been cancelled first.
     *
     * @param artist          user-editable artist field; may be empty
     * @param title           user-editable title field; may be empty
     * @param durationSeconds track duration in seconds, or any out-of-range value to omit it
     * @param type            the flavour the user explicitly chose
     */
    public static Request search(String artist, String title, double durationSeconds, Type type, Callback callback) {
        final Request request = new Request();
        final String cleanArtist = sanitize(artist);
        final String cleanTitle = sanitize(title);
        Utilities.externalNetworkQueue.postRunnable(() -> run(request, cleanArtist, cleanTitle, durationSeconds, type, callback, false));
        return request;
    }

    private static void run(Request request, String artist, String title, double durationSeconds, Type type, Callback callback, boolean isRetry) {
        if (request.isCancelled()) {
            return;
        }
        if (TextUtils.isEmpty(artist) && TextUtils.isEmpty(title)) {
            // Never send an entirely empty query.
            deliver(request, callback, null, Error.NOT_FOUND);
            return;
        }

        boolean sawAnyRow = false;

        // /api/get is the exact-match endpoint and requires both fields, so it is only attempted
        // when both are present; otherwise go straight to /api/search.
        if (!TextUtils.isEmpty(artist) && !TextUtils.isEmpty(title)) {
            StringBuilder url = new StringBuilder(HOST).append(GET_PATH)
                    .append("?track_name=").append(Uri.encode(title))
                    .append("&artist_name=").append(Uri.encode(artist));
            if (durationSeconds >= MIN_DURATION_SECONDS && durationSeconds <= MAX_DURATION_SECONDS) {
                url.append("&duration=").append(formatDuration(durationSeconds));
            }
            Response response = fetch(request, url.toString());
            if (request.isCancelled()) {
                return;
            }
            if (response.retryAfterMs > 0 && !isRetry) {
                scheduleRetry(request, artist, title, durationSeconds, type, callback, response.retryAfterMs);
                return;
            }
            if (response.error != null) {
                deliver(request, callback, null, response.error);
                return;
            }
            if (response.status == 200) {
                Track track = parseTrack(response.body);
                if (track == null) {
                    deliver(request, callback, null, Error.MALFORMED);
                    return;
                }
                sawAnyRow = true;
                String lyrics = usableLyrics(track, type);
                if (lyrics != null) {
                    deliver(request, callback, lyrics, null);
                    return;
                }
                // Exact hit, wrong flavour: fall through to /api/search rather than substituting.
            } else if (response.status != 404) {
                deliver(request, callback, null, statusError(response.status));
                return;
            }
            // 404 is an exact miss; fall through to /api/search.
        }

        StringBuilder url = new StringBuilder(HOST).append(SEARCH_PATH);
        char separator = '?';
        if (!TextUtils.isEmpty(title)) {
            url.append(separator).append("track_name=").append(Uri.encode(title));
            separator = '&';
        }
        if (!TextUtils.isEmpty(artist)) {
            url.append(separator).append("artist_name=").append(Uri.encode(artist));
        }
        // duration is deliberately not sent: the current server does not accept it on /api/search.
        Response response = fetch(request, url.toString());
        if (request.isCancelled()) {
            return;
        }
        if (response.retryAfterMs > 0 && !isRetry) {
            scheduleRetry(request, artist, title, durationSeconds, type, callback, response.retryAfterMs);
            return;
        }
        if (response.error != null) {
            deliver(request, callback, null, response.error);
            return;
        }
        if (response.status != 200) {
            deliver(request, callback, null, statusError(response.status));
            return;
        }

        ArrayList<Track> tracks = parseTracks(response.body);
        if (tracks == null) {
            deliver(request, callback, null, Error.MALFORMED);
            return;
        }
        if (!tracks.isEmpty()) {
            sawAnyRow = true;
        }
        String best = pickBest(tracks, artist, title, durationSeconds, type);
        if (best != null) {
            deliver(request, callback, best, null);
        } else {
            deliver(request, callback, null, sawAnyRow ? Error.TYPE_UNAVAILABLE : Error.NOT_FOUND);
        }
    }

    private static void scheduleRetry(Request request, String artist, String title, double durationSeconds, Type type, Callback callback, long delayMs) {
        if (request.isCancelled()) {
            return;
        }
        // One bounded retry, re-posted onto the same background queue. Nothing sleeps, and the
        // cancellation check above is repeated when the runnable actually fires.
        Utilities.externalNetworkQueue.postRunnable(() -> run(request, artist, title, durationSeconds, type, callback, true), delayMs);
    }

    private static Error statusError(int status) {
        if (status == 400) {
            // The server rejected the request itself; that is not "not found".
            return Error.MALFORMED;
        }
        if (status == 429 || status == 503) {
            return Error.RATE_LIMITED;
        }
        return Error.SERVER;
    }

    private static void deliver(Request request, Callback callback, String lyrics, Error error) {
        if (callback == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (request.isCancelled()) {
                return;
            }
            callback.onResult(lyrics, error);
        });
    }

    // region networking

    private static final class Response {
        int status;
        String body;
        Error error;
        /** Non-zero when the server asked for a short, bounded wait before one more attempt. */
        long retryAfterMs;
    }

    private static Response fetch(Request request, String url) {
        Response result = new Response();
        HttpURLConnection connection = null;
        InputStream stream = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            // The host is fixed and the scheme is https; never follow a redirect off it.
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setDoInput(true);
            connection.setUseCaches(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Lrclib-Client", CLIENT_ID);
            request.attach(connection);
            if (request.isCancelled()) {
                return result;
            }

            result.status = connection.getResponseCode();
            if (result.status == 429 || result.status == 503) {
                long retryAfterMs = parseRetryAfterMs(connection.getHeaderField("Retry-After"));
                if (retryAfterMs > 0) {
                    result.retryAfterMs = retryAfterMs;
                    return result;
                }
            }
            if (result.status == 200) {
                stream = connection.getInputStream();
                result.body = readBounded(stream);
                if (result.body == null) {
                    result.error = Error.MALFORMED;
                }
            } else {
                // Drain the error stream so the connection can be pooled, but nothing is parsed
                // from it: the status alone decides the outcome.
                stream = connection.getErrorStream();
                if (stream != null) {
                    readBounded(stream);
                }
            }
        } catch (IOException e) {
            // Includes SocketTimeoutException and the disconnect() that cancel() performs. No
            // exception text is logged: it can carry the query, and the query is user metadata.
            if (!request.isCancelled()) {
                result.error = Error.NETWORK;
            }
        } catch (Throwable e) {
            if (!request.isCancelled()) {
                result.error = Error.NETWORK;
            }
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Throwable ignored) {
                }
            }
            request.detach();
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
        return result;
    }

    /** Reads at most {@link #MAX_RESPONSE_BYTES}; returns null if the body is larger than that. */
    private static String readBounded(InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = stream.read(buffer)) != -1) {
            total += count;
            if (total > MAX_RESPONSE_BYTES) {
                return null;
            }
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Accepts only the delta-seconds form of {@code Retry-After}, and only a short one. An absent,
     * malformed or excessive value yields 0, which surfaces the rate-limited state instead of
     * retrying.
     */
    private static long parseRetryAfterMs(String header) {
        if (TextUtils.isEmpty(header)) {
            return 0;
        }
        try {
            long seconds = Long.parseLong(header.trim());
            if (seconds < 0) {
                return 0;
            }
            long ms = seconds * 1000L;
            return ms > MAX_RETRY_AFTER_MS ? 0 : Math.max(ms, 250L);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // endregion

    // region parsing

    /**
     * Signals that a 200 body does not match LRCLIB's documented shape: trailing content after the
     * JSON document, a row that is not an object, more rows than the server can return, a field
     * carrying the wrong JSON type, or lyrics that are not well-formed UTF-16.
     *
     * <p>The message names the offending field only. Nothing here ever carries lyrics or query text,
     * and nothing in this class is logged.
     */
    private static final class MalformedResponseException extends Exception {
        MalformedResponseException(String field) {
            super(field);
        }
    }

    private static final class Track {
        String trackName;
        String artistName;
        double duration = INVALID_DURATION;
        boolean instrumental;
        String plainLyrics;
        String syncedLyrics;
    }

    /**
     * Parses the body as exactly one JSON object.
     *
     * <p>{@code nextValue()} alone stops as soon as it has read one complete value, so
     * {@code {"plainLyrics":"x"} garbage} would otherwise be accepted. {@code nextClean()} skips
     * JSON whitespace and answers 0 only at end of input - in both Android's {@code org.json} and
     * the reference implementation - which proves the tokener consumed the whole body;
     * {@link #endsWithTerminator} then proves the body really ends with the document.
     */
    private static Track parseTrack(String body) {
        if (body == null) {
            return null;
        }
        try {
            JSONTokener tokener = new JSONTokener(body);
            Object parsed = tokener.nextValue();
            if (!(parsed instanceof JSONObject) || tokener.nextClean() != 0 || !endsWithTerminator(body, '}')) {
                return null;
            }
            return readTrack((JSONObject) parsed);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * Parses the body as exactly one JSON array of track objects, applying the server's own
     * contract: at most {@link #MAX_SEARCH_RESULTS} rows, every row an object, every field the type
     * the server declares. A row that breaks it fails the whole response rather than being skipped,
     * because silently dropping rows would turn a broken contract into an ordinary "not found".
     */
    private static ArrayList<Track> parseTracks(String body) {
        if (body == null) {
            return null;
        }
        try {
            JSONTokener tokener = new JSONTokener(body);
            Object parsed = tokener.nextValue();
            if (!(parsed instanceof JSONArray) || tokener.nextClean() != 0 || !endsWithTerminator(body, ']')) {
                return null;
            }
            JSONArray array = (JSONArray) parsed;
            final int length = array.length();
            // Checked before allocating, so a server-supplied length can never size the list.
            if (length > MAX_SEARCH_RESULTS) {
                return null;
            }
            ArrayList<Track> tracks = new ArrayList<>(length);
            for (int a = 0; a < length; a++) {
                Object row = array.get(a);
                if (!(row instanceof JSONObject)) {
                    // null, number, string, boolean or a nested array: not a TrackResponse.
                    return null;
                }
                tracks.add(readTrack((JSONObject) row));
            }
            return tracks;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * True when the last non-whitespace character of {@code body} is {@code terminator}.
     *
     * <p>{@code nextClean()} proves the tokener reached the end of the input, but Android's
     * implementation also skips block, line and hash comments on its way there, so on that platform
     * trailing comment text would pass as clean end-of-input. Requiring the body to actually end
     * with the document's own closing brace or bracket closes that without reaching into any
     * tokener internals, and can never reject a well-formed document: JSON objects and arrays
     * always end with their closer.
     */
    private static boolean endsWithTerminator(String body, char terminator) {
        for (int a = body.length() - 1; a >= 0; a--) {
            final char c = body.charAt(a);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                continue; // the only whitespace JSON allows between tokens
            }
            return c == terminator;
        }
        return false;
    }

    /**
     * Reads one row against the server's declared types. In {@code TrackResponse}, every string
     * field and {@code duration} are {@code Option}, so absent and null are both valid, while
     * {@code instrumental} is a plain {@code bool} and cannot be null. Unknown and newly added
     * fields (such as {@code lyricsfile}) are simply not read, so the parser stays
     * forward-compatible.
     */
    private static Track readTrack(JSONObject object) throws MalformedResponseException {
        Track track = new Track();
        track.trackName = readString(object, "trackName", false);
        if (track.trackName == null) {
            track.trackName = readString(object, "name", false);
        }
        track.artistName = readString(object, "artistName", false);
        track.duration = readDuration(object);
        track.instrumental = readInstrumental(object);
        track.plainLyrics = readString(object, "plainLyrics", true);
        track.syncedLyrics = readString(object, "syncedLyrics", true);
        return track;
    }

    /**
     * Accepts a JSON string, or absent/null. Any other JSON type is a malformed row: a number must
     * never be coerced into lyrics text.
     *
     * @param requireWellFormedText true for the two lyric fields, whose value becomes editor text
     *                              and is later serialized to UTF-8 by Save
     */
    private static String readString(JSONObject object, String name, boolean requireWellFormedText) throws MalformedResponseException {
        if (object == null || !object.has(name)) {
            return null;
        }
        Object value = object.opt(name);
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (!(value instanceof String)) {
            throw new MalformedResponseException(name);
        }
        String text = (String) value;
        if (requireWellFormedText && !isWellFormedUtf16(text)) {
            throw new MalformedResponseException(name);
        }
        return text;
    }

    /** {@code duration} is {@code Option<f64>}: absent and null are valid, anything non-numeric is not. */
    private static double readDuration(JSONObject object) throws MalformedResponseException {
        if (!object.has("duration")) {
            return INVALID_DURATION;
        }
        Object value = object.opt("duration");
        if (value == null || value == JSONObject.NULL) {
            return INVALID_DURATION;
        }
        if (!(value instanceof Number)) {
            throw new MalformedResponseException("duration");
        }
        final double duration = ((Number) value).doubleValue();
        // NaN and infinity are not a broken document, but they must never reach ranking: every
        // comparison against NaN is false, which would make the winner depend on iteration order.
        return Double.isNaN(duration) || Double.isInfinite(duration) ? INVALID_DURATION : duration;
    }

    /**
     * {@code instrumental} is a plain {@code bool} on the server, so it is either absent (older or
     * trimmed payloads) or a real JSON boolean. The string {@code "false"} is not a boolean and is
     * never quietly read as one.
     */
    private static boolean readInstrumental(JSONObject object) throws MalformedResponseException {
        if (!object.has("instrumental")) {
            return false;
        }
        Object value = object.opt("instrumental");
        if (!(value instanceof Boolean)) {
            throw new MalformedResponseException("instrumental");
        }
        return (Boolean) value;
    }

    /**
     * True when every surrogate in {@code text} is part of a complete pair. JSON can spell a lone
     * surrogate with a single six-character escape for a high or low surrogate code unit, and such
     * a string cannot round-trip: Java's UTF-8 encoder replaces it with '?', so what Save would
     * write to disk would differ from what the editor was shown. Legitimate supplementary
     * characters - emoji and the like - are whole pairs and pass untouched.
     */
    private static boolean isWellFormedUtf16(String text) {
        for (int a = 0; a < text.length(); a++) {
            final char c = text.charAt(a);
            if (Character.isHighSurrogate(c)) {
                if (a + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(a + 1))) {
                    return false;
                }
                a++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    // endregion

    // region selection

    /**
     * Returns the requested flavour, or null when this row cannot satisfy the request. There is no
     * substitution, no timestamp stripping and no timestamp synthesis: a row that lacks the
     * requested flavour is simply not a candidate.
     */
    private static String usableLyrics(Track track, Type type) {
        if (track == null || track.instrumental) {
            return null;
        }
        String lyrics = type == Type.SYNCED ? track.syncedLyrics : track.plainLyrics;
        if (lyrics == null || lyrics.trim().isEmpty()) {
            return null;
        }
        if (exceedsUtf8Bytes(lyrics, MAX_LYRICS_BYTES)) {
            return null;
        }
        return lyrics;
    }

    private static String pickBest(ArrayList<Track> tracks, String artist, String title, double durationSeconds, Type type) {
        if (tracks == null || tracks.isEmpty()) {
            return null;
        }
        final String wantedTitle = normalize(title);
        final String wantedArtist = normalize(artist);
        final boolean durationComparable = durationSeconds >= MIN_DURATION_SECONDS && durationSeconds <= MAX_DURATION_SECONDS;

        String bestLyrics = null;
        int bestTier = Integer.MAX_VALUE;
        double bestDelta = Double.MAX_VALUE;

        for (int a = 0; a < tracks.size(); a++) {
            Track track = tracks.get(a);
            String lyrics = usableLyrics(track, type);
            if (lyrics == null) {
                continue;
            }
            final String rowTitle = normalize(track.trackName);
            final String rowArtist = normalize(track.artistName);
            final boolean titleMatches = !wantedTitle.isEmpty() && wantedTitle.equals(rowTitle);
            final boolean artistMatches = !wantedArtist.isEmpty() && wantedArtist.equals(rowArtist);
            final int tier = titleMatches && artistMatches ? 0 : titleMatches ? 1 : 2;

            // Rows without a comparable duration sort after rows that have one, within the tier.
            final double delta = durationComparable && track.duration >= MIN_DURATION_SECONDS
                    ? Math.abs(track.duration - durationSeconds)
                    : Double.MAX_VALUE;

            // Strictly better only: ties keep the earlier row, which is LRCLIB's own ranking order.
            if (tier < bestTier || (tier == bestTier && delta < bestDelta)) {
                bestTier = tier;
                bestDelta = delta;
                bestLyrics = lyrics;
            }
        }
        return bestLyrics;
    }

    // endregion

    // region text

    /**
     * Trims, drops control characters and bounds the length. Everything else is preserved verbatim:
     * this text is sent to LRCLIB as-is, so accents, Arabic, Amharic, CJK and emoji must survive.
     */
    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(Math.min(value.length(), MAX_QUERY_FIELD_LENGTH));
        boolean pendingSpace = false;
        for (int a = 0; a < value.length(); ) {
            final int codePoint = value.codePointAt(a);
            a += Character.charCount(codePoint);
            // Control characters are dropped outright; every flavour of space (including
            // NBSP and the other Unicode space characters) collapses to one plain space.
            if (Character.isISOControl(codePoint) || Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                pendingSpace = builder.length() > 0;
                continue;
            }
            final int needed = Character.charCount(codePoint) + (pendingSpace ? 1 : 0);
            if (builder.length() + needed > MAX_QUERY_FIELD_LENGTH) {
                // Stop on a whole code point. Cutting between a surrogate pair would put a lone
                // surrogate into the query, which is not valid text in any encoding.
                break;
            }
            if (pendingSpace) {
                builder.append(' ');
                pendingSpace = false;
            }
            builder.appendCodePoint(codePoint);
        }
        return builder.toString();
    }

    /**
     * Closely approximates LRCLIB's {@code prepare_input} ({@code server/src/utils.rs}):
     * de-accent, map its punctuation set to spaces, drop apostrophes, lowercase, collapse
     * whitespace. Used only to decide whether a row is an "exact" match for ranking - never to
     * build a query.
     *
     * <p>It is an approximation, not an identity. The server de-accents with {@code secular}'s
     * {@code lower_lay_string}, whereas this uses NFD plus combining-mark removal; the two agree on
     * the Latin accents and the punctuation, apostrophe, case and whitespace rules that ranking
     * actually leans on, but they are not guaranteed to produce the same output for every code
     * point. Mark removal is also script-agnostic, so an Arabic hamza folds just as a Latin umlaut
     * does. Any divergence can only make ranking slightly more or less permissive; it can never
     * change the request, because what is sent to LRCLIB is the user's text untouched - see
     * {@link #sanitize(String)}.
     */
    private static String normalize(String value) {
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        StringBuilder builder = new StringBuilder(decomposed.length());
        boolean pendingSpace = false;
        for (int a = 0; a < decomposed.length(); a++) {
            char c = decomposed.charAt(a);
            if (Character.getType(c) == Character.NON_SPACING_MARK) {
                continue; // accent stripped by the NFD decomposition above
            }
            if (c == '\'' || c == '\u2019') {
                continue; // LRCLIB removes these outright rather than replacing them
            }
            if (Character.isWhitespace(c) || Character.isISOControl(c) || isPunctuationToSpace(c)) {
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

    /**
     * LRCLIB's punctuation set, minus the NUL and newline it also lists: those are already
     * folded to a space by the isISOControl/isWhitespace test in the caller.
     */
    private static boolean isPunctuationToSpace(char c) {
        switch (c) {
            case '`': case '~': case '!': case '@': case '#': case '$': case '%':
            case '^': case '&': case '*': case '(': case ')': case '_': case '|':
            case '+': case '-': case '=': case '?': case ';': case ':': case '"':
            case ',': case '.': case '<': case '>': case '{': case '}': case '[':
            case ']': case '\\': case '/':
                return true;
            default:
                return false;
        }
    }

    /**
     * True when the UTF-8 encoding of {@code text} would exceed {@code limit} bytes. Counts the
     * encoded width per code point and stops at the first byte over the limit, so it never
     * allocates a second multi-megabyte buffer just to measure one that is already in memory.
     *
     * <p>An unpaired surrogate is counted as three bytes even though the encoder substitutes a
     * single '?' for it, which errs towards rejecting rather than accepting an oversize document.
     */
    private static boolean exceedsUtf8Bytes(String text, int limit) {
        long total = 0;
        for (int a = 0; a < text.length(); ) {
            final int codePoint = text.codePointAt(a);
            a += Character.charCount(codePoint);
            if (codePoint < 0x80) {
                total += 1;
            } else if (codePoint < 0x800) {
                total += 2;
            } else if (codePoint < 0x10000) {
                total += 3;
            } else {
                total += 4;
            }
            if (total > limit) {
                return true;
            }
        }
        return false;
    }

    /** Whole seconds where possible, so the URL stays stable and locale-independent. */
    private static String formatDuration(double seconds) {
        long rounded = Math.round(seconds);
        if (Math.abs(seconds - rounded) < 0.001) {
            return Long.toString(rounded);
        }
        return String.format(Locale.US, "%.3f", seconds);
    }

    // endregion
}
