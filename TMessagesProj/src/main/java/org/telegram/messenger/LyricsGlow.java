package org.telegram.messenger;

/**
 * When a long note's glow may be seen. The glow of an emphasised word follows its own envelope,
 * which for a line's last word runs on to the start of the next line (and its last graphemes, which
 * start later, past that). The list, though, moves on when the line's last sound ends, and from
 * then on the row is an old row, dimming and scrolling out: its glow must be gone with it.
 *
 * <p>These are pure functions of the playback position, so a seek, a pause and a resume all land
 * on the same picture playing would have drawn. The envelope itself is not touched.
 */
public final class LyricsGlow {

    private LyricsGlow() {
    }

    /**
     * When the glow of a line's words ends: when its last sound ends (the list moves on then), and
     * never after the line stops being active. A line with no known end is gated by its active end.
     */
    public static long glowEnd(long activeEndMs, long lineEndMs) {
        if (lineEndMs == LyricsOverlap.UNKNOWN) return activeEndMs;
        return Math.min(activeEndMs, lineEndMs);
    }

    /**
     * The glow of one grapheme at {@code clockMs}: the envelope's own value ({@code progress}, 0..1,
     * times the word's {@code strength}) while the line sings, nothing once it has ended.
     */
    public static float glow(float progress, float strength, long clockMs, long glowEndMs) {
        return clockMs < glowEndMs ? progress * strength : 0f;
    }

    /**
     * The glow shown while a seek crossfades from the state at the old position to the one at the
     * new. Landing on a row that has already ended shows none at all, not one that fades out over the
     * crossfade; every other case is the ordinary mix.
     */
    public static float blendedGlow(float fromGlow, float toGlow, float weight, long clockMs, long glowEndMs) {
        if (clockMs >= glowEndMs) return 0f;
        return fromGlow + (toGlow - fromGlow) * weight;
    }
}
