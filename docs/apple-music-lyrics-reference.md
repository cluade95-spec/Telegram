# Apple Music lyrics — reference measurements

Source: `Screen_Recording_20260919_172421_Apple_Music.mp4`, **720x1560 @ 60fps, 40.62 s**
(the brief quoted 1080x2340 figures; the supplied file is 720x1560, i.e. 1.5x smaller —
both are given below). Apple Music, "Blinding Lights", Android, word-timed lyrics.

Method: raw frames piped from ffmpeg; per-row/per-column local-contrast profiles
(61 px box-mean background subtraction); sub-pixel scroll tracking by cross-correlating
consecutive row profiles; baselines from the steepest negative gradient below each
text band. Every figure below is reproducible from the capture by that method.

## A. Typography

| quantity | measured @720 | @1080 equiv | as ratio | classification |
|---|---|---|---|---|
| left text inset | 61–63 px | 92–94 px | 0.0854 W | MEASURED |
| text em (from cap height 44 px / 0.71) | 62.0 px | 93 px | 0.0861 W | MEASURED |
| cap height | 44.0 px (45 on the active line) | 66 px | 0.710 em | MEASURED |
| x-height | 34.0 px | 51 px | 0.548 em | MEASURED |
| stem stroke width | 9.0 px | 13.5 px | 0.145 em | MEASURED |
| weight (stroke / cap = 0.20) | **Bold (700)** | | | MEASURED |
| wrapped-row baseline pitch | **78.0 px** (sd 0.3) | 117 px | 1.258 em | MEASURED |
| block-to-block baseline pitch | **141.5 px** | 212 px | 2.282 em | MEASURED |
| extra gap between blocks | 63.5 px | 95 px | 1.024 em | MEASURED |
| right inset | not determinable (no line reaches the wrap edge; ≤ 99 px) | | | NOT DETERMINABLE |

Cross-check: scroll advance per line == rows x 78 + 63.5. Predicted 141.5 / 219.5 / 297.5
for 1/2/3-row blocks; measured 141.6 / 217–224 / 296–312. Consistent.

> The brief's "blocks ~165–170 px @1080" does not reproduce: the measured block pitch is
> 212 px @1080, and it is confirmed independently by the scroll-advance arithmetic above.

## B. Active-line placement

The active block is **TOP-anchored, not centred**. The first baseline of the active line sits
at **y = 405.0 px** of the 1560 px frame in *every* settled frame measured
(405.00, 404.95, 405.01, 405.02, 405.04, 405.50) — regardless of whether the block is
1, 2 or 3 rows tall. Glyph top = 361 px.

* before transition: previous block's first baseline at 405.0
* during transition: travels by exactly one block advance
* after transition: new block's first baseline at 405.0

Normalised: baseline 405/1560 = **0.2596** of the frame; glyph top 361/1560 = **0.2314**.

## C. Line motion

Ten clean transitions (outliers excluded). Duration measured to 99 % of displacement.

| start (s) | displacement px | duration ms | v-peak at |
|---|---|---|---|
| 4.117 | 298.0 | 633 | 0.29 T |
| 9.500 | 294.3 | 617 | 0.30 T |
| 12.467 | 217.0 | 450 | 0.30 T |
| 15.467 | 215.6 | 617 | 0.30 T |
| 20.650 | 311.8 | 433 | 0.27 T |
| 22.117 | 140.2 | 433 | 0.27 T |
| 24.983 | 221.5 | 483 | 0.28 T |
| 27.700 | 222.9 | 417 | 0.28 T |
| 32.267 | 216.5 | 400 | 0.29 T |
| 38.150 | 211.2 | 583 | 0.31 T |

* **duration ≈ 500 ms** (median 483, mean 507, sd 90) — not correlated with distance
* **displacement = one block advance**, i.e. rows x 78 + 63.5 px — *not* a fixed 145 px
* **lead**: the scroll starts **450 ms** (median; mean 498, sd 93) *before* the incoming
  line's karaoke fill begins. Motion therefore completes at roughly the line's own timestamp.

### Normalised displacement p(t/T) — mean of the ten transitions (sd <= 0.025)

| t/T | 0.00 | 0.10 | 0.20 | 0.25 | 0.30 | 0.50 | 0.75 | 0.90 | 1.00 |
|---|---|---|---|---|---|---|---|---|---|
| p | 0.000 | 0.040 | 0.157 | 0.282 | 0.442 | 0.792 | 0.939 | 0.977 | 0.992 |

Best cubic-bezier fit: **cubic-bezier(0.45, 0.10, 0.05, 0.85)**, rmse 0.0037.

> The brief expected "strongly ease-out with a quick initial displacement". Only the second
> half of that holds. The curve has a real ease-*in*: the first 20 % of the time covers only
> 16 % of the distance. It is ease-in-out, with a short in and a very long out — which is why
> a symmetric `EASE_BOTH` reads wrong. Velocity peaks at 0.29 T.

## D. Emphasis transfer

Local contrast above background, frame 1150 (active = fully sung):

| line | contrast | ratio to sung |
|---|---|---|
| active row, **sung** | 141.7–151.1 | **1.000** |
| active row, **not yet sung** | 39.1 | **0.259** |
| active block, row 2 (not yet sung) | 39.5 | 0.261 |
| next block | 41.5 | 0.293 |
| block + 2 | 31.7 | 0.224 |
| block + 2, row 2 | 27.0 | 0.191 |
| block + 3 | 20.4 | 0.144 |
| block + 4 | 11.5 | 0.081 |

Sung text is pure white: RGB (254.5, 252.2, 245.5).

**Movement and opacity do NOT share a clock.** Through an entire transition the incoming
line's contrast is flat at 39 while the scroll runs 0 → 99 % of its travel; it only jumps to
~148 at +500…700 ms, i.e. when its own karaoke fill starts. Measured across six transitions.

The consequence: the unsung text of the *active* line (0.259) and the text of the *next*
line (0.293) are the same brightness. There is no line-level emphasis animation at all —
**the emphasis transfer IS the karaoke fill.** What remains is a static depth fade with
absolute screen position, from ~0.29 near the active line to ~0.081 at the bottom
(ratio 0.28).

### Blur

Not measurable. 20–80 % edge-rise width by depth: active 1.00 px, block+1 3.27 px,
block+2 0.80 px, block+3 1.67 px — noisy, no monotonic trend. Depth is carried by
**opacity alone**. The softness of distant lines that reads as blur is low alpha.

## E. Scaling

**The active line IS scaled, by 1.022.** Same line ("You can turn me on"), inactive at
frame 700 vs active-and-fully-sung at frame 1030, x-extent at five independent thresholds:

| threshold | 0.15 | 0.25 | 0.40 | 0.60 | 0.80 |
|---|---|---|---|---|---|
| width ratio | 1.0219 | 1.0219 | 1.0219 | 1.0219 | 1.0219 |

Threshold-invariance rules out antialiasing bloom (which would be threshold-dependent).
The left edge is pinned at x=61 and the right edge moves 609 → 621, so the transform is
anchored at the left text edge. Corroborated by two independent methods: same-line row
pitch 78.06 → 79.20 (1.015), and a least-squares column-profile fit (1.020).

Inactive lines all share one scale: their row pitch is 78.00 at *every* depth, so there is
no per-depth scaling — only the active line differs.

> This contradicts the brief ("no active-line scaling was observed"). The measurement is
> reported as found; it is **not implemented**, because the brief lists "do not dynamically
> resize active lyrics" as non-negotiable, and because a 2 % metric-affecting change on an
> active row would re-measure and re-wrap it — which is exactly what the protected #44
> shaping fix depends on not happening.

## Karaoke fill (reference behaviour, for comparison only — renderer is frozen)

Glyphs are geometrically identical between frames 975 and 995 while the fill advances:
no per-word lift, no per-letter lift, no vertical motion of any kind, no scaling during the
sweep. The fill front is a soft gradient roughly 10 px wide and crosses mid-glyph.
