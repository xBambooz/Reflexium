# Offline local-scene text levels, 2026-11-01

**Step 1 fails. No rendering implementation, GPU acquisition, client launch or Gradle build was performed.** All twelve tested model/window combinations fail at least one held-out group at the required MAE of 8. No shader, DeferredWorldText, debug setting, status or name-tag path changed. The lead's overlay was not touched. No push or merge.

The initial report rejected neighborhood sampling without testing it. That was premature. Local scene light does predict much of the dark lift, but these fits do not meet the requested luma and hue bar.

## Inputs and method

Runnable code: `tools/WorldTextLevelsFit.java`, Java 25 and standard library only. Self-checks exercise the linear/display round trip, least-squares solver, box integral and hue calculation.

```powershell
& "$env:JAVA_HOME/bin/java.exe" -ea tools/WorldTextLevelsFit.java --self-check
& "$env:JAVA_HOME/bin/java.exe" -ea tools/WorldTextLevelsFit.java $pwd
```

Outputs and committed input crops: `docs/validation/world-text-shading-2026-11-01/offline-fit/`. The normal invocation needs only committed files, not the old worktree or GPU. `--prepare ROOT ORIGINAL_CURRENT_LIGHT_DIR` regenerates those crops from the original full captures and this worktree's `run/world-text-shading-raw/`.

The text-free scene is the actual `_output.png` captured just after SR evaluation, before deferred text composition. It visibly contains blank sign boards, not inpainted glyphs. Deferred text comes from `_world_text.png`, with coverage from `_world_text_alpha.png`. Native targets and the previous rendering come from the existing full screenshots. Crops preserve exact pixels:

- 1080p day, shade, night: origin `(820,360)`, size `280x210`.
- Original 1440p day and shade: origin `(1090,480)`, size `380x300`.
- Text masks and ROIs are unchanged from the prior alpha-luma measurements. Original hanging ROIs are `(1330,510,120,60)` dark and `(1120,510,120,60)` glowing. These add 935 glowing hanging pixels per original state.
- 1080p has standing dark, standing glow and hanging dark. There is no glowing hanging sign in that set. The original 1440p set supplies hanging glow holdouts for day and shade; no night hanging-glow capture exists.

Each display RGB channel is decoded using the standard sRGB transfer. The text layer is unpremultiplied by coverage before decoding. L is a square box average in linear RGB over the text-free scene, centered on each sample. Radii are 2, 4 and 8 at 1080p, scaled to 3, 5 and 11 at 1440p. This tests small local blur, without sampling glyphs or using native target pixels in L. All scored neighborhoods fit inside the retained crops.

For each channel independently, fit linear least squares with equal weight per training group:

| Name | Linear prediction |
| --- | --- |
| levels | k L + w text |
| constant | k L + w text + b |
| varying | k0 L + k1 L squared + w text |
| varying_constant | k0 L + k1 L squared + w text + b |

The varying model means k(L) = k0 + k1 L. Coefficients are unconstrained; this gives the estimator more freedom than a nonnegative production correction. Predictions clamp to linear `[0,1]` and encode back to display RGB before scoring.

1080p uses leave-one-lighting-state-out fits. For each held-out day/shade/night state, train on standing dark and glowing text in the other two states. Hanging text is never used for fitting. Original 1440p captures are a separate resolution holdout, fitted using all three 1080p standing states and no original or hanging samples. There is no random pixel split that leaks the same lighting state into training and test.

MAE is mean absolute per-pixel display-luma error on the 0 to 255 scale, with Rec.709 RGB weights. This is not the absolute difference of the two mean lumas. Hue is HSV hue of mean display RGB, computed without integer rounding; saturation is included because hue is unstable near gray. `scores.csv` contains all 204 rows, unrounded coefficients are in `coefficients.txt`.

## Held-out results

Equal-weight average over the seventeen held-out groups; each cell is an independent state/geometry/brightness group. Radius was a fixed grid, not optimized per sign. No combination passes, even before considering hue.

| Model | Radius | Mean MAE | Worst MAE | Groups above 8 |
| --- | ---: | ---: | ---: | ---: |
| levels | 2 | 14.23 | 25.15 | 15 |
| levels | 4 | 13.98 | 24.63 | 15 |
| levels | 8 | 12.78 | 21.95 | 14 |
| constant | 2 | 11.28 | 28.99 | 10 |
| constant | 4 | 11.19 | 28.56 | 10 |
| constant | 8 | 10.64 | 24.45 | 9 |
| varying | 2 | 15.50 | 58.99 | 10 |
| varying | 4 | 9.59 | 19.52 | 10 |
| varying | 8 | 8.49 | 15.81 | 7 |
| varying_constant | 2 | 23.66 | 138.89 | 11 |
| varying_constant | 4 | 12.37 | 34.70 | 10 |
| varying_constant | 8 | 10.57 | 22.66 | 9 |

The following table shows the lowest overall mean variant, varying k with radius 8. This is descriptive selection after scoring, not an accepted production model. All its dark-text luma groups pass individually, but all seven glowing groups fail. Every glowing group also exceeds 8 under its own best result across the entire twelve-combination grid, so choosing different windows per sign would not rescue these tested fits.

Hue columns are `degrees / saturation percent`. Near-gray hues below 2 percent saturation are marked n/a in this table; exact values remain in CSV.

| Held-out group | Previous MAE | Predicted MAE | Native hue / sat | Predicted hue / sat |
| --- | ---: | ---: | --- | --- |
| 1080 day standing dark | 52.43 | 5.54 | 27.9 / 5.0 | 22.4 / 12.2 |
| 1080 shade standing dark | 43.32 | 6.22 | 256.6 / 4.6 | 28.5 / 16.2 |
| 1080 night standing dark | 24.33 | 3.32 | 19.3 / 27.7 | 24.6 / 37.3 |
| 1080 day hanging dark | 47.71 | 6.73 | n/a / 0.5 | 26.5 / 12.4 |
| 1080 shade hanging dark | 36.10 | 4.39 | 221.9 / 12.4 | 43.5 / 13.5 |
| 1080 night hanging dark | 16.91 | 2.93 | n/a / 1.8 | 31.3 / 19.0 |
| 1080 day standing glow | 36.78 | 13.80 | n/a / 0.2 | n/a / 1.8 |
| 1080 shade standing glow | 38.00 | 13.79 | n/a / 0.1 | n/a / 0.5 |
| 1080 night standing glow | 36.30 | 14.08 | n/a / 0.5 | n/a / 1.2 |
| 1440 day standing dark | 49.41 | 4.48 | 23.3 / 3.7 | 21.6 / 13.3 |
| 1440 shade standing dark | 41.52 | 4.38 | 254.7 / 4.2 | 15.3 / 8.8 |
| 1440 day hanging dark | 42.66 | 7.75 | 224.4 / 3.0 | 27.8 / 14.6 |
| 1440 shade hanging dark | 32.94 | 4.30 | 221.3 / 14.0 | 39.7 / 5.0 |
| 1440 day standing glow | 33.21 | 11.04 | n/a / 0.1 | n/a / 0.5 |
| 1440 shade standing glow | 33.32 | 11.08 | n/a / 0.1 | n/a / 0.2 |
| 1440 day hanging glow | 23.79 | 14.69 | n/a / 0.2 | n/a / 0.4 |
| 1440 shade hanging glow | 23.97 | 15.81 | n/a / 0.2 | n/a / 0.2 |

Hue does not consistently match the native pack. The predictor lifts shade hanging text toward brown, while native is cooler: 1080 shade native hue 221.9 at 12.4 percent saturation versus prediction 43.5 at 13.5 percent. This is visible in `offline-fit/previews/shade_hanging_dark.png` against `offline-fit/inputs/shade_native.png`. The night hanging prediction also introduces brown where the native mean is nearly gray. Matching the board's hue alone is therefore not sufficient for these captures.

## Mask limits and interpretation

The established masks do not independently identify native glyph cores. Native TAA/filtering and different glyph edge coverage contribute to per-pixel error. This remains a material limit, rather than evidence that these functional forms are mathematically incapable of matching perfectly registered glyphs.

`native-statistics.csv` quantifies the target variation. At 1080p the deferred-selected glowing pixels have native luma 10th/90th percentiles of **201/239** in day, **200/238.07** in shade, and **197.93/238** at night. Even an independently optimized constant native luma per group has MAE **13.05, 13.91, 13.76**. At 1440p, standing glowing optimal-constant MAE is **10.93/10.75** for day/shade; hanging glowing is **12.26/12.43**. This is a constant-model diagnostic, not a lower bound for arbitrary spatial models. It explains why matching only the mean glowing white point would falsely suggest a pass.

The dark lift's correlation with local scene light is supported by the large MAE reduction, including the other resolution. Its exact cause is not isolated. The source has an explicit screen-space contribution: composite5 combines seven bloom tiles spanning levels 2 through 8 and `mix(color, blur, bloomStrength)` before tonemapping. Those broad, pre-tonemap fields are not equivalent to a small blur of the final, already tone-mapped board. Purkinje and dark desaturation can also affect native hue. These observations support testing screen-space contributions; they do not establish their relative causal shares from these final images alone.

Correction to the earlier cause claim: in this 26.3 mapped jar, DyeColor.BLACK's textColor constructor argument is zero; `AbstractSignRenderer.getDarkColor` scales it by 0.4 for ordinary text. A factor of 5 followed by multiplicative diffuse shading cannot lift that zero. The omitted material path was correctly identified, but attributing the black lift directly to that multiplier was not justified.

## Artifacts and disposition

All paths below are under `docs/validation/world-text-shading-2026-11-01/offline-fit/`:

- `inputs/STATE_{native,before,scene,text,alpha}.png`: exact input crops for replay.
- `scores.csv`, `coefficients.txt`, `native-statistics.csv`: full results and fitted coefficients.
- `previews/GROUP.png`: predicted pixels on the existing deferred crop for the displayed varying/radius-8 fit. Only the named group's measured glyph pixels are changed; other signs, outlines and unmeasured pixels remain the baseline. These are offline model visualizations, not renderer after captures.

No new runtime cost or sharpness result exists because step 2 was gated off. Frame timing remains **unmeasured for a correction**, not 0 ms measured. Shaderless has no rendering changes. The previous build result remains applicable to unchanged runtime sources; the new offline tool compiles/runs with assertions enabled. Glowing hanging text at night, full glyph/outline composition, halos and live A/B remain untested. The pass bar was not reached, so no GPU work follows.
