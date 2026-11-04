// Copy proof: `node tools/dlss-cost/copy-proof.mjs <baseline|fixed> <port>`.
// Frozen route pose in the round 17 preparation world, DLSS Quality preset 11, profiling on for two
// 12 s windows. Separate launch from the FPS passes, so the profiler never runs during an FPS pass.
import fs from 'node:fs';
import {
	scratch, evidence, profile, jarName, prepWorld, delay, save, ps, client, launch, quit, dhSnapshot, sameSnapshot,
} from './lib.mjs';

const [build, portArg] = process.argv.slice(2);
const port = Number(portArg);
const EXPECTED = {
	baseline: '36551E5004D1A08B259F67B87F5781D93754392CE53D8CB544F30FA0B1BD9FAA',
	fixed: '99A0157554E1F2C73517C821D802F4538394B01602B3EB93044D5D36ABFB70EF',
};
const jar = `${scratch}/${build}.jar`;
const dest = `${profile}/mods/${jarName}`;
fs.copyFileSync(jar, dest);
const hash = ps(`(Get-FileHash -LiteralPath '${dest}' -Algorithm SHA256).Hash`);
if (hash !== EXPECTED[build]) throw new Error(`Installed ${build} jar hash mismatch: ${hash}`);

const pid = await launch(port, `copy-proof-${build}`, jar);
const c = client(port);
await c.rx('shader_pack', 'Mellow v3.4.1a.zip');
await c.rx('frame_generation', 'off');
await c.rx('reflex_mode', 'off');
await c.rx('upscaler', 'dlss');
await c.rx('quality', 'quality');
await c.debugGet('/set', {key: 'dlss_preset', value: '11'});
await delay(12000);

await c.call('join', {world: 'Round17 Benchmark Preparation', timeout_s: 90}, 110000);
await c.call('command', {command: 'time set 6000'});
await c.call('command', {command: 'weather clear'});
await c.call('camera', {x: 180.5, y: 121.61999988555908, z: 60.5, yaw: 60, pitch: 20});
await c.call('command', {command: 'tick freeze'});
await delay(12000);

const dhBefore = dhSnapshot(prepWorld);
const captures = [];
for (let k = 1; k <= 2; k++) {
	const first = await c.call('status');
	await c.call('reflexium', {profile: 'reset'});
	await delay(12000);
	const spans = await c.call('reflexium', {profile: 'off'});
	const last = await c.call('status');
	const rxFirst = first.plugins.reflexium, rxLast = last.plugins.reflexium;
	const checks = {
		world_loaded: first.world_loaded && last.world_loaded,
		paused: first.paused || last.paused,
		render_size: rxLast.render_size.w === 1280 && rxLast.render_size.h === 720,
		upscaler_effective: rxLast.upscaler_effective === 'dlss',
		evaluated_counter: rxLast.evaluated_frames > rxFirst.evaluated_frames,
		no_last_error: !rxLast.last_error,
		preset_hint: rxLast.dlss_preset_hint === 11,
	};
	captures.push({capture: k, spans, render_size: rxLast.render_size, preset_hint: rxLast.dlss_preset_hint,
		evaluated_frames: [rxFirst.evaluated_frames, rxLast.evaluated_frames], checks,
		valid: Object.values(checks).every((v, i) => (i === 1 ? !v : v))});
	save(`${evidence}/copy-proof/${build}-capture${k}.json`, {first, last, spans});
}
const dhAfter = dhSnapshot(prepWorld);
await c.call('command', {command: 'tick unfreeze'});
const result = {build, sha256: hash, pose: {x: 180.5, y: 121.62, z: 60.5, yaw: 60, pitch: 20}, world: 'Round17 Benchmark Preparation',
	preset: 11, quality: 'quality', dh_gate: {files_before: dhBefore.count, files_after: dhAfter.count, unchanged: sameSnapshot(dhBefore, dhAfter)},
	captures};
save(`${evidence}/copy-proof/${build}.json`, result);
console.log(JSON.stringify(result.captures.map(k => ({capture: k.capture, valid: k.valid, evaluate: k.spans.spans?.evaluate}))));
await quit(c, pid);
console.log('copy proof complete', build);
