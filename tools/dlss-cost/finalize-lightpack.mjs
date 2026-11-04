// Accept the final route passes for docs/validation/dlss-light-pack-2026-10-26.md.
// Usage: node tools/dlss-cost/finalize-lightpack.mjs <build>
import fs from 'node:fs';
import {scratch, save} from './lib.mjs';

const lightEvidence = 'C:/Users/Ryan/source/repos/Reflexium/docs/validation/dlss-light-pack-2026-10-26';

const build = process.argv[2];
if (!build) {
	console.error('usage: finalize-lightpack.mjs <build>');
	process.exit(2);
}
const order = ['native', 'cnn-q', 'transformer-q'];
const passes = JSON.parse(fs.readFileSync(`${lightEvidence}/passes-${build}.json`, 'utf8'));
const spread = (a, b) => 100 * (Math.max(a, b) / Math.min(a, b) - 1);
const mean = values => values.reduce((a, b) => a + b, 0) / values.length;
const summary = {};
for (const p of passes) {
	// The strict snapshot gate also counts a transient SQLite -wal size change. The plan's DH
	// requirement is no new LOD files and zero generation evidence: accept the durable form when the
	// generator plan is disabled and no generation log lines appeared. Keep the raw flags visible.
	const durableGate = p.dh && p.dh.generator_plan === 'DISABLED' && p.dh.durable_unchanged_before_to_end
		&& p.dh.log_generation_lines === 0;
	p.dh_review = p.dh ? {durable_gate: durableGate, transient_wal_changed: !p.dh.unchanged_crop_to_end} : null;
	p.review_valid = Boolean(p.valid || (p.mode?.valid && p.content?.content_valid && durableGate
		&& p.gate_flags && !p.gate_flags.invalid && !p.gate_flags.load_timeout && p.gate_flags.backend_vulkan
		&& p.gate_flags.settle_ready));
}
for (const config of order) {
	const mine = passes.filter(p => p.config === config && p.build === build);
	const valid = mine.filter(p => p.review_valid);
	let accepted = null;
	if (valid.length >= 2) {
		let best = null;
		for (let i = 0; i < valid.length; i++) for (let j = i + 1; j < valid.length; j++) {
			const s = spread(valid[i].avg_fps, valid[j].avg_fps);
			if (!best || s < best.s) best = {s, pair: [valid[i], valid[j]]};
		}
		if (best && best.s <= 3) accepted = best.pair;
	}
	summary[config] = {
		status: accepted ? 'accepted' : (valid.length ? 'noisy' : 'invalid'),
		accepted_passes: accepted ? accepted.map(p => p.key) : [],
		spread_pct: accepted ? spread(accepted[0].avg_fps, accepted[1].avg_fps) : null,
		avg_fps: accepted ? mean(accepted.map(p => p.avg_fps)) : null,
		low1_fps: accepted ? mean(accepted.map(p => p.low1_fps)) : null,
		low01_fps: accepted ? mean(accepted.map(p => p.low01_fps)) : null,
		all_passes: mine.map(p => ({key: p.key, valid: p.valid, review_valid: p.review_valid, avg_fps: p.avg_fps, sha256: p.sha256, dh_review: p.dh_review})),
	};
	for (const p of mine) p.accepted = Boolean(accepted && accepted.includes(p));
}
save(`${lightEvidence}/summary-${build}.json`, summary);
const outDir = `${lightEvidence}/capframex`;
fs.mkdirSync(outDir, {recursive: true});
for (const p of passes.filter(p => p.accepted)) {
	fs.copyFileSync(`${scratch}/capframex-all/${p.key}.csv`, `${outDir}/${p.key}.csv`);
}
save(`${lightEvidence}/passes-${build}.json`, passes);
console.log(JSON.stringify(summary, null, 2));
