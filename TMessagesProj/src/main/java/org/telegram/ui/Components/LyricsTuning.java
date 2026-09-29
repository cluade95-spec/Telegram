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

    // --- Scroll spring: song finished (AMLL "medium") ----------------------------------------
    public static final float SCROLL_END_STIFFNESS = 140f;
    public static final float SCROLL_END_DAMPING = 22f;
    /** For start-only word timing (no stated end), the song counts as finished this long after
     *  the last word starts. With stated ends it is the last word's own end. */
    public static final long END_OF_SONG_DERIVED_MS = 3000;
    /** A follow target that is not laid out is placed this fraction of the viewport height past
     *  the anchor, on the side it comes from, and springs in from there. */
    public static final float FAR_TARGET_ENTRY_FRACTION = 0.5f;

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
    /** Brightening when a line becomes active, and dimming when it stops, ride critically
     *  damped springs (mass 1) so an interrupted fade keeps its speed instead of restarting.
     *  These settle (98%) in about AMLL's 300 ms in and 450 ms out: omega = 5.83 / duration,
     *  stiffness = omega^2, damping = 2 * omega. */
    public static final float FOCUS_IN_STIFFNESS = 377f;
    public static final float FOCUS_IN_DAMPING = 38.9f;
    public static final float FOCUS_OUT_STIFFNESS = 168f;
    public static final float FOCUS_OUT_DAMPING = 26f;

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
    /** The drawn clock follows the player's position through a critically damped filter with
     *  this time constant (1 / omega). It removes the player's 10 ms position steps, makes a
     *  pause decelerate into its stop and a resume accelerate out of it, and still lands exactly
     *  on the player's position at rest. Larger is softer; the lag while playing is zero. */
    public static final float CLOCK_SMOOTHING_MS = 40f;
    /** A frame gap longer than this (the loop was idle while paused) is treated as this long. */
    public static final long CLOCK_MAX_FRAME_MS = 100;

    // --- Seek crossfade ---------------------------------------------------------------------
    /** After a jump of the clock (a seek, including while paused), word lift and emphasis blend
     *  from where they were to where the new position puts them over about this long, instead
     *  of popping. At rest they are exactly the pure function of the position again. */
    public static final long SEEK_BLEND_MS = 350;

    // --- Blur by line distance (AMLL LyricPlayerBase.resolveBlurLevel, lyric-player CSS) -----
    /** level = min(BLUR_LEVEL_MAX, (1 + distance) * BLUR_LEVEL_STEP); a line already passed
     *  counts one further than a line still to come. Off-screen lines take BLUR_LEVEL_MAX. */
    public static final float BLUR_LEVEL_STEP = 0.8f;
    public static final float BLUR_LEVEL_MAX = 5f;
    /** A level is a CSS blur() standard deviation, in dp. RenderEffect wants a radius; Skia turns
     *  one into the other with sigma = radius * SCALE + BIAS. */
    public static final float BLUR_SIGMA_SCALE = 0.57735f;
    public static final float BLUR_SIGMA_BIAS = 0.5f;
    /** Blur changes ride a critically damped spring (mass 1) that settles in about AMLL's
     *  0.4 s CSS transition, so an interrupted change keeps its speed. */
    public static final float BLUR_STIFFNESS = 212f;
    public static final float BLUR_DAMPING = 29.2f;
    /** Blur radius step, in px, for the shared RenderEffect cache. Small enough to read as
     *  continuous while a blur eases. */
    public static final float BLUR_RADIUS_STEP_PX = 0.1f;

    // --- Word fill edge (AMLL mask: generateFadeGradient, WebMaskAnimator) -------------------
    /** Width of the soft edge, as a fraction of the text's line height (ascent to descent). It
     *  runs from ALPHA_SUNG at the fill front to ALPHA_UNSUNG one edge-width ahead. */
    public static final float FILL_FADE_WIDTH = 0.5f;
    /** Extra travel, in edge widths: the first word starts with the edge this far before it, so
     *  it fades in; the last word runs this far past its end, so it finishes fully lit. */
    public static final float FILL_FIRST_WORD_PAD = 1.5f;
    public static final float FILL_LAST_WORD_PAD = 0.5f;

    // --- Per-word lift (AMLL dom/animation/float) ---------------------------------------------
    /** Rise of a word once it starts, in em, times LIFT_MULTIPLIER. The full rise is rounded to
     *  whole pixels so a word at rest is pixel-sharp; in between it moves sub-pixel. */
    public static final float LIFT_EM = 0.05f;
    public static final float LIFT_MULTIPLIER = 1.0f;
    /** The rise takes max(this, the word's duration), ease-out. */
    public static final long LIFT_MIN_DURATION_MS = 1000;
    /** When the line stops being active, words sink back over max(this, the time they spent
     *  rising), starting at the speed they were rising with, so the turn has no kink. */
    public static final long LIFT_FALL_MIN_MS = 500;

    // --- Background vocals (AMLL .lyricBgLine) ------------------------------------------------
    /** Text in parentheses is shown as a second, smaller line under the main one: this size
     *  relative to the main text (set once when the row is bound, never per frame)... */
    public static final float BACKGROUND_VOCALS_SCALE = 0.7f;
    /** ...and this opacity on top of the line's own brightness stage. */
    public static final float BACKGROUND_VOCALS_ALPHA = 0.4f;

    // --- Instrumental gap dots (AMLL base/interlude-dots.ts, base/timeline.ts) ----------------
    /** A gap between the end of one line's singing and the next line of at least this long gets
     *  the three dots. The end of singing is a timed blank line, or the last word's stated end. */
    public static final long INTERLUDE_MIN_GAP_MS = 7000;
    /** Height of the row that holds the dots; it is also the space the dots leave behind. */
    public static final int INTERLUDE_ROW_HEIGHT_DP = 44;
    /** Dot diameter and the gap between dots, in em of the lyric text. */
    public static final float INTERLUDE_DOT_EM = 0.3f;
    public static final float INTERLUDE_DOT_GAP_EM = 0.18f;
    /** After the singing stops the dots wait this long (not before the first line), fade in over
     *  ENTER_FADE_MS, and each dot fades in over DOT_ENTER_FADE_MS, DOT_STAGGER_MS apart. */
    public static final long INTERLUDE_ENTER_HOLD_MS = 500;
    public static final long INTERLUDE_ENTER_FADE_MS = 180;
    public static final long INTERLUDE_DOT_ENTER_FADE_MS = 750;
    public static final long INTERLUDE_DOT_STAGGER_MS = 80;
    /** The group breathes between 1 and BREATHE_MAX_SCALE, in whole cycles of about this long. */
    public static final long INTERLUDE_BREATHE_PERIOD_MS = 4000;
    public static final float INTERLUDE_BREATHE_MAX_SCALE = 1.25f;
    /** A gap whose body is shorter than this just holds the dots lit instead of breathing. */
    public static final long INTERLUDE_FALLBACK_HOLD_MS = 3000;
    /** Exit, ending exactly when the next line starts: swell to BREATHE_MAX_SCALE over PHASE1,
     *  shrink to EXIT_MIN_SCALE over PHASE2, fading over the last EXIT_FADE_MS. */
    public static final long INTERLUDE_EXIT_PHASE1_MS = 750;
    public static final long INTERLUDE_EXIT_PHASE2_MS = 250;
    public static final long INTERLUDE_EXIT_FADE_MS = 250;
    public static final float INTERLUDE_EXIT_MIN_SCALE = 0.4f;
    /** The third dot finishes lighting over this long, during the exit. */
    public static final long INTERLUDE_DOT3_TRAILING_MS = 750;
    /** Dot opacity unlit and lit. */
    public static final float INTERLUDE_DOT_UNLIT = 0.2f;
    public static final float INTERLUDE_DOT_LIT = 0.9f;
    /** Easing curves, cubic-bezier control points. */
    public static final float[] INTERLUDE_LIGHTING_EASE = {0.56f, 0.01f, 0.45f, 1f};
    public static final float[] INTERLUDE_ENTER_EASE = {0.59f, 0.02f, 0.07f, 1f};
    public static final float[] INTERLUDE_EXIT_PHASE1_EASE = {0.14f, 0.06f, 0.25f, 1f};
    public static final float[] INTERLUDE_EXIT_PHASE2_EASE = {0.29f, 0.03f, 1f, 0.38f};
    public static final float[] INTERLUDE_EXIT_FADE_EASE = {0.43f, 0.08f, 0.83f, 0.31f};

    // --- Long-word emphasis (AMLL dom/animation/emphasize, LyricLineBase.shouldEmphasize) ----
    public static final long EMPHASIS_MIN_DURATION_MS = 1000;
    /** Grapheme range for non-CJK words; CJK words qualify at any length. */
    public static final int EMPHASIS_MIN_GRAPHEMES = 2;
    public static final int EMPHASIS_MAX_GRAPHEMES = 7;
    /** AMLL calculateEmphasizeParams: amount = f(du / AMOUNT_REF_MS) * AMOUNT_SCALE and
     *  glow = f(du / GLOW_REF_MS) * GLOW_SCALE, where f(x) = sqrt(x) above 1 and x^3 below, and
     *  du = max(EMPHASIS_MIN_DURATION_MS, word duration); capped at AMOUNT_MAX / GLOW_MAX. */
    public static final float EMPHASIS_AMOUNT_REF_MS = 2000f;
    public static final float EMPHASIS_GLOW_REF_MS = 3000f;
    public static final float EMPHASIS_AMOUNT_SCALE = 0.6f;
    public static final float EMPHASIS_GLOW_SCALE = 0.5f;
    public static final float EMPHASIS_AMOUNT_MAX = 1.2f;
    public static final float EMPHASIS_GLOW_MAX = 0.8f;
    /** Grapheme swell: scale = 1 + ease * SWELL * amount, never above 1 + MAX_SWELL. */
    public static final float EMPHASIS_SWELL = 0.1f;
    public static final float EMPHASIS_MAX_SWELL = 0.10f;
    /** Graphemes push apart by ease * SPREAD * amount * (count / 2 - index) em, and rise by
     *  ease * RISE * amount em. */
    public static final float EMPHASIS_SPREAD_EM = 0.03f;
    public static final float EMPHASIS_RISE_EM = 0.025f;
    /** Glow: white shadow of radius min(GLOW_MAX_EM, blur * GLOW_MAX_EM) em, alpha ease * blur. */
    public static final float EMPHASIS_GLOW_MAX_EM = 0.3f;
    /** Each grapheme starts duration / STAGGER_DIVISOR / count after the previous one. */
    public static final float EMPHASIS_STAGGER_DIVISOR = 2.5f;
    /** Extra float per grapheme: sin-shaped, FLOAT_EM high, FLOAT_STRETCH times the duration,
     *  starting FLOAT_LEAD_MS early. */
    public static final float EMPHASIS_FLOAT_EM = 0.05f;
    public static final float EMPHASIS_FLOAT_STRETCH = 1.4f;
    public static final long EMPHASIS_FLOAT_LEAD_MS = 400;
    /** The line's last word gets more: amount, glow and duration multipliers. */
    public static final float EMPHASIS_LAST_WORD_AMOUNT = 1.6f;
    public static final float EMPHASIS_LAST_WORD_GLOW = 1.5f;
    public static final float EMPHASIS_LAST_WORD_DURATION = 1.2f;
}
