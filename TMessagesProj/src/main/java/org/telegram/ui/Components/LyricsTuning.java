package org.telegram.ui.Components;

/**
 * Every tunable of the large player's synced-lyrics motion, in one place. Nothing that shapes how
 * the lyrics move, fade or scale is hardcoded anywhere else: change a value here, rebuild, look.
 *
 * <p>Sources: numbers marked AMLL come from Steve-xmh/applemusic-like-lyrics (packages/core,
 * commit 628c163): base/spring.ts, base/index.ts, base/group.ts, base/scroll.ts,
 * base/seek-detector.ts and styles/lyric-player.module.css. AMLL expresses lengths in CSS px,
 * which are taken as dp here.
 */
public final class LyricsTuning {
    private LyricsTuning() {}

    // --- Anchor ---------------------------------------------------------------------------
    /** Where the centre of the active line settles, as a fraction of the lyrics viewport height,
     *  measured from its top. AMLL: 0.35 (alignAnchor Center, alignPosition 0.35). */
    public static final float ANCHOR_FRACTION = 0.35f;

    // --- Scroll spring: normal playback (AMLL base/spring.ts) -------------------------------
    /** Spring mass for every line-position spring. AMLL posYSpringParams.mass. */
    public static final float SCROLL_MASS = 0.9f;
    /** Line interval (ms, previous line start to this line start) mapped onto the stiffness
     *  range below; intervals outside it are clamped. */
    public static final float SCROLL_INTERVAL_MIN_MS = 100f;
    public static final float SCROLL_INTERVAL_MAX_MS = 800f;
    /** Stiffness at the longest interval and at the shortest one. */
    public static final float SCROLL_STIFFNESS_MIN = 170f;
    public static final float SCROLL_STIFFNESS_MAX = 220f;
    /** ratio = (1 - normalisedInterval) ^ this; below 1 biases towards the faster end. */
    public static final float SCROLL_INTERVAL_EXPONENT = 0.2f;
    /** damping = sqrt(stiffness) * this. */
    public static final float SCROLL_DAMPING_MULTIPLIER = 2.2f;

    // --- Scroll spring: seek, gap, first line (AMLL "slow") -------------------------------
    public static final float SCROLL_SLOW_STIFFNESS = 90f;
    public static final float SCROLL_SLOW_DAMPING = 15f;

    // --- Scroll pre-roll ------------------------------------------------------------------
    /** The list starts moving to the next line at most this long before its timestamp. The
     *  brightness never moves early: it changes at the timestamp itself. */
    public static final long PRE_ROLL_MAX_MS = 250;
    /** ...and never more than this fraction of the gap between the two lines. */
    public static final float PRE_ROLL_GAP_FRACTION = 0.5f;

    // --- Stagger (AMLL base/index.ts calcLayout) ------------------------------------------
    /** Delay added per visible line, top of the viewport downwards. */
    public static final long STAGGER_STEP_MS = 50;
    /** From the active line down, each step is divided by this. */
    public static final float STAGGER_DECAY = 1.05f;

    // --- Line scale (AMLL base/group.ts) --------------------------------------------------
    public static final float SCALE_ACTIVE = 1.0f;
    public static final float SCALE_INACTIVE = 0.97f;
    /** Scale every line takes while playback is paused. */
    public static final float SCALE_PAUSED = 1.0f;
    public static final float SCALE_MASS = 2f;
    public static final float SCALE_STIFFNESS = 100f;
    public static final float SCALE_DAMPING = 25f;

    // --- Line opacity (AMLL lyric-player.module.css, base/index.ts resolveOpacity) --------
    // Exactly three stages. The only other values ever drawn are the FOCUS_IN / FOCUS_OUT fades
    // between them.
    /** Text alpha of every line that is not the active one, sung or not. */
    public static final float ALPHA_INACTIVE = 0.2f;
    /** Active line: text already sung (a line without word timing is all "sung"). */
    public static final float ALPHA_SUNG = 1.0f;
    /** Active word-timed line: text still to come. */
    public static final float ALPHA_UNSUNG = 0.4f;
    /** How long a line takes to brighten when it becomes active, and to dim when it stops. */
    public static final long FOCUS_IN_MS = 300;
    public static final long FOCUS_OUT_MS = 450;

    // --- Manual scroll and seek (AMLL base/scroll.ts, base/seek-detector.ts) --------------
    /** After the user's own scroll stops, following resumes this much later. */
    public static final long MANUAL_SCROLL_RESUME_MS = 5000;
    /** A position further than this from where the clock should be is a seek. */
    public static final long SEEK_JITTER_TOLERANCE_MS = 150;
    /** Wall-clock gap between two position reads that is still trusted for that prediction. */
    public static final long SEEK_MAX_TRUSTED_GAP_MS = 800;
    /** How long after a detected seek the next scroll still counts as a seek (slow spring, no
     *  stagger). */
    public static final long SEEK_WINDOW_MS = 1000;

    // --- Frame clock ----------------------------------------------------------------------
    /** Longest stretch the position is extrapolated past the player's last reported value. */
    public static final long CLOCK_MAX_EXTRAPOLATION_MS = 200;

    // --- Interim values Build 2 replaces ---------------------------------------------------
    /** Blur, in dp, of the line furthest from the anchor (pixel-distance depth, Build 1 only). */
    public static final float BLUR_MAX_DP = 2.4f;
    /** Width of the soft sung/unsung boundary of the word fill, in dp. */
    public static final float FILL_FEATHER_DP = 7f;
}
