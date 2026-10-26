// Bounded profiler probes for docs/specs/DLSS_LIGHT_PACK_PLAN.md.
// Usage:
//   node tools/dlss-cost/lightpack-probe.mjs evaluate <baseline|fixed|new> <port> <presets> <windows>
//   node tools/dlss-cost/lightpack-probe.mjs fg <fixed|new> <port>
//   node tools/dlss-cost/lightpack-probe.mjs motion <new> <port>
//   node tools/dlss-cost/lightpack-probe.mjs dims <fixed|new> <port>
// Installs one build into the copy profile only, launches it detached, freezes a documented pose and
// records profiler spans and status proofs. No FPS route passes run here.
import fs from 'node:fs';
import path from 'node:path';
import {
	scratch, profile, prepWorld, delay, save, ps, client, launch, quit, idleGate, dhSnapshot, sameSnapshot,
} from './lib.mjs';

export const lightEvidence = 'C:/Users/Ryan/source/repos/Reflexium/docs/validation/dlss-light-pack-2026-10-26';
const HASH = {
	baseline: '36551E5004D1A08B259F67B87F5781D93754392CE53D8CB544F30FA0B1BD9FAA',
	fixed: '99A0157554E1F2C73517C821D802F4538394B01602B3EB93044D5D36ABFB70EF',
	new: '3E6CF91CDA7FEA653447297791541B945A2B3A149B47911FD66E805CA07450DF',
};
const JAR = {
	baseline: `${scratch}/baseline.jar`,
	fixed: `${scratch}/fixed.jar`,
	new: `${scratch}/lightpack.jar`,
};
const [scenario, build, portArg, presetsArg, windowsArg] = process.argv.slice(2);
const port = Number(portArg);
if (!scenario || !HASH[build] || !Number.isFinite(port)) {
	console.error('usage: lightpack-probe.mjs <evaluate|fg|motion|dims> <baseline|fixed|new> <port> [presets] [windows]');
	process.exit(2);
}
const log = (...args) => console.log(new Date().toISOString(), ...args);
const install = () => {
	const dest = `${profile}/mods/Reflexium-1.2.0-alpha-fabric-26.3.jar`;
	fs.copyFileSync(JAR[build], dest);
	const hash = ps(`(Get-FileHash -LiteralPath '${dest}' -Algorithm SHA256).Hash`);
	if (hash !== HASH[build]) throw new Error(`Installed ${build} jar hash mismatch: ${hash}`);
	return hash;
};

const pose = {x: 180.5, y: 121.61999988555908, z: 60.5, yaw: 60, pitch: 20};
const rangePose = {x: 200.5, y: 151, z: 0.5, yaw: 0, pitch: 3};

async function join(c, world, where, waitMs = 12000) {
	await c.call('join', {world, timeout_s: 90}, 110000);
	await c.call('command', {command: 'time set 6000'});
	await c.call('command', {command: 'weather clear'});
	await c.call('camera', where);
	await c.call('command', {command: 'tick freeze'});
	await delay(waitMs);
}

function configMode(c, {upscaler, quality = 'quality', preset, fg = 'off', fgMultiplier}) {
	const steps = [['frame_generation', fg], ['reflex_mode', fg === 'off' ? 'off' : 'on'], ['upscaler', upscaler]];
	if (upscaler !== 'off') steps.push(['quality', quality]);
	// `frame_generation` resets the configured multiplier, so send the fixed multiplier after it.
	if (fgMultiplier != null) steps.push(['fg_multiplier', String(fgMultiplier)]);
	if (preset != null) steps.push(['dlss_preset', String(preset)]);
	return (async () => {
		for (const [key, value] of steps) await c.rx(key, value);
	})();
}

function proof(status, {upscaler, preset, fg}) {
	const rx = status.plugins.reflexium;
	const active = upscaler !== 'off';
	const wantedSize = active ? {w: 1280, h: 720} : {w: 1920, h: 1080};
	return {
		world_loaded: status.world_loaded,
		screen_none: status.screen === 'none',
		no_last_error: !rx.last_error,
		upscaler_effective: rx.upscaler_effective === (active ? 'dlss' : 'off'),
		upscaling_active: rx.upscaling_active === active,
		render_size: rx.render_size.w === wantedSize.w && rx.render_size.h === wantedSize.h,
		preset_hint: preset != null ? rx.dlss_preset_hint === preset : true,
		pack_mellow: String(rx.pack).includes('Mellow'),
		render_distance_8: rx.render_distance === 8,
		fg_path: fg === 'off' ? rx.frame_generation === 'off' : rx.fg_streamline === true,
		window_secondary: status.window.x === 2560 && status.window.width === 1920 && status.window.height === 1080,
	};
}

async function profileWindow(c, label, config) {
	const first = await c.call('status');
	const f0 = first.plugins.reflexium.evaluated_frames;
	await c.call('reflexium', {profile: 'reset'});
	await delay(12000);
	const spans = await c.call('reflexium', {profile: 'off'});
	const last = await c.call('status');
	const checks = proof(last, config);
	checks.evaluated_counter = config.upscaler !== 'off'
		? last.plugins.reflexium.evaluated_frames > f0
		: last.plugins.reflexium.evaluated_frames === f0;
	const record = {
		label, config, spans,
		world_frames: spans.world_frames,
		render_size: last.plugins.reflexium.render_size,
		preset_hint: last.plugins.reflexium.dlss_preset_hint,
		evaluated_frames: [f0, last.plugins.reflexium.evaluated_frames],
		fg_generated_presents: last.plugins.reflexium.fg_streamline_generated_presents,
		fg_active: last.plugins.reflexium.fg_streamline_active,
		camera: last.camera,
		checks, valid: Object.values(checks).every(Boolean),
	};
	save(`${lightEvidence}/probes/${label}.json`, record);
	const span = name => record.spans.spans?.[name] ?? null;
	log(label, 'valid=' + record.valid, 'eval_us=' + (span('evaluate')?.cpu_us ?? 0).toFixed(2),
		'gpu=' + (span('evaluate')?.gpu_us ?? 0).toFixed(2), 'fg_hudless=' + (span('fg_hudless_copy')?.gpu_us ?? 0).toFixed(2));
	return record;
}

async function evaluateScenario(c) {
	const presets = String(presetsArg || '11').split(',').map(Number);
	const windows = Math.max(1, Number(windowsArg || 2));
	const order = [];
	for (let w = 0; w < windows; w++) order.push(...(w % 2 === 0 ? presets : [...presets].reverse()));
	const records = [];
	for (let i = 0; i < order.length; i++) {
		const preset = order[i];
		await configMode(c, {upscaler: 'dlss', preset, fg: 'off'});
		await delay(12000);
		records.push(await profileWindow(c, `evaluate-${build}-p${preset}-w${i + 1}`, {upscaler: 'dlss', preset, fg: 'off'}));
	}
	await c.rx('dlss_preset', '-1');
	save(`${lightEvidence}/probes/evaluate-${build}-${presets.join('-')}.json`, records);
}

async function fgScenario(c) {
	const records = [];
	await configMode(c, {upscaler: 'off', fg: 'dlss', fgMultiplier: 2});
	await delay(15000);
	records.push(await profileWindow(c, `fg-native-${build}-w1`, {upscaler: 'off', fg: 'dlss'}));
	records.push(await profileWindow(c, `fg-native-${build}-w2`, {upscaler: 'off', fg: 'dlss'}));

	await configMode(c, {upscaler: 'dlss', preset: 11, fg: 'dlss', fgMultiplier: 2});
	await delay(15000);
	records.push(await profileWindow(c, `fg-shared-${build}-w1`, {upscaler: 'dlss', preset: 11, fg: 'dlss'}));
	records.push(await profileWindow(c, `fg-shared-${build}-w2`, {upscaler: 'dlss', preset: 11, fg: 'dlss'}));

	// Crosshair composition: the debug entry forces the F3 axes without opening the overlay.
	await c.rx('crosshair_3d', '1');
	await delay(3000);
	const crosshairPath = `${lightEvidence}/probes/crosshair-${build}-real.png`;
	await c.debugGet('/screenshot', {path: crosshairPath, gui: '1', crop_width: '640', crop_height: '360'});
	const crosshairStatus = await c.call('status');

	// Toggle sequence: native FG -> SR+FG -> native FG. Generation is expected to stay paused in
	// this unfocused window; the proof records the counters rather than treating zero as a failure.
	const toggles = [];
	for (const config of [
		{upscaler: 'off', fg: 'dlss', fgMultiplier: 2},
		{upscaler: 'dlss', preset: 11, fg: 'dlss', fgMultiplier: 2},
		{upscaler: 'off', fg: 'dlss', fgMultiplier: 2},
	]) {
		await configMode(c, config);
		await delay(12000);
		const status = await c.call('status');
		toggles.push({config, status: status.plugins.reflexium, checks: proof(status, config)});
	}
	await c.rx('crosshair_3d', '0');
	await c.rx('frame_generation', 'off');
	await c.rx('upscaler', 'off');
	save(`${lightEvidence}/probes/fg-${build}.json`, {records, crosshair: {status: crosshairStatus.plugins.reflexium, file: crosshairPath}, toggles});
}

async function motionScenario(c) {
	await c.call('command', {command: 'function reflexium:goto_range'});
	await c.call('command', {command: 'function reflexium:spawn_mobs'});
	await delay(15000);
	await configMode(c, {upscaler: 'off', fg: 'off'});
	await delay(12000);
	const native = await profileWindow(c, `motion-${build}-native`, {upscaler: 'off', fg: 'off'});
	await configMode(c, {upscaler: 'dlss', preset: 11, fg: 'off'});
	await delay(12000);
	const quality = await profileWindow(c, `motion-${build}-quality`, {upscaler: 'dlss', preset: 11, fg: 'off'});
	const targets = await c.call('reflexium', {dh_targets: 1});
	const status = await c.call('status');
	save(`${lightEvidence}/probes/motion-${build}.json`, {pose: rangePose, native, quality, dh_targets: targets, status: status.plugins.reflexium});
}

async function dimsScenario(c) {
	await configMode(c, {upscaler: 'off', fg: 'off'});
	await delay(12000);
	const nativeStatus = await c.call('status');
	const nativeTargets = await c.call('reflexium', {dh_targets: 1});
	await configMode(c, {upscaler: 'dlss', preset: 11, fg: 'off'});
	await delay(12000);
	const qualityStatus = await c.call('status');
	const qualityTargets = await c.call('reflexium', {dh_targets: 1});
	save(`${lightEvidence}/probes/dims-${build}.json`, {
		pose,
		native: {status: nativeStatus.plugins.reflexium, dh_targets: nativeTargets},
		quality: {status: qualityStatus.plugins.reflexium, dh_targets: qualityTargets},
	});
}

const hash = install();
log(`installed ${build} ${hash} into the copy profile`);
const pid = await launch(port, `lightpack-${scenario}-${build}`, JAR[build]);
const c = client(port);
log(`client pid ${pid} at title`);
await idleGate(c, pid);
await c.rx('shader_pack', 'Mellow v3.4.1a.zip');
await delay(12000);

try {
	if (scenario === 'evaluate') {
		await join(c, 'Round17 Benchmark Preparation', pose);
		await evaluateScenario(c);
	} else if (scenario === 'fg') {
		await join(c, 'Round17 Benchmark Preparation', pose);
		await fgScenario(c);
	} else if (scenario === 'motion') {
		await join(c, 'Reflexium Vitrail Smoke', rangePose);
		await motionScenario(c);
	} else if (scenario === 'dims') {
		await join(c, 'Round17 Benchmark Preparation', pose);
		await dimsScenario(c);
	} else {
		throw new Error('unknown scenario ' + scenario);
	}
} finally {
	const before = dhSnapshot(prepWorld);
	await c.call('command', {command: 'tick unfreeze'}).catch(() => {});
	await quit(c, pid);
	log('quit; dh files', before.count, 'bytes', before.bytes);
}
log('done', scenario, build);
