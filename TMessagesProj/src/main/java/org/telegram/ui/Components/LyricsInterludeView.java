package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/**
 * The three dots shown during an instrumental gap in synced lyrics, like Apple Music.
 *
 * <p>Ported from AMLL's InterludeDotsBase (packages/core/src/lyric-player/base/interlude-dots.ts):
 * the dots wait, fade in one after another, light up in turn while the group breathes, then swell
 * and shrink away so the exit ends exactly when the next line starts. Every number is in
 * {@link LyricsTuning}. Unlike AMLL, which advances its own clock per frame, the whole
 * performance here is a pure function of the playback position (the gap always anchors at its
 * own start), so a pause holds it exactly and a seek lands on the right frame; a jump of the
 * position blends from the old state to the new one over SEEK_BLEND_MS instead of popping.
 */
public class LyricsInterludeView extends View {
    private static final CubicBezierInterpolator LIGHTING = ease(LyricsTuning.INTERLUDE_LIGHTING_EASE);
    private static final CubicBezierInterpolator ENTER = ease(LyricsTuning.INTERLUDE_ENTER_EASE);
    private static final CubicBezierInterpolator EXIT_PHASE1 = ease(LyricsTuning.INTERLUDE_EXIT_PHASE1_EASE);
    private static final CubicBezierInterpolator EXIT_PHASE2 = ease(LyricsTuning.INTERLUDE_EXIT_PHASE2_EASE);
    private static final CubicBezierInterpolator EXIT_FADE = ease(LyricsTuning.INTERLUDE_EXIT_FADE_EASE);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int paddingHorizontal;
    private float emPx;
    private long startMs;
    private long endMs;
    private boolean intro;
    private boolean rtl;
    private int color = Color.WHITE;

    private long clockMs;
    private boolean clockSet;
    private long blendFromMs;
    private long blendStartNanos;
    private float blend = 1f;
    /** Drawn state: opacity, scale, and the three dots' opacities. */
    private final float[] state = new float[5];
    private final float[] from = new float[5];

    public LyricsInterludeView(Context context, int paddingHorizontal) {
        super(context);
        this.paddingHorizontal = paddingHorizontal;
    }

    /** The gap this row stands for: [start, end) in ms, whether it is the intro, and the side. */
    public void bind(long startMs, long endMs, boolean intro, boolean rtl, float emPx) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.intro = intro;
        this.rtl = rtl;
        this.emPx = emPx;
        clockSet = false;
        blendStartNanos = 0;
        blend = 1f;
        java.util.Arrays.fill(state, 0f);
        invalidate();
    }

    public void setColor(int color) {
        if (this.color == color) return;
        this.color = color;
        invalidate();
    }

    /**
     * Pushes the playback position. Returns whether a seek blend is still running, so the frame
     * loop keeps going while paused.
     */
    public boolean setClock(long positionMs, long nowNanos) {
        if (clockSet && Math.abs(positionMs - clockMs) > LyricsTuning.SEEK_JITTER_TOLERANCE_MS) {
            if (blend >= 0.5f) blendFromMs = clockMs;
            blendStartNanos = nowNanos;
        }
        clockSet = true;
        clockMs = positionMs;
        blend = seekBlend(nowNanos);
        final float o0 = state[0], s0 = state[1], d0 = state[2], d1 = state[3], d2 = state[4];
        snapshot(startMs, endMs, intro, positionMs, state);
        if (blend < 1f) {
            snapshot(startMs, endMs, intro, blendFromMs, from);
            for (int i = 0; i < state.length; i++) state[i] = from[i] + (state[i] - from[i]) * blend;
        }
        if (o0 != state[0] || s0 != state[1] || d0 != state[2] || d1 != state[3] || d2 != state[4]) invalidate();
        return blend < 1f;
    }

    private float seekBlend(long nowNanos) {
        if (blendStartNanos == 0) return 1f;
        final float t = (nowNanos - blendStartNanos) / 1e6f;
        if (t >= LyricsTuning.SEEK_BLEND_MS) {
            blendStartNanos = 0;
            return 1f;
        }
        if (t <= 0f) return 0f;
        final float omega = 5.83f / LyricsTuning.SEEK_BLEND_MS;
        return 1f - (1f + omega * t) * (float) Math.exp(-omega * t);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float opacity = state[0];
        if (opacity <= 0.001f || emPx <= 0f) return;
        final float dot = LyricsTuning.INTERLUDE_DOT_EM * emPx;
        final float gap = LyricsTuning.INTERLUDE_DOT_GAP_EM * emPx;
        final float total = dot * 3f + gap * 2f;
        final float x0 = rtl ? getWidth() - paddingHorizontal - total : paddingHorizontal;
        final float cy = getHeight() / 2f;
        canvas.save();
        canvas.scale(state[1], state[1], x0 + total / 2f, cy);
        final int baseAlpha = Color.alpha(color);
        for (int k = 0; k < 3; k++) {
            final float alpha = opacity * state[2 + k];
            if (alpha <= 0.001f) continue;
            paint.setColor(color);
            paint.setAlpha(Math.round(baseAlpha * Math.min(1f, alpha)));
            canvas.drawCircle(x0 + dot / 2f + k * (dot + gap), cy, dot / 2f, paint);
        }
        canvas.restore();
    }

    /**
     * AMLL InterludeDotsBase.setInterlude + resolveSnapshot, anchored at the gap's start. Writes
     * opacity, scale and the three dot opacities for position {@code p}; all zero when hidden.
     */
    static void snapshot(long start, long end, boolean intro, long p, float[] out) {
        out[0] = 0f;
        out[1] = 1f;
        out[2] = out[3] = out[4] = 0f;
        final long delayEnd = intro ? 0 : LyricsTuning.INTERLUDE_ENTER_HOLD_MS;
        final long exitTotal = LyricsTuning.INTERLUDE_EXIT_PHASE1_MS + LyricsTuning.INTERLUDE_EXIT_PHASE2_MS;
        final long body = Math.max(0, end - start) - delayEnd - exitTotal;
        final long dotEnterTotal = 2 * LyricsTuning.INTERLUDE_DOT_STAGGER_MS + LyricsTuning.INTERLUDE_DOT_ENTER_FADE_MS;
        if (body < dotEnterTotal) return;
        final long bodyEnd = delayEnd + body;
        final long totalEnd = bodyEnd + exitTotal;
        final long elapsed = p - start;
        if (elapsed < delayEnd || elapsed >= totalEnd) return;
        final float internal = elapsed - delayEnd;
        final boolean breathe = body >= LyricsTuning.INTERLUDE_FALLBACK_HOLD_MS;
        float period = 1f, segment = 1f, dot3Duration = 1f, dot3Target = 1f;
        if (breathe) {
            final long cycles = Math.max(1, body / LyricsTuning.INTERLUDE_BREATHE_PERIOD_MS);
            period = body / (float) cycles;
            segment = Math.round((body + LyricsTuning.INTERLUDE_DOT3_TRAILING_MS) / 3f);
            dot3Duration = body - segment * 2f;
            dot3Target = dot3Duration / segment;
        }
        final float max = LyricsTuning.INTERLUDE_BREATHE_MAX_SCALE;
        if (elapsed >= bodyEnd) {
            // Exit: swell, then shrink away, fading over the last EXIT_FADE_MS; the third dot
            // finishes lighting meanwhile.
            final float exit = elapsed - bodyEnd;
            final float fadeT = clamp01((exit - (exitTotal - LyricsTuning.INTERLUDE_EXIT_FADE_MS)) / (float) LyricsTuning.INTERLUDE_EXIT_FADE_MS);
            out[0] = enterOpacity(internal) * (1f - EXIT_FADE.getInterpolation(fadeT));
            if (exit < LyricsTuning.INTERLUDE_EXIT_PHASE1_MS) {
                out[1] = 1f + EXIT_PHASE1.getInterpolation(exit / LyricsTuning.INTERLUDE_EXIT_PHASE1_MS) * (max - 1f);
            } else {
                final float t = clamp01((exit - LyricsTuning.INTERLUDE_EXIT_PHASE1_MS) / (float) LyricsTuning.INTERLUDE_EXIT_PHASE2_MS);
                out[1] = max - EXIT_PHASE2.getInterpolation(t) * (max - LyricsTuning.INTERLUDE_EXIT_MIN_SCALE);
            }
            final float trailing = clamp01(exit / LyricsTuning.INTERLUDE_DOT3_TRAILING_MS);
            writeDots(out, internal, 1f, 1f, dot3Target + (1f - dot3Target) * trailing);
            return;
        }
        out[0] = enterOpacity(internal);
        if (!breathe) {
            writeDots(out, internal, 1f, 1f, 1f);
            return;
        }
        final float progress = breathing((internal % period) / period);
        out[1] = progress <= 0.5f ? 1f + progress / 0.5f * (max - 1f) : max - (progress - 0.5f) / 0.5f * (max - 1f);
        writeDots(out, internal,
                fraction(internal, 0f, segment, 1f),
                fraction(internal, segment, segment, 1f),
                fraction(internal, segment * 2f, dot3Duration, dot3Target));
    }

    private static void writeDots(float[] out, float internal, float f0, float f1, float f2) {
        out[2] = dotOpacity(f0) * dotEnterAlpha(0, internal);
        out[3] = dotOpacity(f1) * dotEnterAlpha(1, internal);
        out[4] = dotOpacity(f2) * dotEnterAlpha(2, internal);
    }

    /** AMLL breathingProgress. */
    private static float breathing(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        final double angle = 4.0 * Math.PI * t;
        final double s = Math.sin(angle), c = Math.cos(angle);
        return (float) (t - 0.084 * s + 0.008 * (1 - c) + 0.0046 * s * (c - s));
    }

    private static float dotOpacity(float fraction) {
        return LyricsTuning.INTERLUDE_DOT_UNLIT + (LyricsTuning.INTERLUDE_DOT_LIT - LyricsTuning.INTERLUDE_DOT_UNLIT) * clamp01(fraction);
    }

    private static float enterOpacity(float internal) {
        return ENTER.getInterpolation(clamp01(internal / LyricsTuning.INTERLUDE_ENTER_FADE_MS));
    }

    private static float dotEnterAlpha(int index, float internal) {
        final float t = clamp01((internal - index * LyricsTuning.INTERLUDE_DOT_STAGGER_MS) / LyricsTuning.INTERLUDE_DOT_ENTER_FADE_MS);
        return t * t;
    }

    private static float fraction(float internal, float startDelay, float duration, float target) {
        if (internal <= startDelay || duration <= 0f) return 0f;
        return LIGHTING.getInterpolation(clamp01((internal - startDelay) / duration)) * target;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }

    private static CubicBezierInterpolator ease(float[] p) {
        return new CubicBezierInterpolator(p[0], p[1], p[2], p[3]);
    }
}
