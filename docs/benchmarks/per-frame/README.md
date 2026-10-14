# Per-frame investigation, 14 October 2026

Analysis and interpretation: [PERFORMANCE.md](../../PERFORMANCE.md).

- `summary.csv` and `summary.json`: accepted before/after rows. `source` identifies the raw capture family. Each before/after pair has two samples per arm from one JVM. Do not pool absolute FPS from different JVMs.
- `final-*`: full candidate ABBA, frozen scene, four-second warmup and four-second measurement. The CNN Ultra Performance F preset and FSR transient-uniform/direct-depth candidates in this family were rejected. These are retained for audit, not silently replaced.
- `retained-*`: subsequent six-mode ABBA in the first JVM, three-second warmup and five-second measurement. Only its CNN Ultra Performance E row feeds the accepted summary; FSR still had the later-rejected direct-depth candidate.
- `fsr-accepted-*`: final-build FSR ABBA with native controls, three-second warmup and four-second measurement. Original FSR uniform and depth paths are retained. The selected pose has a visible outline, so the accepted FSR path performs the same work in both arms; its small deltas are not a claimed optimization win.
- `original.jsonl`: first uninstrumented baseline in an earlier JVM, contextual only.
- `profile-original`, `profile-final`, `profile-legacy`, `profile-retained`: exploratory CPU/GPU traces. `profile-shipped.jsonl` is the final source configuration. Do not use profiled FPS as the uninstrumented headline.
- `cpu-breakdown.csv`, `gpu-breakdown.csv`: final profile tables. Parent spans overlap child spans. Completion-check invocation counts include cached checks and do not count only actual blocking Vulkan calls.
- `native-controls.jsonl`, `native-input-summary.csv`, `targets.csv`: real input-resolution controls and the requested additive reference. NVIDIA costs are Performance-input proxies outside Performance. FSR costs are measured dispatch GPU times. The formula is not a hard upper bound when CPU and GPU work overlap.
- `perf_*`, `preset-*`, `balanced-*`, `reflex-*`, `outline-*`: isolated experiments. Candidate FPS improvements were not retained merely because one arm was faster. The invisible-outline GPU pass is absent with the optimization.
- `image-metrics*.json`, `image-hashes.json`, `motion-*.png`, `motion-visual-check.json`: image and velocity evidence. The initial entity counters alone were insufficient; the later visible-surface overlays and camera-only black control are the acceptance evidence.
- `frame-generation-check.json`: 12 supported FG combinations, including 3x through 6x MFG. This proves functional evaluation/presentation counters, not physical scanout pacing.
- `vitrail-check.json`, `vitrail-profile.json`: Complementary Reimagined smoke timings and pack hand pass costs.
- `build-verification.json`: installed/built release JAR hash equality and passing build checks.

Large screenshots and the exploratory JFR remain ignored in `run/`. No run directory, build output, session token or native runtime download is committed.
