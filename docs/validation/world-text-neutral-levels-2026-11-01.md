# Neutral text lift, offline step 1b, 2026-11-01

**The revised bar fails. No GPU use or rendering implementation follows.** Neutral lift improves the previous hue behavior, and standing glowing groups now pass their revised limits, but original 1440p hanging dark and glowing holdouts still fail. A fixed tint fraction also leaves dark saturation failures. No client launch, GPU lock acquisition, Gradle build, shader change, debug switch, status change, push or merge occurred. The lead's overlay was left alone.

## Method and coefficient constraints

Extended `tools/WorldTextLevelsFit.java`. Replay entirely from committed input crops:

```powershell
& "$env:JAVA_HOME/bin/java.exe" -ea tools/WorldTextLevelsFit.java --self-check
& "$env:JAVA_HOME/bin/java.exe" -ea tools/WorldTextLevelsFit.java --neutral $pwd
```

Inputs, alpha masks, native targets and splits are unchanged from [step 1](world-text-levels-offline-2026-11-01.md). The actual text-free SR output supplies L. Decode RGB to linear light before averaging a square neighborhood. Radius is 8 at 1080p, scaled by **full image height**, giving radius 11 at 1440p. No native target pixels enter the local scene estimate. The 1080p standing signs train each leave-one-lighting-state-out fit; hanging signs are never training data. Fit all 1080p standing states for the independent original 1440p holdout.

Let Y be Rec.709 linear luminance of blurred L, T the linear unpremultiplied deferred text RGB, and f(Y) = k0 Y + k1 Y squared. Two model families:

- Additive: prediction = f(Y) C + w T.
- Separate white point: prediction = (1 minus T) f(Y) C + W T, per channel. White glyphs approach fitted white point W instead of adding scene lift on top of it. W is shared across RGB, not a separately fitted hue.

The fixed tint vector C is `1 + s (L / Y minus 1)`, or 1 at Y = 0. Thus s = 0 is exactly neutral; s = 1 carries L's hue. Tested s = 0, 0.1, 0.2, 0.35, 0.5, 0.75 and 1. No tint is retained in runtime code. There is no global s that passes all groups.

Fit shared coefficients to **linear target luminance** with equal total weight per training group. Coefficients are not independently adjusted per channel. The RGB prediction is then encoded to display space and scored with the same per-pixel display-luma MAE and mean-RGB HSV hue/saturation metrics as step 1.

The fit enforces the requested physical constraints, rather than relying on output clamping to hide bad coefficients. Parameterize f by endpoint derivatives A and B over `[0,M]`:

`f(Y) = A (Y minus Y squared / (2 M)) + B Y squared / (2 M)`

An enumerated active-set nonnegative least-squares solve constrains A, B and the text/white weight to be nonnegative. M = **0.3464359241**, the maximum local input Y across the scored captures, including holdout scene inputs but no holdout targets. Then f(0) = 0 and f'(Y) = A + (B minus A) Y / M is nonnegative throughout the input range. Every fit also checks 1001 points numerically before scoring. The coefficients can have negative k1 while remaining monotonic on this domain. No behavior beyond M is certified or shipped.

For neutral separate-white fits:

| Held-out split | k0 | k1 | W, linear | Derivative at M |
| --- | ---: | ---: | ---: | ---: |
| Day | 0.386002 | -0.557104 | 0.706562 | 0 |
| Shade | 0.384464 | -0.554885 | 0.710139 | 0 |
| Night | 0.383068 | -0.552870 | 0.705353 | 0 |
| Original 1440p | 0.384083 | -0.554335 | 0.707341 | 0 |

All fourteen models have nonnegative, monotonic lift and zero lift at black over the certified domain. Full coefficients are recorded. The self-check now also covers the constrained solver.

## Revised gate results

For each held-out group: dark MAE <= 8; glowing MAE <= that group's optimal-constant MAE plus 2; dark saturation difference <= 8 percentage points. The optimal constant is computed from that group's native luma median only to define the review threshold, never to fit the prediction.

There are ten dark and seven glowing held-out groups. Failures below can overlap.

| Family | Tint s | Mean MAE | Dark luma failures | Glow luma failures | Dark saturation failures |
| --- | ---: | ---: | ---: | ---: | ---: |
| Additive | 0 | 10.219 | 4 | 2 | 3 |
| Additive | 0.1 | 10.219 | 4 | 2 | 3 |
| Additive | 0.2 | 10.219 | 4 | 2 | 3 |
| Additive | 0.35 | 10.220 | 4 | 2 | 3 |
| Additive | 0.5 | 10.221 | 4 | 2 | 3 |
| Additive | 0.75 | 10.224 | 4 | 2 | 5 |
| Additive | 1 | 10.227 | 4 | 2 | 7 |
| Separate white | 0 | 8.729 | 1 | 2 | 3 |
| Separate white | 0.1 | 8.729 | 1 | 2 | 3 |
| Separate white | 0.2 | 8.729 | 1 | 2 | 3 |
| Separate white | 0.35 | 8.728 | 1 | 2 | 3 |
| Separate white | 0.5 | 8.727 | 1 | 2 | 2 |
| Separate white | 0.75 | 8.726 | 1 | 2 | 5 |
| Separate white | 1 | 8.724 | 1 | 2 | 8 |

Detailed luma results for the separate-white model, both neutral and the half-tint comparison:

| Held-out group | Revised luma limit | Neutral MAE | Half-tint MAE |
| --- | ---: | ---: | ---: |
| 1080 day standing dark | 8 | 5.560 | 5.559 |
| 1080 shade standing dark | 8 | 5.755 | 5.756 |
| 1080 night standing dark | 8 | 3.186 | 3.185 |
| 1080 day hanging dark | 8 | 7.402 | 7.395 |
| 1080 shade hanging dark | 8 | 4.393 | 4.392 |
| 1080 night hanging dark | 8 | 2.347 | 2.345 |
| 1080 day standing glow | 15.051 | 13.072 | 13.072 |
| 1080 shade standing glow | 15.906 | 13.907 | 13.907 |
| 1080 night standing glow | 15.760 | 13.817 | 13.817 |
| 1440 day standing dark | 8 | 5.452 | 5.449 |
| 1440 shade standing dark | 8 | 5.001 | 5.002 |
| 1440 day hanging dark | 8 | **10.253** | **10.244** |
| 1440 shade hanging dark | 8 | 4.751 | 4.749 |
| 1440 day standing glow | 12.927 | 11.368 | 11.369 |
| 1440 shade standing glow | 12.751 | 11.127 | 11.127 |
| 1440 day hanging glow | 14.263 | **15.465** | **15.465** |
| 1440 shade hanging glow | 14.428 | **15.532** | **15.533** |

Hue/saturation values below are degrees / percent saturation, computed from mean display RGB. Hue below 2 percent saturation is n/a because it is effectively neutral. Full numeric hue remains in CSV.

| Held-out group | Native H / S | Neutral H / S | Half-tint H / S |
| --- | --- | --- | --- |
| 1080 day standing dark | 27.9 / 5.0 | n/a / 0.2 | 37.2 / 6.8 |
| 1080 shade standing dark | 256.6 / 4.6 | n/a / 0.4 | n/a / 1.5 |
| 1080 night standing dark | 19.3 / 27.7 | n/a / **0.0** | 21.9 / 21.2 |
| 1080 day hanging dark | n/a / 0.5 | n/a / 0.0 | 41.0 / 7.1 |
| 1080 shade hanging dark | 221.9 / 12.4 | n/a / **0.0** | 217.9 / **2.1** |
| 1080 night hanging dark | n/a / 1.8 | n/a / 0.0 | 28.6 / 9.7 |
| 1080 day standing glow | n/a / 0.2 | n/a / 0.4 | n/a / 0.4 |
| 1080 shade standing glow | n/a / 0.1 | n/a / 0.4 | n/a / 0.4 |
| 1080 night standing glow | n/a / 0.5 | n/a / 0.0 | n/a / 0.0 |
| 1440 day standing dark | 23.3 / 3.7 | n/a / 0.2 | 38.0 / 7.2 |
| 1440 shade standing dark | 254.7 / 4.2 | n/a / 0.4 | n/a / 1.7 |
| 1440 day hanging dark | 224.4 / 3.0 | n/a / 0.0 | 41.4 / 7.9 |
| 1440 shade hanging dark | 221.3 / 14.0 | n/a / **0.0** | n/a / **1.5** |
| 1440 day standing glow | n/a / 0.1 | n/a / 0.4 | n/a / 0.4 |
| 1440 shade standing glow | n/a / 0.1 | n/a / 0.4 | n/a / 0.4 |
| 1440 day hanging glow | n/a / 0.2 | n/a / 0.4 | n/a / 0.4 |
| 1440 shade hanging glow | n/a / 0.2 | n/a / 0.4 | n/a / 0.4 |

Bold dark saturation entries exceed the 8-point tolerance. The half-tint night hanging gap is about 7.97 points and barely passes; it is not rounded into a failure. Higher tint fractions fail that group as well. The neutral version loses the warm night standing color, while tint helps that sign but does not recover the native cool saturation of shade hanging text.

## Previews, limits and disposition

Artifacts are under `docs/validation/world-text-shading-2026-11-01/offline-fit/neutral/`:

- `scores.csv`: all 238 scored groups, including RGB means, hue and saturation.
- `gates.csv`: every group's computed limit, error, saturation gap and pass/fail.
- `summary.csv`, `coefficients.txt`: reproducible summaries and unrounded constrained fits.
- `previews/neutral_white_0.0/GROUP.png` and `previews/neutral_white_0.5/GROUP.png`: neutral and half-tint predictions. Only the named group's measured glyph pixels are changed, as in the previous investigation. These are offline predictions, not after-render captures.

Inspected pairs include `shade_hanging_dark.png`, `night_standing_dark.png` and `original_day_hanging_dark.png` in both preview folders, against the corresponding native input crops one level above `neutral/`. The shade hanging neutral preview is visually close; the numerical saturation requirement still fails. Night standing color is visibly reduced with s = 0. No runtime patch was justified by this test.

The original alpha-mask coverage/filter limitations remain. The revised glowing threshold accounts for a group's constant-fit baseline, but that does not remove all resolution and geometry differences. These failures reject the tested models under the stated gate, not every possible screen-space reconstruction. No state-dependent tint rule, per-sign coefficients or new masking exception was introduced to make the result pass.

The dark cave live test, halo inspection of a complete composite, Laplacian comparison, shaderless switch identity test, frame timing and build verification for a renderer change remain unperformed because step 1b did not pass. Zero lift at Y = 0 and monotonic lift over the observed domain are verified algebraically and numerically offline; that is not a live cave result. Correction frame cost is **unmeasured**. Runtime source remains unchanged, and no shaderless or name-tag behavior was modified.
