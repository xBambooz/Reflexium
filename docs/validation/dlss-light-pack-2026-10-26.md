# DLSS light pack: ranked implementation results

Date: 2026-10-26. Branch `round6-validation`, design baseline `dc344e6`. Artifact under test
`Reflexium-1.2.0-alpha-fabric-26.3.jar`, SHA-256
`3E6CF91CDA7FEA653447297791541B945A2B3A149B47911FD66E805CA07450DF`.
Extracted `natives/windows-x86_64/reflexium_sl.dll` SHA-256
`A4CA9652728A0A36D3744DEA2CFD7514986475FBAE0D8E42BBE808E830F46193`.

Retained commits: `d2e029d` (item 1 tooltip), `d6894cd` (item 3 native-FG SR targets),
`aa5879f` (item 4 deferred crosshair). The plan's ranked order was followed. No new selector,
route matrix or validation round was added.

## Measurement setup and limits

- Copied profile `Fabric 26.3 RxRound17` only. Ryan's real `Fabric 26.3` and `26.3 Testing
  Instance` profiles were not launched or modified.
- 1920x1080 on the secondary XF250Q, Mellow v3.4.1a, render distance 8, DH on with
  `generatorPlan = "DISABLED"`, FG and Reflex off for SR-only rows, VSync off, uncapped. Sound is
  zero. The game was started detached with `tools\launch-modrinth-profile.ps1` and closed through
  `/quit`.
- Frozen pose for profiler A/Bs: `(180.5, 121.62, 60.5)`, yaw 60, pitch 20 in
  `Round17 Benchmark Preparation`. Motion probe: range pose `(200.5, 151, 0.5)`, yaw 0, pitch 3
  in `Reflexium Vitrail Smoke` with the tagged passive mobs spawned.
- Profiler spans are `FrameProfiler` CPU and GPU microseconds. GPU means are per sample; a span
  with two samples per world frame is doubled before it is described as a per-frame cost.
- Frame generation cannot insert generated presents in the owned, unfocused harness window. The
  FG span probes still run the application tagging and copy paths. Generated-frame counters stayed
  at zero and are reported as blocked, not as a renderer failure. Focus was not stolen.
- No Vulkan API trace was obtained; the only capture tools on this machine are the unregistered
  LunarG layers under `build/validation/fg-tools/sdk/Bin`. Copy absence is therefore code-proven
  where stated, not API-traced. This is the main verification limit below.

## Item outcomes

### 1. Expose the CNN speed choice accurately: retained with evidence

`Auto` remains the default and still maps to SDK preset 0. `DlssModel` keeps CNN as explicit E=5
and Transformer as K=11 for DLAA/Quality/Balanced, M=13 for Performance and L=12 for Ultra
Performance. The false "CNN is the default" claim was removed from
`src/client/resources/assets/reflexium/lang/en_us.json`. The model tooltip now says Auto uses the
runtime recommendation and that CNN reduces GPU cost but may reduce image quality. The upscaler
tooltip now carries the light-scene guidance: at high FPS, light shaders or no shaders, DLSS can be
slower, and Off gives maximum FPS. There is no new selector and no adaptive policy.

E/K evaluate A/B on the final artifact at the frozen pose, ABBA order E, K, K, E, two 12 s windows
each, preset requested as a hint and restored to `-1` afterwards:

| Window | Preset | Valid | World frames | Evaluate CPU us | Evaluate GPU us | Render size |
| --- | ---: | --- | ---: | ---: | ---: | --- |
| w1 | 5 | yes | 4833 | 70.27 | 259.79 | 1280x720 |
| w2 | 11 | yes | 4515 | 65.47 | 579.05 | 1280x720 |
| w3 | 11 | yes | 4520 | 65.44 | 584.95 | 1280x720 |
| w4 | 5 | yes | 5197 | 67.43 | 257.79 | 1280x720 |

Means: CNN E 258.79 us GPU and 68.85 us CPU; Transformer K 582.00 us GPU and 65.46 us CPU.
K minus E is +323.21 us GPU (+124.9 percent) and -3.39 us CPU. This is the model cost, not a
wrapper defect. The established 0.35 to 0.37 ms budget is confirmed at 0.32 ms on this pose.

The existing round 15 foliage/forest comparisons remain the quality check: preset E was 0.05 to
0.5 dB better on several crops, within a few tenths of a dB of Auto/K, and E is a deprecated
legacy preset. Item 1 does not change the default, so objective 5 is neither regressed nor newly
certified. Raw files: `probes/evaluate-new-5-11.json` and the four `evaluate-new-p5/p11-*.json`.

### 2. Validate the existing local Streamline tags: already correct, copy trace inconclusive

`dffb65f` already passes color, depth, motion and output as local `ResourceTag` inputs and keeps
the FG global tags. No new lifetime change was made. Old/new evaluate A/B at preset 11, ABBA across
builds (baseline `762cadc`, fixed `dffb65f`), two 12 s windows per launch:

| Side | Window evaluate GPU us | Launch mean GPU us | Repeat spread |
| --- | --- | ---: | ---: |
| baseline | 581.84, 591.66, 581.50, 586.64 | 586.75, 584.07 | 0.46 percent |
| fixed | 577.25, 592.91, 584.66, 589.71 | 585.08, 587.18 | 0.36 percent |

The four-window means are 585.41 us baseline and 586.13 us fixed, so fixed minus baseline is
+0.72 us (+0.12 percent), smaller than both repeat spreads. No evaluate saving is accepted. The
first fixed windows had CPU outliers (106.29 and 149.87 us) that the first baseline windows did
not; the second windows are 66.58 and 67.42 us fixed against 67.90 and 72.58 us baseline, so the
CPU difference is not a sustained regression either. A per-window JSON overwrite lost the
`world_frames` and counter proofs for the first two launches; their CPU and GPU values survive in
the console record and every window was reported valid at run time. No API trace was captured, so
the two predicted clone copies are not proven absent by a trace. The source path is correct and
the measured evaluate cost is unchanged.

### 3. Remove unused SR targets and the native-FG color flip: retained with evidence

Native DLSS-G never evaluates SR. The old code allocated `srColorTarget` and `srOutputTarget` and
copied scene color into the first every frame. `srTargetsWanted` is now derived from the shared
SR/DLSS-G path, the rebuild predicate and preparation guard agree with it, and SR targets are
released after a GPU wait when the path ends. Depth, motion and hudless preparation are unchanged.
The display-size SR color copy is skipped for native FG, and the two SR targets are not allocated.

FG span A/B at the frozen pose, FG fixed 2x with SR off, two 12 s windows per build:

| Build | FG_HUDLESS CPU us/sample | FG_HUDLESS GPU us/sample | World GPU us | Repeat spread GPU |
| --- | ---: | ---: | ---: | ---: |
| fixed `99A01575...` | 3.921 | 34.131 | 980.84, 988.53 | 2.01 percent |
| new `3E6CF91C...` | 3.536 | 28.918 | 989.80, 1002.12 | 1.28 percent |

The FG_HUDLESS span has two samples per world frame. New minus fixed is -0.385 us CPU and
-5.213 us GPU per sample, about -0.77 us CPU and -10.4 us GPU per frame. The 15.3 percent GPU
reduction exceeds both within-build repeat spreads, so this is an accepted screening result inside
the plan's 0 to 0.05 ms budget. Nominal shared-memory reduction is about 31.6 MiB at 1080p.
`fg_streamline` remained true and no `last_error` appeared through native FG, SR+FG and back to
native FG. Generated presents stayed zero because the window was unfocused. The absent color flip
is proven by the guarded code path and the span drop, not by an API trace. Raw files:
`probes/fg-fixed.json`, `probes/fg-new.json` and the eight `fg-native/fg-shared` window files.

### 4. FG cap and count semantics, and the deferred crosshair: cap/count inconclusive, crosshair retained

Cap: the exact 120 FPS fixed-2x discriminator could not run. In the owned unfocused window DLSS-G
keeps `fg_streamline_generated_presents` at zero, so the presented-interval multiplier is not
exercised. No cap code was changed. Count: the same focus block prevents a menu pause/resume from
producing generated frames, so the state-query accounting was not changed. Both remain open for an
attended focused test, and no claim is made from the unfocused zero-generation samples.

Crosshair: the deferred F3 axis crosshair is drawn after the hudless capture, so Streamline's UI
alpha replay previously omitted it. The same display-resolution projection and draw order are now
replayed into the cleared UI alpha target before the GUI list. A forced `crosshair_3d=1` capture
shows the axes in the real frame, and `fg_ui_capture` sampled for both native FG and shared SR+FG.
The toggle sequence had no renderer error. The generated-frame visual cannot be captured while
DLSS-G is paused unfocused, so the composition identity is not visually certified on generated
frames. This is the remaining limit for item 4. Raw images: `probes/crosshair-fixed-real.png` and
`probes/crosshair-new-real.png`.

### 5. Reduce FG orientation conversions and GUI replay: rejected, gate not met

At fixed 2x with a populated HUD on the final artifact, shared SR+FG spans were:

| Span | CPU us | GPU us | Samples |
| --- | ---: | ---: | --- |
| fg_hudless_copy | 3.912 | 22.595 | 1 per sampled frame pair |
| fg_ui_capture | 9.723 | 24.056 | 1 per sampled frame |
| fg_gui | 0 | 0 | 0 |

The sum of the measured UI and hudless span means is 46.651 us, below the plan's 50 us gate. The
UI capture alone is about 24 us. No redundant SR-output round trip was isolated from the evaluate
span, and the GUI replay is already skipped for Streamline generated frames. No renderer change
was made.

### 6. Motion-pass and CPU bookkeeping hot spot: inconclusive, no change

At the range pose with 16 tracked entities, 4656 object vertices were captured but 0 were drawn,
so the capture does not cover actual moving geometry. The DLSS Quality window recorded
`camera_motion` 4.78 us CPU and 22.19 us GPU, `object_motion` 12.81 us CPU and 0.0036 us GPU, and
no `phase_entities` samples. The largest CPU candidate is below the plan's 20 us gate, and the
camera motion GPU span is not a removable bookkeeping cost. No code was changed. A capture with
drawn moving vertices is still needed for a definitive motion-pass result.

### 7. Resolution boundaries: already correct, no code

At the frozen route pose, native reported render and display 1920x1080; DLSS Quality reported
render 1280x720 and display 1920x1080. `dh_targets` reported `iris_main_depth_size`, `depth_size`,
and the terrain, water and generic attachments at 1920x1080 native and 1280x720 Quality, with all
three attachments using DH depth and `ok: true`. Every reported extent matched its mode's render
size; nothing was display-sized that should have followed the render size. Mellow's absolute
targets were not separately enumerated, but the plan records that absolute pack targets
intentionally ignore relative scaling. No code was changed.

### 8. Pre-tonemap DLSS path: inconclusive, gate not met

No prototype was attempted. The plan's only supported boundary is the Complementary HDR pack path,
which is FSR-only today because FG rejects FP16 display output. The measured evidence still
attributes the residual static softness to temporal reconstruction (round 15 jitter test), not to
the post-tonemap input location, and the expected performance budget is 0 ms. With bright and
translucent content the HDR path could still be a quality experiment, but there is no measured
quality deficit here that justifies its exposure, tonemap-order and FG-format risk. The item remains
inconclusive rather than rejected.

### 9. Vulkan states, barrier scope and constant timing: inconclusive, no patch

No current validation trace was captured. The SR resources declare `VK_IMAGE_LAYOUT_GENERAL`, which
is valid for every access used here, and the existing interop barrier is broad rather than proven
redundant. Earlier synchronization validation on the raw NGX path reported hazards inside the
vendor evaluation, not caller barriers. A Streamline-path validation capture is still needed before
any barrier narrowing or constant-caching change; none was made. Expected saving remains 0 to
0.05 ms unproven.

### 10. Engine wait and async serialization: rejected, gate not met

Round 17 already established that the in-world completion waits track the submitted GPU work:
world CPU 1837.0 to 2417.0 us per frame, completion waits 834.8 to 1290.5 us, world GPU 956.2 to
1383.9 us with evaluation at 588.5 us. The waits overlap necessary GPU work, and no independent
work or early Fog ring reuse was demonstrated. No fence, frame-in-flight, queue or sleep change was
made. A new CPU/GPU timeline is still required before an async SR prototype could be considered.

## Final route batch

Fixed setup as above. Two passes per configuration, each in a fresh launch because the previous
run's second visits changed the DH SQLite WAL and failed the gate. Reverse order for the second
round: native, CNN Quality (preset 5), Transformer Quality (preset 11), then Transformer, CNN,
native. Profiling is off. The table is the mean of accepted passes; the CapFrameX sheet is
generated from the same accepted CSVs.

| Configuration | Avg FPS | 1% low | 0.1% low | Avg vs native | 1% vs native | 0.1% vs native | Spread |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Native | 446.6 | 258.1 | 160.3 | baseline | baseline | baseline | 0.62% |
| DLSS CNN Quality (preset 5) | 406.8 | 242.9 | 158.1 | -8.9% | -5.9% | -1.4% | 0.38% |
| DLSS Transformer Quality (preset 11) | 352.1 | 216.8 | 145.5 | -21.2% | -16.0% | -9.3% | 0.42% |

All three rows are accepted under the 3% repeat rule. CNN Quality is 15.5% faster on average
than Transformer Quality, consistent with the 0.32 ms evaluate difference. Both reconstructed
rows still lose to native in this Mellow plus DH scene, matching the accepted 2026-10-25 decision
that 1080p and light-shader losses are expected. No SR mode was changed, so no additional
changed-mode row was added. Native FG was not a valid route row because DLSS-G pauses in the
unfocused window; its copy saving is the item 3 span A/B instead. The transient SQLite `-wal`
check failed for the two Transformer passes; the generator plan was disabled, the durable DH files
were unchanged and zero generation log lines appeared, so the plan's no-new-LOD-file gate is
treated as passed and the WAL companion is recorded separately in `passes-lightpack.json`.

The artifact is the same SHA-256 for every row. The sheet is
`docs/validation/dlss-light-pack-2026-10-26/capframex-sheet.png`.

## Remaining limits

- The generated-frame visual for item 4, the 120 FPS cap discriminator and the menu count
  sequence need an attended focused test. They are blocked, not passed.
- No Vulkan API trace exists for item 2 or item 3. Copy removal is source-level and span-level
  evidence only.
- Item 6 did not draw moving geometry. Item 8 is not attempted. Item 9 has no current validation
  trace.
- The final batch covers 1080p SR-only on this profile and artifact. It does not certify 1440p/4K,
  FG cadence, other packs, shaderless scenes or the complete release objectives. Round 15's static
  detail limit remains.

## Reproduce

```powershell
node tools/dlss-cost/lightpack-probe.mjs evaluate new 25630 5,11 2
node tools/dlss-cost/lightpack-probe.mjs evaluate baseline 25631 11 2
node tools/dlss-cost/lightpack-probe.mjs evaluate fixed 25632 11 2
node tools/dlss-cost/lightpack-probe.mjs evaluate fixed 25633 11 2
node tools/dlss-cost/lightpack-probe.mjs evaluate baseline 25634 11 2
node tools/dlss-cost/lightpack-probe.mjs fg fixed 25640
node tools/dlss-cost/lightpack-probe.mjs fg new 25641
node tools/dlss-cost/lightpack-probe.mjs motion new 25650
node tools/dlss-cost/lightpack-probe.mjs dims new 25651
node tools/dlss-cost/final-batch.mjs lightpack 25660 native 1
node tools/dlss-cost/final-batch.mjs lightpack 25661 cnn-q 1
node tools/dlss-cost/final-batch.mjs lightpack 25662 transformer-q 1
node tools/dlss-cost/final-batch.mjs lightpack 25663 transformer-q 2
node tools/dlss-cost/final-batch.mjs lightpack 25664 cnn-q 2
node tools/dlss-cost/final-batch.mjs lightpack 25665 native 2
node tools/dlss-cost/finalize-lightpack.mjs lightpack
node tools/capframex-sheet.mjs
```

The copied profile, scene folder and shader pack are the round 17 copies; the original profiles
are untouched.
