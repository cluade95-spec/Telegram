package org.telegram.messenger;

import org.telegram.ui.Components.LyricsTuning;

/**
 * The two ends of the small background-vocals line's fill. The line is drawn at the main line's own
 * sung and unsung brightness, each scaled by its own strength: the part not yet sung by
 * {@link LyricsTuning#BACKGROUND_VOCALS_ALPHA} (as it always was), the sung part by the stronger
 * {@link LyricsTuning#BACKGROUND_VOCALS_SUNG_ALPHA}, so it reads whiter as it is sung while staying
 * below the sung main line. The fill travels between exactly these two colours.
 */
public final class LyricsBackgroundVocals {

    private LyricsBackgroundVocals() {
    }

    /** {@code argb} with its alpha channel scaled by {@code factor} (clamped to 0..1). */
    public static int scaleAlpha(int argb, float factor) {
        final float f = Math.max(0f, Math.min(1f, factor));
        final int alpha = Math.round((argb >>> 24) * f);
        return (argb & 0x00FFFFFF) | (alpha << 24);
    }

    /** The small line's colour before it is sung, from the main line's colour for text still to come. */
    public static int unsung(int mainUnsungArgb) {
        return scaleAlpha(mainUnsungArgb, LyricsTuning.BACKGROUND_VOCALS_ALPHA);
    }

    /** The small line's colour once sung, from the main line's colour for text already sung. */
    public static int sung(int mainSungArgb) {
        return scaleAlpha(mainSungArgb, LyricsTuning.BACKGROUND_VOCALS_SUNG_ALPHA);
    }
}
