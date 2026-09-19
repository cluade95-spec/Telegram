/*
 * This is the source code of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */
package org.telegram.ui.Components;

import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.telegram.messenger.AndroidUtilities;
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
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Read-only client for the two lyric sources the editor can import from.
 *
 * <p>{@link Type#SYNCED} and {@link Type#PLAIN} come from LRCLIB, whose contract is documented
 * below. {@link Type#KARAOKE} comes from LyricsPlus, which is the only one of the two that states
 * word timing, and is documented at {@link #KARAOKE_HOSTS} and {@link #toEnhancedLrc(String)}. The
 * two are kept strictly apart: line timestamps can never answer a request for word timing, and
 * neither source is ever substituted for the other.
 *
 * <p>Everything here is read-only for both providers. No write endpoint is reachable, nothing is
 * uploaded, no credential is sent, and no request carries account, chat or message identity.
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
        /** LRCLIB {@code syncedLyrics}: line timestamps. Only {@code syncedLyrics} is acceptable. */
        SYNCED,
        /** LRCLIB {@code plainLyrics}: no timing at all. Only {@code plainLyrics} is acceptable. */
        PLAIN,
        /**
         * Genuine word-level timing, from a provider that actually states it. Never satisfied by
         * line timestamps from any source: a LRCLIB row can never answer this request, and a
         * word-level provider that has only line timing for the track is reported as unavailable
         * rather than downgraded.
         */
        KARAOKE
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

    /**
     * Exactly one of {@code lyrics} / {@code error} is non-null. Always called on the UI thread.
     *
     * <p>{@code resolved} is what the returned document actually is, which is not always what was
     * asked for: a {@link Type#KARAOKE} request that no word-timing provider could answer falls
     * back to line-synced lyrics and says so here. It is never the other way round - a request is
     * never satisfied by something with less timing than it asked for without this saying so, and
     * never by something claiming more.
     */
    public interface Callback {
        void onResult(String lyrics, Type resolved, Error error);
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

    /**
     * LyricsPlus mirrors, the read-only word-timing provider. All five run the same open-source
     * backend (<a href="https://github.com/ibratabian17/lyricsplus">ibratabian17/lyricsplus</a>)
     * and are the set that project's clients are told to iterate over; none of them is reliable on
     * its own, which is why there is a list rather than a host. Observed behaviour at the time of
     * writing: one answered, one was rate limited, one served a certificate for the wrong name,
     * and two answered 402. The order puts the one known to answer first, and every one of those
     * failures simply moves on to the next.
     *
     * <p>Each entry is an https origin with no path and no credentials, and a redirect away from it
     * is never followed.
     */
    private static final String[] KARAOKE_HOSTS = {
            "https://lyricsplus.binimum.org",
            "https://lyricsplus.atomix.one",
            "https://lyricsplus.prjktla.workers.dev",
            "https://lyricsplus-seven.vercel.app",
            "https://lyrics-plus-backend.vercel.app",
    };
    private static final String KARAOKE_PATH = "/v2/lyrics/get";
    /**
     * The provider's Apple Music path, which is where its genuine word timing comes from and the
     * one configuration confirmed to return it. {@code duration} and {@code album} are deliberately
     * not sent: they are optional, they are only used by the provider to narrow matching, and a
     * duration that disagrees with its catalogue by a second would turn a hit into a miss.
     */
    private static final String KARAOKE_SOURCE = "apple";
    /** Per-mirror, so one dead mirror cannot spend the whole budget. */
    private static final int KARAOKE_CONNECT_TIMEOUT_MS = 5_000;
    private static final int KARAOKE_READ_TIMEOUT_MS = 8_000;
    /**
     * Total wall-clock budget for the whole mirror walk. Mirrors are tried in order until one
     * answers with genuine word timing or the budget is gone; no mirror is ever tried twice, and
     * nothing here sleeps or retries. A mirror is only started if a connection could still finish
     * inside the budget, which bounds the whole search at roughly this plus one read timeout - and
     * the user can abandon it at any point from the spinner.
     */
    private static final long KARAOKE_BUDGET_MS = 12_000;
    /** Bounds on a word-timed document, checked before anything is allocated from it. */
    private static final int MAX_KARAOKE_LINES = 2000;
    private static final int MAX_KARAOKE_WORDS_PER_LINE = 300;
    /** The largest time the LRC timestamp grammar can express: 999:59.99. */
    private static final long MAX_KARAOKE_TIME_MS = (999L * 60 + 59) * 1000 + 999;

    /**
     * The AMLL TTML Database's public API, the second genuine word-timing source. The database
     * itself is <a href="https://github.com/amll-dev/amll-ttml-db">amll-dev/amll-ttml-db</a>, whose
     * README documents direct access by platform id
     * ({@code raw.githubusercontent.com/.../am-lyrics/[id].ttml}) - which is no use here, because a
     * Telegram audio document carries a title and a performer and no catalogue id at all. The
     * search API is therefore the only reachable entry point, and its two read routes are the only
     * ones contacted:
     *
     * <ul>
     *   <li>{@code GET /v1/lyrics/search?musicName=...} answers
     *       {@code {"status":200,"data":{"items":[...],"pagination":{...}}}}, each item carrying
     *       {@code id}, {@code filename}, {@code musicNames[]}, {@code artistNames[]},
     *       {@code albumNames[]}, {@code isrcs[]} and the per-platform id arrays.</li>
     *   <li>{@code GET /v1/lyrics/get?id=...} answers the same shape with the document itself in
     *       {@code data.lyrics} and its format in {@code data.format}.</li>
     * </ul>
     *
     * <p>Only {@code musicName} is sent. The route is known to accept it; narrowing server-side by
     * artist is not, and a parameter the deployment ignores would silently widen the result set
     * while making this client believe it had been narrowed. Artist, album and duration are
     * therefore judged here, against every row that comes back - see {@link #scoreAmll}.
     *
     * <p>Read-only throughout: no write route is reachable from this file, nothing is uploaded, no
     * credential is sent, and the request carries no account, chat or message identity.
     */
    private static final String AMLL_HOST = "https://api.amll.dev";
    private static final String AMLL_SEARCH_PATH = "/v1/lyrics/search";
    private static final String AMLL_GET_PATH = "/v1/lyrics/get";
    private static final int AMLL_CONNECT_TIMEOUT_MS = 6_000;
    private static final int AMLL_READ_TIMEOUT_MS = 10_000;
    /** Same shape of bound as the mirror walk: one wall-clock budget for search plus fetches. */
    private static final long AMLL_BUDGET_MS = 10_000;
    /**
     * The ceiling on both word-timing stages together, whatever each is allowed on its own. A
     * karaoke search that ends in the line-synced fallback pays for this before LRCLIB is even
     * asked, so the two providers in front of it are bounded jointly rather than one after another.
     */
    private static final long WORD_TIMING_BUDGET_MS = 16_000;
    /** Rows considered from one search page. Anything past this is noise, not a better match. */
    private static final int AMLL_MAX_CANDIDATES = 24;
    /** Documents actually downloaded. Ranking decides the order; this bounds the verification. */
    private static final int AMLL_MAX_FETCHES = 3;
    /** Floors a candidate must clear on its own before the weighted score is even considered. */
    private static final double AMLL_MIN_TITLE_SIMILARITY = 0.6;
    private static final double AMLL_MIN_ARTIST_SIMILARITY = 0.5;
    /** "If confidence is poor, reject": below this, the line-synced fallback is the better answer. */
    private static final double AMLL_MIN_CONFIDENCE = 0.72;
    /** How far a document's last stated time may run past the track before it is a different cut. */
    private static final long AMLL_DURATION_OVERRUN_MS = 10_000;

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
     * @param durationSeconds track duration in seconds, or any out-of-range value to omit it; used
     *                        only for LRCLIB ranking, and not sent at all for {@link Type#KARAOKE}
     * @param type            the flavour the user explicitly chose
     */
    public static Request search(String artist, String title, double durationSeconds, Type type, Callback callback) {
        final Request request = new Request();
        final String cleanArtist = sanitize(artist);
        final String cleanTitle = sanitize(title);
        if (type == Type.KARAOKE) {
            Utilities.externalNetworkQueue.postRunnable(() -> runKaraoke(request, cleanArtist, cleanTitle, durationSeconds, callback));
        } else {
            Utilities.externalNetworkQueue.postRunnable(() -> run(request, cleanArtist, cleanTitle, durationSeconds, type, callback, false, null));
        }
        return request;
    }

    /**
     * @param carried the most informative failure the word-timing stages saw before falling back
     *                here, or null for a search that was for this flavour all along. It only ever
     *                affects the message shown when this stage <em>also</em> fails.
     */
    private static void run(Request request, String artist, String title, double durationSeconds, Type type, Callback callback, boolean isRetry, Error carried) {
        if (request.isCancelled()) {
            return;
        }
        if (TextUtils.isEmpty(artist) && TextUtils.isEmpty(title)) {
            // Never send an entirely empty query.
            deliver(request, callback, null, type, worse(carried, Error.NOT_FOUND));
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
                scheduleRetry(request, artist, title, durationSeconds, type, callback, response.retryAfterMs, carried);
                return;
            }
            if (response.error != null) {
                deliver(request, callback, null, type, worse(carried, response.error));
                return;
            }
            if (response.status == 200) {
                Track track = parseTrack(response.body);
                if (track == null) {
                    deliver(request, callback, null, type, worse(carried, Error.MALFORMED));
                    return;
                }
                sawAnyRow = true;
                String lyrics = usableLyrics(track, type);
                if (lyrics != null) {
                    deliver(request, callback, lyrics, type, null);
                    return;
                }
                // Exact hit, wrong flavour: fall through to /api/search rather than substituting.
            } else if (response.status != 404) {
                deliver(request, callback, null, type, worse(carried, statusError(response.status)));
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
            scheduleRetry(request, artist, title, durationSeconds, type, callback, response.retryAfterMs, carried);
            return;
        }
        if (response.error != null) {
            deliver(request, callback, null, type, worse(carried, response.error));
            return;
        }
        if (response.status != 200) {
            deliver(request, callback, null, type, worse(carried, statusError(response.status)));
            return;
        }

        ArrayList<Track> tracks = parseTracks(response.body);
        if (tracks == null) {
            deliver(request, callback, null, type, worse(carried, Error.MALFORMED));
            return;
        }
        if (!tracks.isEmpty()) {
            sawAnyRow = true;
        }
        String best = pickBest(tracks, artist, title, durationSeconds, type);
        if (best != null) {
            deliver(request, callback, best, type, null);
        } else {
            deliver(request, callback, null, type, worse(carried, sawAnyRow ? Error.TYPE_UNAVAILABLE : Error.NOT_FOUND));
        }
    }

    private static void scheduleRetry(Request request, String artist, String title, double durationSeconds, Type type, Callback callback, long delayMs, Error carried) {
        if (request.isCancelled()) {
            return;
        }
        // One bounded retry, re-posted onto the same background queue. Nothing sleeps, and the
        // cancellation check above is repeated when the runnable actually fires.
        Utilities.externalNetworkQueue.postRunnable(() -> run(request, artist, title, durationSeconds, type, callback, true, carried), delayMs);
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

    private static void deliver(Request request, Callback callback, String lyrics, Type resolved, Error error) {
        if (callback == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (request.isCancelled()) {
                return;
            }
            callback.onResult(lyrics, resolved, error);
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

    /** Which read-only provider a request is going to, which decides its headers and timeouts. */
    private enum Provider {
        LRCLIB,
        LYRICS_PLUS,
        AMLL
    }

    private static Response fetch(Request request, String url) {
        return fetch(request, url, Provider.LRCLIB);
    }

    private static Response fetch(Request request, String url, Provider provider) {
        Response result = new Response();
        HttpURLConnection connection = null;
        InputStream stream = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            // The host of every request is one of a fixed set and the scheme is https; never
            // follow a redirect off it.
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setDoInput(true);
            connection.setUseCaches(false);
            connection.setConnectTimeout(connectTimeoutMs(provider));
            connection.setReadTimeout(readTimeoutMs(provider));
            connection.setRequestProperty("Accept", "application/json");
            if (provider == Provider.LRCLIB) {
                connection.setRequestProperty("Lrclib-Client", CLIENT_ID);
            } else {
                // The same static, non-identifying string, under the header this provider reads.
                connection.setRequestProperty("User-Agent", CLIENT_ID);
            }
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

    private static int connectTimeoutMs(Provider provider) {
        switch (provider) {
            case LRCLIB: return CONNECT_TIMEOUT_MS;
            case AMLL: return AMLL_CONNECT_TIMEOUT_MS;
            default: return KARAOKE_CONNECT_TIMEOUT_MS;
        }
    }

    private static int readTimeoutMs(Provider provider) {
        switch (provider) {
            case LRCLIB: return READ_TIMEOUT_MS;
            case AMLL: return AMLL_READ_TIMEOUT_MS;
            default: return KARAOKE_READ_TIMEOUT_MS;
        }
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
     * {@code {"plainLyrics":"x"} garbage} would otherwise be accepted.
     * {@link #hasOnlyTrailingJsonWhitespace} then requires that nothing but JSON whitespace
     * follows it.
     */
    private static Track parseTrack(String body) {
        if (body == null) {
            return null;
        }
        try {
            JSONTokener tokener = new JSONTokener(body);
            Object parsed = tokener.nextValue();
            if (!(parsed instanceof JSONObject) || !hasOnlyTrailingJsonWhitespace(tokener)) {
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
     *
     * <p>As in {@link #parseTrack}, nothing but JSON whitespace may follow the array.
     */
    private static ArrayList<Track> parseTracks(String body) {
        if (body == null) {
            return null;
        }
        try {
            JSONTokener tokener = new JSONTokener(body);
            Object parsed = tokener.nextValue();
            if (!(parsed instanceof JSONArray) || !hasOnlyTrailingJsonWhitespace(tokener)) {
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
     * True when everything left in {@code tokener} after the parsed value is JSON whitespace.
     *
     * <p>Deliberately reads the raw remainder with {@code more()} and {@code next()} rather than
     * asking {@code nextClean()} whether the tokener is at the end. Android's {@code nextClean()}
     * also skips block, line and hash comments, so on that platform it reports clean end-of-input
     * for a body whose document is followed by a hash comment ending in a closing brace - trailing
     * content that JSON does not allow, and which also defeats any check based on the body's last
     * non-whitespace character. The raw reader skips nothing, so a comment is seen for what it is:
     * a character that is not one of the four whitespace characters JSON permits between tokens.
     *
     * <p>{@code JSONException} is declared rather than caught because the reference
     * {@code org.json} declares it on these methods and Android's does not; catching it here would
     * not compile against Android. Both callers already funnel every throwable into a clean
     * malformed-response result.
     */
    private static boolean hasOnlyTrailingJsonWhitespace(JSONTokener tokener) throws JSONException {
        while (tokener.more()) {
            final char c = tokener.next();
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return false;
            }
        }
        return true;
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
        if (type != Type.SYNCED && type != Type.PLAIN) {
            // LRCLIB states line timestamps and nothing finer. Presenting one of its rows as an
            // answer to any other request would be exactly the downgrade this must never make, so
            // no row is a candidate - not even as a fallback when the real provider fails.
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

    // region karaoke

    /**
     * The karaoke acquisition chain, in descending order of how much timing the source states:
     *
     * <ol>
     *   <li>LyricsPlus' Apple Music path - genuine word timing, tried across its mirrors;</li>
     *   <li>the AMLL TTML Database - genuine word or syllable timing, and the only source here
     *       that can also state a genuine end per word;</li>
     *   <li>LRCLIB's {@code syncedLyrics} - <em>line</em> timing, and reported as such.</li>
     * </ol>
     *
     * <p>Nothing is ever synthesised on the way down. Step 3 is not karaoke and is never dressed up
     * as it: the result is delivered as {@link Type#SYNCED}, the caller is told, and no word motion
     * is generated from it anywhere downstream. A line is never divided among its words, and line
     * timestamps are never presented as word timestamps.
     *
     * <p>The chain stops at line timing on purpose. Where LRCLIB has the track but only as plain
     * text, that is reported rather than delivered: untimed words are not a weaker answer to a
     * karaoke request but a different kind of thing, and the message the user gets for it names
     * exactly that, which is also the one they would pick Normal for.
     *
     * <p>If every step fails, the most informative failure any of them saw is what the user is
     * shown - a definitive "this track has no word timing" outranks a mirror that simply did not
     * answer.
     */
    private static void runKaraoke(Request request, String artist, String title, double durationSeconds, Callback callback) {
        if (request.isCancelled()) {
            return;
        }
        if (TextUtils.isEmpty(artist) && TextUtils.isEmpty(title)) {
            deliver(request, callback, null, Type.KARAOKE, Error.NOT_FOUND);
            return;
        }
        final Error wordTimingError = runWordTiming(request, artist, title, durationSeconds, callback);
        if (wordTimingError == null) {
            return; // a genuine word-timed document was delivered
        }
        if (request.isCancelled()) {
            return;
        }
        // Every word-timing source is out. Line timing is still worth having, so the search
        // continues into LRCLIB - but as itself: run() delivers Type.SYNCED, which is what the
        // caller is told it got.
        run(request, artist, title, durationSeconds, Type.SYNCED, callback, false, wordTimingError);
    }

    /**
     * Steps 1 and 2 of the chain, under one wall-clock budget between them. Each stage takes what
     * is left of it, so two providers that are both unreachable cannot add their timeouts together
     * and leave the honest line-synced fallback waiting behind them.
     *
     * @return null once a genuine word-timed document has been delivered, otherwise the most
     *         informative failure either stage saw
     */
    private static Error runWordTiming(Request request, String artist, String title, double durationSeconds, Callback callback) {
        final long deadline = SystemClock.elapsedRealtime() + WORD_TIMING_BUDGET_MS;
        final Error mirrors = runLyricsPlus(request, artist, title, callback,
                Math.min(deadline, SystemClock.elapsedRealtime() + KARAOKE_BUDGET_MS));
        if (mirrors == null || request.isCancelled()) {
            return mirrors;
        }
        final Error amll = runAmll(request, artist, title, durationSeconds, callback,
                Math.min(deadline, SystemClock.elapsedRealtime() + AMLL_BUDGET_MS));
        if (amll == null) {
            return null;
        }
        return worse(mirrors, amll);
    }

    /**
     * Walks the LyricsPlus mirrors in order within one wall-clock budget. A mirror that cannot be
     * reached, answers a status other than 200, returns something that is not the documented shape,
     * or has only line timing for this track is recorded and skipped; the next one is tried. The
     * first mirror that returns real word timing wins, is delivered, and the walk stops there.
     *
     * @return null once a result has been delivered, otherwise the most informative failure seen
     */
    private static Error runLyricsPlus(Request request, String artist, String title, Callback callback, long deadline) {
        final String query = KARAOKE_PATH
                + "?title=" + Uri.encode(title)
                + "&artist=" + Uri.encode(artist)
                + "&source=" + KARAOKE_SOURCE;
        Error worst = null;
        for (int a = 0; a < KARAOKE_HOSTS.length; a++) {
            if (request.isCancelled()) {
                return Error.NETWORK;
            }
            if (SystemClock.elapsedRealtime() + KARAOKE_CONNECT_TIMEOUT_MS >= deadline) {
                // Not enough budget left for another connection to even succeed. Report what has
                // been seen so far rather than keep a user waiting on a provider that is not
                // answering, and never start an attempt whose timeout would run past the budget.
                break;
            }
            final Response response = fetch(request, KARAOKE_HOSTS[a] + query, Provider.LYRICS_PLUS);
            if (request.isCancelled()) {
                return Error.NETWORK;
            }
            if (response.error != null) {
                worst = worse(worst, response.error);
                continue;
            }
            if (response.status != 200) {
                // 404 is a definitive miss on this mirror, 402/5xx is a mirror that is not
                // serving, 429/503 is one that is throttling. None of them is retried here: the
                // next mirror is the retry, and Retry-After is deliberately not honoured.
                worst = worse(worst, response.status == 404 ? Error.NOT_FOUND : statusError(response.status));
                continue;
            }
            final String enhanced = toEnhancedLrc(response.body);
            if (enhanced == null) {
                // Either the body is not the documented shape, or it is line-only for this track.
                // Both are "this mirror cannot answer a karaoke request"; the walk continues.
                worst = worse(worst, Error.TYPE_UNAVAILABLE);
                continue;
            }
            deliver(request, callback, enhanced, Type.KARAOKE, null);
            return null;
        }
        return worst == null ? Error.NETWORK : worst;
    }

    /**
     * Keeps whichever of two failures tells the user more. A definitive answer from any mirror
     * outranks a mirror that simply did not work.
     */
    private static Error worse(Error current, Error candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || karaokeRank(candidate) < karaokeRank(current) ? candidate : current;
    }

    private static int karaokeRank(Error error) {
        switch (error) {
            case TYPE_UNAVAILABLE: return 0;
            case NOT_FOUND: return 1;
            case MALFORMED: return 2;
            case RATE_LIMITED: return 3;
            case SERVER: return 4;
            default: return 5;
        }
    }

    /**
     * Anything in a lyric line that our own parser would read as timing rather than as text. A word
     * tag would be stripped out of the visible text and shift every offset after it; a leading line
     * tag would be read as a second timestamp for the line. Neither can be escaped in LRC, so a
     * line whose text contains one is dropped instead of being written out corrupted. Ordinary text
     * that merely looks similar - "see &lt;3 you", "[chorus]" - matches neither.
     */
    private static final Pattern LOOKS_LIKE_WORD_TAG = Pattern.compile("<\\d{1,3}:\\d{1,2}(?:[\\.:]\\d{1,3})?>");
    private static final Pattern LOOKS_LIKE_LINE_TAG = Pattern.compile("^\\s*\\[\\d{1,3}:\\d{1,2}(?:[\\.:]\\d{1,3})?]");

    /** One line of a word-timed response, after validation. */
    private static final class KaraokeLine {
        long timeMs;
        /** Exactly the text the emitted line will carry. */
        String text;
        /** Stated word start times, or null when the source stated none for this line. */
        long[] wordTimes;
        /** Parallel to {@link #wordTimes}; concatenating these gives {@link #text}. */
        String[] wordTexts;
    }

    /**
     * Converts a LyricsPlus body into the Enhanced LRC this app already parses, or returns null.
     *
     * <p>Null means "this is not a karaoke answer", for every reason: not the documented shape, a
     * field of the wrong JSON type, a {@code type} that does not claim word granularity, no line
     * that actually carries more than one stated word, or a document that ends up too large. The
     * caller treats all of them the same way, because from the user's point of view they are the
     * same thing: this provider does not have word timing for this track.
     *
     * <p>The three conditions for a non-null result are deliberately independent, and all of them
     * are about what the source states rather than what would look good:
     * <ol>
     *   <li>{@code type} claims word or syllable granularity;</li>
     *   <li>at least one line carries two or more stated words <em>at different stated times</em>,
     *       so the response genuinely subdivides a line in time rather than restating one
     *       timestamp across it;</li>
     *   <li>the document, re-read by {@link SyncedLyricsController#parse}, really is line-synced
     *       and really does carry captured inline timing.</li>
     * </ol>
     *
     * <p>Public because it is the whole of the genuineness decision and is worth testing on its
     * own: it is a pure function of the response body, touches no network and keeps no state.
     */
    public static String toEnhancedLrc(String body) {
        if (body == null) {
            return null;
        }
        final ArrayList<KaraokeLine> lines;
        try {
            final JSONTokener tokener = new JSONTokener(body);
            final Object parsed = tokener.nextValue();
            if (!(parsed instanceof JSONObject) || !hasOnlyTrailingJsonWhitespace(tokener)) {
                return null;
            }
            final JSONObject root = (JSONObject) parsed;
            if (!claimsWordTiming(readString(root, "type", false))) {
                // The provider itself says this is line-level or plain. Believe it, and do not go
                // looking for word timing to salvage out of a response that does not claim any.
                return null;
            }
            lines = readKaraokeLines(root);
        } catch (Throwable e) {
            return null;
        }
        if (lines == null) {
            return null;
        }
        final String enhanced = writeEnhancedLrc(lines);
        if (enhanced == null || exceedsUtf8Bytes(enhanced, MAX_LYRICS_BYTES)) {
            return null;
        }
        // Last gate, and the only one that cannot be fooled by a hand-written check: read the
        // result back with the parser that will actually play it. Unless that parser agrees the
        // document is line-synced AND finds genuine inline timing in it, this is not karaoke and
        // the caller is told so.
        final SyncedLyricsController.Lyrics played = SyncedLyricsController.parse(enhanced);
        if (played.kind != SyncedLyricsController.Kind.SYNCED) {
            return null;
        }
        for (int a = 0; a < played.lines.size(); a++) {
            if (played.lines.get(a).segments != null) {
                return enhanced;
            }
        }
        return null;
    }

    /**
     * True when the provider's own {@code type} claims word or syllable granularity. The backend
     * normalises this field to upper case, older deployments answer in mixed case, and the
     * comparison is case-insensitive for both. Anything else - {@code LINE}, {@code PLAIN}, absent,
     * or a value this client has never heard of - is not a claim of word timing.
     */
    private static boolean claimsWordTiming(String type) {
        return "word".equalsIgnoreCase(type) || "syllable".equalsIgnoreCase(type);
    }

    /**
     * Reads the {@code lyrics} array against the documented shape: {@code time} and
     * {@code duration} in milliseconds, {@code text}, and an optional {@code syllabus} array of
     * {@code {time, duration, text}} words. {@code duration} is read only to be ignored - an end
     * time is never emitted, because Enhanced LRC cannot state one and inventing it is exactly
     * what this must not do.
     *
     * <p>Returns null when the array itself breaks the contract, and drops a single line when only
     * that line does. A line keeps its text and its own timestamp even when its word timing has to
     * be dropped, so a broken word list costs word timing and nothing else.
     */
    private static ArrayList<KaraokeLine> readKaraokeLines(JSONObject root) throws MalformedResponseException, JSONException {
        if (!root.has("lyrics")) {
            return null;
        }
        final Object raw = root.opt("lyrics");
        if (!(raw instanceof JSONArray)) {
            throw new MalformedResponseException("lyrics");
        }
        final JSONArray array = (JSONArray) raw;
        final int length = array.length();
        // Checked before allocating, so a server-supplied length can never size the list.
        if (length == 0 || length > MAX_KARAOKE_LINES) {
            return null;
        }
        final ArrayList<KaraokeLine> lines = new ArrayList<>(length);
        for (int a = 0; a < length; a++) {
            final Object row = array.get(a);
            if (!(row instanceof JSONObject)) {
                throw new MalformedResponseException("lyrics[]");
            }
            final KaraokeLine line = readKaraokeLine((JSONObject) row);
            if (line != null) {
                lines.add(line);
            }
        }
        return lines.isEmpty() ? null : lines;
    }

    private static KaraokeLine readKaraokeLine(JSONObject row) throws MalformedResponseException, JSONException {
        final long timeMs = readTimeMs(row);
        if (timeMs < 0) {
            return null; // no stated start: there is nowhere to put this line
        }
        final KaraokeLine line = new KaraokeLine();
        line.timeMs = timeMs;
        final String rowText = readString(row, "text", true);
        readWords(row, line);
        if (line.wordTimes == null) {
            line.text = rowText == null ? "" : rowText;
        } else {
            // The words are what the offsets will address, so their concatenation is the text -
            // not the row's own copy of it, which may differ in whitespace.
            final StringBuilder text = new StringBuilder();
            for (int a = 0; a < line.wordTexts.length; a++) {
                text.append(line.wordTexts[a]);
            }
            line.text = text.toString();
            // A word cannot start before the line it belongs to. Where the source disagrees with
            // itself, the line starts at the earlier of the two times it stated, which keeps every
            // word time exactly as given instead of discarding the line's timing.
            line.timeMs = Math.min(line.timeMs, line.wordTimes[0]);
        }
        if (!isRepresentableLyricLine(line.text)) {
            return null;
        }
        return line;
    }

    /** {@code time} is milliseconds. Absent, null, non-numeric, negative or beyond LRC: unusable. */
    private static long readTimeMs(JSONObject object) throws MalformedResponseException {
        if (!object.has("time")) {
            return -1;
        }
        final Object value = object.opt("time");
        if (value == null || value == JSONObject.NULL) {
            return -1;
        }
        if (!(value instanceof Number)) {
            throw new MalformedResponseException("time");
        }
        final double ms = ((Number) value).doubleValue();
        if (Double.isNaN(ms) || Double.isInfinite(ms) || ms < 0 || ms > MAX_KARAOKE_TIME_MS) {
            return -1;
        }
        return (long) ms;
    }

    /**
     * Reads one line's {@code syllabus}, leaving {@code wordTimes} null unless the source states
     * usable word timing for it.
     *
     * <p>A word with no text contributes no range and is skipped along with its time; a
     * whitespace-only word is kept, because it is what separates the words around it. Times must
     * not run backwards: a line whose stated words are out of order keeps its text and loses only
     * its word timing, exactly as the parser would decide for the same data.
     */
    private static void readWords(JSONObject row, KaraokeLine line) throws MalformedResponseException, JSONException {
        if (!row.has("syllabus")) {
            return;
        }
        final Object raw = row.opt("syllabus");
        if (raw == null || raw == JSONObject.NULL) {
            return;
        }
        if (!(raw instanceof JSONArray)) {
            throw new MalformedResponseException("syllabus");
        }
        final JSONArray array = (JSONArray) raw;
        final int length = array.length();
        if (length == 0 || length > MAX_KARAOKE_WORDS_PER_LINE) {
            return;
        }
        final long[] times = new long[length];
        final String[] texts = new String[length];
        int count = 0;
        for (int a = 0; a < length; a++) {
            final Object word = array.get(a);
            if (!(word instanceof JSONObject)) {
                throw new MalformedResponseException("syllabus[]");
            }
            final long timeMs = readTimeMs((JSONObject) word);
            final String text = readString((JSONObject) word, "text", true);
            if (timeMs < 0 || text == null) {
                return; // a word without a stated time or text is not word timing
            }
            if (text.isEmpty()) {
                continue;
            }
            if (count > 0 && timeMs < times[count - 1]) {
                return; // stated out of order; this line keeps its text and loses word timing
            }
            times[count] = timeMs;
            texts[count] = text;
            count++;
        }
        if (count == 0) {
            return;
        }
        line.wordTimes = count == length ? times : Arrays.copyOf(times, count);
        line.wordTexts = count == length ? texts : Arrays.copyOf(texts, count);
    }

    /** False when a line's text could not survive being written into an LRC document. */
    private static boolean isRepresentableLyricLine(String text) {
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return false; // one lyric line cannot hold a line break
        }
        return !LOOKS_LIKE_WORD_TAG.matcher(text).find() && !LOOKS_LIKE_LINE_TAG.matcher(text).find();
    }

    /**
     * Writes the lines out as Enhanced LRC, or returns null when no line ended up carrying more
     * than one stated word.
     *
     * <p>A line the source timed word by word is written with its words inline; a line it timed
     * only as a whole is written as an ordinary LRC line, which is genuine line timing and is
     * exactly what the player falls back to for it. No timestamp is interpolated, divided, rounded
     * up or invented, and no end time is written, because none was stated.
     */
    private static String writeEnhancedLrc(ArrayList<KaraokeLine> lines) {
        final StringBuilder out = new StringBuilder();
        int subdivided = 0;
        for (int a = 0; a < lines.size(); a++) {
            final KaraokeLine line = lines.get(a);
            final String stamp = formatLrcTime(line.timeMs);
            if (stamp == null) {
                continue;
            }
            final StringBuilder body = new StringBuilder();
            int words = 0;
            String firstWordStamp = null;
            String lastWordStamp = null;
            if (line.wordTimes != null) {
                for (int b = 0; b < line.wordTimes.length; b++) {
                    final String wordStamp = formatLrcTime(line.wordTimes[b]);
                    if (wordStamp == null) {
                        words = 0;
                        break;
                    }
                    body.append('<').append(wordStamp).append('>').append(line.wordTexts[b]);
                    if (words == 0) {
                        firstWordStamp = wordStamp;
                    }
                    lastWordStamp = wordStamp;
                    words++;
                }
            }
            if (out.length() > MAX_LYRICS_BYTES) {
                // Already past the ceiling the result will be measured against, and one UTF-16
                // char is at least one UTF-8 byte, so this can only be rejected. Stop building
                // rather than grow a buffer several times the size of the response.
                return null;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append('[').append(stamp).append(']');
            if (words > 0) {
                out.append(body);
                // Two or more words is not enough on its own: a converter with nothing but line
                // timing to work from can split a line's text into words and stamp every one of
                // them with the line's own time, which states no more than the line timestamp
                // already did and would light the whole line at once. A line only counts as
                // genuinely subdivided when the times it states for its words actually differ as
                // written - which also rules out words so close together that they collapse onto
                // one centisecond in the document.
                if (words > 1 && !firstWordStamp.equals(lastWordStamp)) {
                    subdivided++;
                }
            } else {
                out.append(line.text);
            }
        }
        // A document with no subdivided line at all is line timing wearing word timing's clothes.
        // It is not what the user asked for, so it is reported as unavailable rather than served.
        return subdivided > 0 ? out.toString() : null;
    }

    /**
     * Formats an absolute millisecond time as the {@code mm:ss.xx} the LRC grammar states, or null
     * when it cannot be expressed. Milliseconds are truncated to centiseconds rather than rounded,
     * so a word can never be written as starting later than the source said it does.
     */
    private static String formatLrcTime(long ms) {
        if (ms < 0 || ms > MAX_KARAOKE_TIME_MS) {
            return null;
        }
        final long centis = ms / 10;
        return String.format(Locale.US, "%02d:%02d.%02d", centis / 6000, (centis / 100) % 60, centis % 100);
    }

    // endregion

    // region amll

    /**
     * The AMLL TTML Database stage. Searches by music name, judges every row that comes back
     * against the track's own metadata, then downloads the best few in order and verifies each
     * document before it is accepted.
     *
     * <p>The verification is the point of this stage. A fuzzy title hit is never taken on trust:
     * a document only wins if its metadata clears the floors in {@link #scoreAmll}, carries no
     * variant marker the track itself does not carry, and - once fetched - actually parses as
     * word-timed lyrics whose stated times fit inside the track being played. Anything short of
     * that is rejected, and the chain drops to honest line timing instead. Beautifully animating
     * the wrong recording is a worse outcome than a line-synced one that is right.
     *
     * @return null once a result has been delivered, otherwise the most informative failure seen
     */
    private static Error runAmll(Request request, String artist, String title, double durationSeconds, Callback callback, long deadline) {
        if (TextUtils.isEmpty(title)) {
            // The one parameter this API is searched by. Without it there is no query to make.
            return Error.NOT_FOUND;
        }
        if (SystemClock.elapsedRealtime() + AMLL_CONNECT_TIMEOUT_MS >= deadline) {
            return Error.NETWORK;
        }
        final Response search = fetch(request, AMLL_HOST + AMLL_SEARCH_PATH + "?musicName=" + Uri.encode(title), Provider.AMLL);
        if (request.isCancelled()) {
            return Error.NETWORK;
        }
        if (search.error != null) {
            return search.error;
        }
        if (search.status != 200) {
            return search.status == 404 ? Error.NOT_FOUND : statusError(search.status);
        }
        final ArrayList<AmllCandidate> candidates = readAmllCandidates(search.body);
        if (candidates == null) {
            return Error.MALFORMED;
        }
        if (candidates.isEmpty()) {
            return Error.NOT_FOUND;
        }
        final ArrayList<AmllCandidate> ranked = rankAmll(candidates, artist, title);
        if (ranked.isEmpty()) {
            // Rows came back, but none of them is this recording. That is a definitive answer
            // about word timing for this track, not a transport failure.
            return Error.TYPE_UNAVAILABLE;
        }
        Error worst = Error.TYPE_UNAVAILABLE;
        for (int a = 0; a < ranked.size() && a < AMLL_MAX_FETCHES; a++) {
            if (request.isCancelled()) {
                return Error.NETWORK;
            }
            if (SystemClock.elapsedRealtime() + AMLL_CONNECT_TIMEOUT_MS >= deadline) {
                // No budget left for another document to arrive; stop rather than start a fetch
                // whose timeout would run past it.
                break;
            }
            final Response document = fetch(request, AMLL_HOST + AMLL_GET_PATH + "?id=" + Uri.encode(ranked.get(a).id), Provider.AMLL);
            if (request.isCancelled()) {
                return Error.NETWORK;
            }
            if (document.error != null) {
                worst = worse(worst, document.error);
                continue;
            }
            if (document.status != 200) {
                worst = worse(worst, document.status == 404 ? Error.NOT_FOUND : statusError(document.status));
                continue;
            }
            final String ttml = readAmllDocument(document.body);
            if (ttml == null) {
                worst = worse(worst, Error.MALFORMED);
                continue;
            }
            if (!isWordTimedTtml(ttml, durationSeconds)) {
                // Line-only TTML, or a document whose stated times do not fit this track. Either
                // way it is not an answer to a karaoke request; try the next candidate.
                worst = worse(worst, Error.TYPE_UNAVAILABLE);
                continue;
            }
            // Delivered as the source wrote it. TTML is the only format here that can state a
            // genuine end per word, and rewriting it into Enhanced LRC - which has no way to
            // express one - would throw that timing away for nothing.
            deliver(request, callback, ttml, Type.KARAOKE, null);
            return null;
        }
        return worst;
    }

    /**
     * The ids {@link #runAmll} would download for a search response, best first, and empty when
     * none of the rows is this recording.
     *
     * <p>Public for the same reason {@link #toEnhancedLrc(String)} is: this and the two methods
     * below are the whole of the accept/reject decision for this provider, they are pure functions
     * of a response body, they touch no network and keep no state, and a decision this consequential
     * - it is what stands between the player and animating the wrong recording - is worth being able
     * to test directly rather than through a socket.
     */
    public static ArrayList<String> rankAmllCandidates(String searchBody, String artist, String title) {
        final ArrayList<String> ids = new ArrayList<>();
        final ArrayList<AmllCandidate> rows = readAmllCandidates(searchBody);
        if (rows == null) {
            return null;
        }
        final ArrayList<AmllCandidate> ranked = rankAmll(rows, sanitize(artist), sanitize(title));
        for (int a = 0; a < ranked.size(); a++) {
            ids.add(ranked.get(a).id);
        }
        return ids;
    }

    /** One search row, reduced to the fields that can be judged against a Telegram audio file. */
    private static final class AmllCandidate {
        final String id;
        final String[] musicNames;
        final String[] artistNames;
        final String[] albumNames;
        double confidence;

        AmllCandidate(String id, String[] musicNames, String[] artistNames, String[] albumNames) {
            this.id = id;
            this.musicNames = musicNames;
            this.artistNames = artistNames;
            this.albumNames = albumNames;
        }
    }

    /**
     * Reads the search response. The documented envelope is
     * {@code {"status":200,"data":{"items":[...]}}}; a bare {@code items} array and a bare array
     * body are accepted too, so a deployment that answers a slightly flatter shape is read rather
     * than reported as broken. Anything else returns null, and a single unreadable row is skipped
     * rather than failing the page.
     */
    private static ArrayList<AmllCandidate> readAmllCandidates(String body) {
        if (body == null) {
            return null;
        }
        try {
            final JSONTokener tokener = new JSONTokener(body);
            final Object parsed = tokener.nextValue();
            if (!hasOnlyTrailingJsonWhitespace(tokener)) {
                return null;
            }
            JSONArray items = null;
            if (parsed instanceof JSONArray) {
                items = (JSONArray) parsed;
            } else if (parsed instanceof JSONObject) {
                final JSONObject root = (JSONObject) parsed;
                final Object data = root.opt("data");
                if (data instanceof JSONArray) {
                    items = (JSONArray) data;
                } else if (data instanceof JSONObject) {
                    items = ((JSONObject) data).optJSONArray("items");
                }
                if (items == null) {
                    items = root.optJSONArray("items");
                }
            }
            if (items == null) {
                return null;
            }
            final ArrayList<AmllCandidate> result = new ArrayList<>();
            for (int a = 0; a < items.length() && result.size() < AMLL_MAX_CANDIDATES; a++) {
                final JSONObject row = items.optJSONObject(a);
                if (row == null) {
                    continue;
                }
                final String id = readAmllId(row);
                if (id == null) {
                    continue;
                }
                result.add(new AmllCandidate(id,
                        readAmllStrings(row, "musicNames"),
                        readAmllStrings(row, "artistNames"),
                        readAmllStrings(row, "albumNames")));
            }
            return result;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * The row's identifier for {@code /lyrics/get}. It is a string in the documented shape but a
     * number is accepted as well, because the same API also answers {@code ?id=1}. Anything empty,
     * oversized or not plain text is refused: it goes straight into a URL.
     */
    private static String readAmllId(JSONObject row) {
        final Object raw = row.opt("id");
        final String id;
        if (raw instanceof String) {
            id = (String) raw;
        } else if (raw instanceof Integer || raw instanceof Long) {
            id = raw.toString();
        } else {
            return null;
        }
        if (id.isEmpty() || id.length() > MAX_QUERY_FIELD_LENGTH || !isWellFormedUtf16(id)) {
            return null;
        }
        for (int a = 0; a < id.length(); a++) {
            final char c = id.charAt(a);
            // Identifiers observed here are uuids and decimal ids. Refusing everything else keeps
            // a hostile row from steering the request, whatever Uri.encode would have done with it.
            final boolean allowed = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '-' || c == '_';
            if (!allowed) {
                return null;
            }
        }
        return id;
    }

    /** One of the row's parallel name arrays. A missing array is an empty one, never a failure. */
    private static String[] readAmllStrings(JSONObject row, String name) {
        final JSONArray array = row.optJSONArray(name);
        if (array == null) {
            final String single = row.optString(name, null);
            return TextUtils.isEmpty(single) ? EMPTY_STRINGS : new String[]{single};
        }
        final ArrayList<String> values = new ArrayList<>(Math.min(array.length(), MAX_ARTIST_CREDITS));
        for (int a = 0; a < array.length() && values.size() < MAX_ARTIST_CREDITS; a++) {
            final String value = array.optString(a, null);
            if (!TextUtils.isEmpty(value) && value.length() <= MAX_QUERY_FIELD_LENGTH && isWellFormedUtf16(value)) {
                values.add(value);
            }
        }
        return values.isEmpty() ? EMPTY_STRINGS : values.toArray(new String[0]);
    }

    private static final String[] EMPTY_STRINGS = new String[0];

    /**
     * Scores every candidate and returns the ones worth downloading, best first. A candidate that
     * fails any floor is not ranked lower - it is dropped, because a poor match is not a weaker
     * answer to the question but a wrong one.
     */
    private static ArrayList<AmllCandidate> rankAmll(ArrayList<AmllCandidate> candidates, String artist, String title) {
        final ArrayList<AmllCandidate> ranked = new ArrayList<>();
        for (int a = 0; a < candidates.size(); a++) {
            final AmllCandidate candidate = candidates.get(a);
            candidate.confidence = scoreAmll(candidate, artist, title);
            if (candidate.confidence >= AMLL_MIN_CONFIDENCE) {
                ranked.add(candidate);
            }
        }
        // Insertion sort by descending confidence: the list is at most AMLL_MAX_CANDIDATES long,
        // and a stable ordering keeps the server's own ranking as the tie-break.
        for (int a = 1; a < ranked.size(); a++) {
            final AmllCandidate moving = ranked.get(a);
            int b = a - 1;
            while (b >= 0 && ranked.get(b).confidence < moving.confidence) {
                ranked.set(b + 1, ranked.get(b));
                b--;
            }
            ranked.set(b + 1, moving);
        }
        return ranked;
    }

    /**
     * How much this row looks like the track that is playing, in 0..1, or 0 for "not this
     * recording at all".
     *
     * <p>What is available to judge with, and what is not, is worth being exact about. A Telegram
     * audio document states a title and a performer and nothing else: there is no album, no
     * duration in the search index, and no ISRC or platform id on our side, so the row's
     * {@code isrcs} and per-platform id arrays have nothing to be compared against and are not
     * used for matching. Duration <em>is</em> checked, but later and from the document itself -
     * see {@link #isWordTimedTtml}. Album is used in the one way it can be without a reference:
     * as another place a variant marker can show up.
     *
     * <p>The floors matter more than the weighting. A title that does not really match, or an
     * artist that does not match when one is known, drops the row outright; so does a variant
     * marker that is on one side and not the other, which is what keeps a live take, a remix, a
     * sped-up edit or a re-recording from being animated over the studio master.
     */
    private static double scoreAmll(AmllCandidate candidate, String artist, String title) {
        final String wantTitle = variantBase(title);
        if (wantTitle.isEmpty()) {
            return 0;
        }
        final double titleScore = bestSimilarity(wantTitle, candidate.musicNames);
        if (titleScore < AMLL_MIN_TITLE_SIMILARITY) {
            return 0;
        }
        if (variantMismatch(title, candidate)) {
            return 0;
        }
        final String wantArtist = variantBase(artist);
        double artistScore;
        if (wantArtist.isEmpty()) {
            // Nothing to check against. The row is not rewarded for it, and the title alone has
            // to carry the whole score past the threshold - which, at 0.6 of it, it cannot.
            artistScore = 0;
        } else {
            artistScore = artistScore(artist, candidate.artistNames, wantArtist);
            if (artistScore < AMLL_MIN_ARTIST_SIMILARITY) {
                return 0;
            }
        }
        return 0.6 * titleScore + 0.4 * artistScore;
    }

    /**
     * How well the row's performers match the track's, in 0..1, where 0 means "someone else".
     *
     * <p>Two readings of the track's performer field are tried, because a Telegram audio tag is
     * written by whoever made the file and both are common:
     *
     * <ul>
     *   <li>as a credit list - "A feat. B", "A &amp; B", "A, B" - split the same way on both sides
     *       and matched name against name. The track's <em>first</em> credit has to be found among
     *       the row's, which is what stops "Artist" from being satisfied by "Other Artist": half
     *       its words agreeing is not the same artist.</li>
     *   <li>as one opaque name, compared whole against the row's credits joined back together.
     *       This is what lets a tag that separates two performers with nothing but a space still
     *       match a row that lists them separately - but it has to be a near-identical match to
     *       count, because on this reading there is no primary credit to anchor it.</li>
     * </ul>
     */
    private static double artistScore(String artist, String[] artistNames, String wantArtist) {
        final String[] wanted = splitArtists(artist);
        double bySplit = 0;
        if (wanted.length > 0 && artistNames.length > 0) {
            final ArrayList<String> offered = new ArrayList<>();
            for (int a = 0; a < artistNames.length; a++) {
                final String[] split = splitArtists(artistNames[a]);
                for (int b = 0; b < split.length; b++) {
                    offered.add(split[b]);
                }
            }
            int matched = 0;
            boolean primary = false;
            for (int a = 0; a < wanted.length; a++) {
                for (int b = 0; b < offered.size(); b++) {
                    if (similarity(wanted[a], offered.get(b)) >= NAME_MATCH_SIMILARITY) {
                        matched++;
                        primary |= a == 0;
                        break;
                    }
                }
            }
            if (primary) {
                // Credited lists routinely disagree about the features either side names, so the
                // shorter list is what the agreement is measured against. The primary credit above
                // is the gate; this only rewards the rest agreeing too.
                bySplit = matched / (double) Math.min(wanted.length, offered.size());
            }
        }
        final double byJoin = similarity(wantArtist, variantBase(join(artistNames)));
        return Math.max(bySplit, byJoin >= NAME_MATCH_SIMILARITY ? byJoin : 0);
    }

    /** How alike two performer names must be to be the same performer. */
    private static final double NAME_MATCH_SIMILARITY = 0.8;

    /**
     * Separators a performer field uses to list several performers. Applied to the track's tag and
     * to the row's names alike, so a credit written "A &amp; B" on one side and listed as two
     * names on the other reduces to the same pair. Splitting is deliberately done before
     * {@link #normalize}, which folds "&amp;", "," and "/" into spaces and would erase them.
     */
    private static final Pattern ARTIST_SEPARATOR = Pattern.compile(
            "\\s*(?:[,;&/+\u00d7\uff06]|\\b(?:feat|ft|featuring|with|vs|versus|and)\\b)\\s*",
            Pattern.CASE_INSENSITIVE);
    /** A credit list longer than this is not a list any more; the joined reading covers it. */
    private static final int MAX_ARTIST_CREDITS = 8;

    private static String[] splitArtists(String value) {
        if (TextUtils.isEmpty(value)) {
            return EMPTY_STRINGS;
        }
        final String[] raw = ARTIST_SEPARATOR.split(value);
        final ArrayList<String> names = new ArrayList<>(Math.min(raw.length, MAX_ARTIST_CREDITS));
        for (int a = 0; a < raw.length && names.size() < MAX_ARTIST_CREDITS; a++) {
            final String name = variantBase(raw[a]);
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names.isEmpty() ? EMPTY_STRINGS : names.toArray(new String[0]);
    }

    /** The best similarity between one wanted value and any of the row's alternatives for it. */
    private static double bestSimilarity(String want, String[] values) {
        double best = 0;
        for (int a = 0; a < values.length; a++) {
            best = Math.max(best, similarity(want, variantBase(values[a])));
            if (best >= 1) {
                break;
            }
        }
        return best;
    }

    private static String join(String[] values) {
        if (values.length == 0) {
            return "";
        }
        if (values.length == 1) {
            return values[0];
        }
        final StringBuilder builder = new StringBuilder();
        for (int a = 0; a < values.length; a++) {
            if (a > 0) {
                builder.append(' ');
            }
            builder.append(values[a]);
        }
        return builder.toString();
    }

    /**
     * Token-set similarity in 0..1: how much of the larger token set the two share. Dividing by
     * the larger set - rather than by the union or the smaller set - is what makes "song" and
     * "song live at wembley" score poorly instead of perfectly, so extra words in either title
     * cost the row something even when no variant marker was recognised in them.
     */
    private static double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        if (a.equals(b)) {
            return 1;
        }
        final String[] left = a.split(" ");
        final String[] right = b.split(" ");
        int shared = 0;
        final boolean[] taken = new boolean[right.length];
        for (int i = 0; i < left.length; i++) {
            for (int j = 0; j < right.length; j++) {
                if (!taken[j] && left[i].equals(right[j])) {
                    taken[j] = true;
                    shared++;
                    break;
                }
            }
        }
        return shared / (double) Math.max(left.length, right.length);
    }

    /**
     * Words that mark a different recording rather than a different pressing of the same one. A
     * marker present on one side and absent on the other is a rejection: the studio master and the
     * live take share a title, share an artist, and do not share a single word boundary.
     */
    private static final String[] VARIANT_MARKERS = {
            "live", "remix", "remixed", "rmx", "acoustic", "instrumental", "karaoke", "cover",
            "demo", "reprise", "edit", "extended", "sped", "slowed", "reverb", "nightcore",
            "unplugged", "rerecorded", "session", "sessions", "mix", "dub", "version", "medley",
            "interlude", "intro", "outro", "clean", "explicit", "orchestral", "symphonic",
    };

    /**
     * Phrases that name a different master of the same performance, which is not a different
     * recording in any way that moves a word. They are removed from both sides before anything is
     * compared, so "Song - 2011 Remastered Version" and "Song" match, and - because the removal
     * takes the trailing "version" with the rest of the phrase - the marker test above is not
     * tripped by it either.
     */
    private static final Pattern VARIANT_NEUTRAL = Pattern.compile(
            "\\b(?:\\d{4} )?(?:digitally )?remaster(?:ed|s)?(?: version| mix)?\\b"
                    + "|\\b(?:mono|stereo)(?: version| mix)?\\b"
                    + "|\\b(?:bonus track|deluxe(?: edition)?|album version|single version|original (?:mix|version)|radio edit)\\b");

    /**
     * The comparable form of a title or artist: normalised the same way LRCLIB ranking normalises,
     * then with the neutral remaster/pressing phrases removed and the whitespace collapsed again.
     */
    private static String variantBase(String value) {
        final String normalized = normalize(value);
        if (normalized.isEmpty()) {
            return "";
        }
        final String stripped = VARIANT_NEUTRAL.matcher(normalized).replaceAll(" ");
        final StringBuilder builder = new StringBuilder(stripped.length());
        boolean pendingSpace = false;
        for (int a = 0; a < stripped.length(); a++) {
            final char c = stripped.charAt(a);
            if (c == ' ') {
                pendingSpace = builder.length() > 0;
                continue;
            }
            if (pendingSpace) {
                builder.append(' ');
                pendingSpace = false;
            }
            builder.append(c);
        }
        return dropLeadingArticle(builder.toString());
    }

    /**
     * Definite and indefinite articles that catalogues and file tags disagree about - "Beatles"
     * against "The Beatles" is the same band, and one leading article should not cost a row half
     * its similarity. Dropped from both sides, and only when something is left afterwards.
     */
    private static final String[] LEADING_ARTICLES = {"the", "a", "an", "el", "la", "los", "las", "le", "les", "der", "die", "das"};

    private static String dropLeadingArticle(String value) {
        final int space = value.indexOf(' ');
        if (space <= 0) {
            return value;
        }
        final String first = value.substring(0, space);
        for (int a = 0; a < LEADING_ARTICLES.length; a++) {
            if (first.equals(LEADING_ARTICLES[a])) {
                return value.substring(space + 1);
            }
        }
        return value;
    }

    /**
     * True when the row and the track disagree about what kind of recording this is. The track's
     * side is its title alone, which is all a Telegram audio file states; the row's side is every
     * name it carries, its albums included, because "Live at Wembley" is as often the album as it
     * is the title.
     */
    private static boolean variantMismatch(String title, AmllCandidate candidate) {
        final int wanted = variantMarkers(variantBase(title));
        if (wanted != rowVariantMarkers(candidate.musicNames, wanted)) {
            return true;
        }
        // The album only ever adds a marker; an album that names none says nothing either way.
        final int album = rowVariantMarkers(candidate.albumNames, wanted);
        return (album & ~wanted) != 0;
    }

    /**
     * The markers the row states, as a bit set. When several names are offered the one that agrees
     * with the track is the one believed: a row that lists both "Song" and "Song (Live)" is
     * ambiguous, and the disagreement is only real when no name it states agrees.
     */
    private static int rowVariantMarkers(String[] values, int wanted) {
        if (values.length == 0) {
            return 0;
        }
        int best = -1;
        for (int a = 0; a < values.length; a++) {
            final int markers = variantMarkers(variantBase(values[a]));
            if (markers == wanted) {
                return markers;
            }
            if (best == -1 || Integer.bitCount(markers ^ wanted) < Integer.bitCount(best ^ wanted)) {
                best = markers;
            }
        }
        return best;
    }

    /** Which of {@link #VARIANT_MARKERS} appear as whole tokens of an already-normalised value. */
    private static int variantMarkers(String normalized) {
        if (normalized.isEmpty()) {
            return 0;
        }
        int markers = 0;
        final String[] tokens = normalized.split(" ");
        for (int a = 0; a < tokens.length; a++) {
            for (int b = 0; b < VARIANT_MARKERS.length; b++) {
                if (tokens[a].equals(VARIANT_MARKERS[b])) {
                    markers |= 1 << b;
                    break;
                }
            }
        }
        return markers;
    }

    /**
     * Pulls the document out of a {@code /lyrics/get} response. The documented envelope carries it
     * in {@code data.lyrics} with {@code data.format} naming the format; a body that is already the
     * document is accepted as well. A format the client cannot read, or a document too large for
     * the editor's own limit, is refused here rather than half-imported.
     */
    public static String readAmllDocument(String body) {
        if (body == null) {
            return null;
        }
        final String trimmed = body.trim();
        if (trimmed.startsWith("<")) {
            return acceptableTtml(trimmed);
        }
        try {
            final JSONTokener tokener = new JSONTokener(body);
            final Object parsed = tokener.nextValue();
            if (!(parsed instanceof JSONObject) || !hasOnlyTrailingJsonWhitespace(tokener)) {
                return null;
            }
            final JSONObject root = (JSONObject) parsed;
            final JSONObject data = root.optJSONObject("data");
            final JSONObject holder = data != null ? data : root;
            final String format = holder.optString("format", null);
            if (!TextUtils.isEmpty(format) && !"ttml".equalsIgnoreCase(format)) {
                // The row was served in some other format. Nothing here can read it, and guessing
                // would be the one thing this must not do.
                return null;
            }
            String lyrics = holder.optString("lyrics", null);
            if (TextUtils.isEmpty(lyrics)) {
                lyrics = holder.optString("content", null);
            }
            return TextUtils.isEmpty(lyrics) ? null : acceptableTtml(lyrics.trim());
        } catch (Throwable e) {
            return null;
        }
    }

    private static String acceptableTtml(String document) {
        if (!isWellFormedUtf16(document) || exceedsUtf8Bytes(document, MAX_LYRICS_BYTES)) {
            return null;
        }
        return document;
    }

    /**
     * The last gate, and the only one that cannot be fooled by a hand-written check: read the
     * document back with the parser that will actually play it.
     *
     * <p>Three things have to hold, and all of them are about what the source states:
     * <ol>
     *   <li>the parser agrees the document is line-synced;</li>
     *   <li>at least one line carries captured word timing, so this really is word or syllable
     *       timed rather than a TTML document with one time per line;</li>
     *   <li>nothing it states runs past the end of the track being played. This is the one
     *       direction of the duration check that is unambiguous - lyrics may legitimately stop
     *       long before a track does, because outros exist, but a document whose words are still
     *       arriving after the audio has ended is timed against a different cut of the song.</li>
     * </ol>
     */
    public static boolean isWordTimedTtml(String ttml, double durationSeconds) {
        final SyncedLyricsController.Lyrics parsed;
        try {
            parsed = SyncedLyricsController.parse(ttml);
        } catch (Throwable e) {
            return false;
        }
        if (parsed.kind != SyncedLyricsController.Kind.SYNCED) {
            return false;
        }
        final boolean comparable = durationSeconds >= MIN_DURATION_SECONDS && durationSeconds <= MAX_DURATION_SECONDS;
        final long limit = comparable ? Math.round(durationSeconds * 1000) + AMLL_DURATION_OVERRUN_MS : Long.MAX_VALUE;
        boolean anyWordTiming = false;
        for (int a = 0; a < parsed.lines.size(); a++) {
            final SyncedLyricsController.Line line = parsed.lines.get(a);
            if (line.timeMs > limit) {
                return false;
            }
            final SyncedLyricsController.Segments segments = line.segments;
            if (segments == null) {
                continue;
            }
            anyWordTiming = true;
            final int last = segments.size() - 1;
            if (segments.startTimeMs(last) > limit) {
                return false;
            }
            if (segments.hasEndTime(last) && segments.endTimeMs(last) > limit) {
                return false;
            }
        }
        return anyWordTiming;
    }

    // endregion
}
