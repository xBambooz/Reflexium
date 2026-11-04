// One route pass in a fresh launch for docs/validation/dlss-light-pack-2026-10-26.md.
// Usage: node tools/dlss-cost/final-batch.mjs <build> <port> <config> <pass>
// config: native | cnn-q | transformer-q. Each invocation installs the build, launches detached,
// runs one Benchmark Mod pair, records proof/content/DH gates and appends the pass to passes-<build>.json.
import fs from 'node:fs';
import {
	scratch, profile, jarName, sceneWorld, delay, save, ps, client, launch, quit, idleGate,
	dhSnapshot, sameSnapshot, sameDurable, dhGeneratorPlan, logSize, dhLogSince, listResults, readResult,
	capframexCsv, metrics, contentCheck, passKey,
} from './lib.mjs';

export const lightEvidence = 'C:/Users/Ryan/source/repos/Reflexium/docs/validation/dlss-light-pack-2026-10-26';
const HASH = {
	lightpack: '3E6CF91CDA7FEA653447297791541B945A2B3A149B47911FD66E805CA07450DF',
};
const CONFIGS = {
	native: {upscaler: 'off'},
	'cnn-q': {upscaler: 'dlss', quality: 'quality', preset: 5},
	'transformer-q': {upscaler: 'dlss', quality: 'quality', preset: 11},
};
const [build, portArg, config, passArg] = process.argv.slice(2);
const port = Number(portArg), pass = Number(passArg);
const spec = CONFIGS[config];
if (!HASH[build] || !spec || !Number.isFinite(port) || !Number.isFinite(pass)) {
	console.error('usage: final-batch.mjs <lightpack> <port> <native|cnn-q|transformer-q> <pass>');
	process.exit(2);
}
const log = (...args) => console.log(new Date().toISOString(), ...args);
let ownedPid = 0, ownedClient = null;

function installJar() {
	const dest = `${profile}/mods/${jarName}`;
	fs.copyFileSync(`${scratch}/${build}.jar`, dest);
	const hash = ps(`(Get-FileHash -LiteralPath '${dest}' -Algorithm SHA256).Hash`);
	if (hash !== HASH[build]) throw new Error(`Installed ${build} jar hash mismatch: ${hash}`);
	return hash;
}

function modeProof(first, last, current, index) {
	const rx = last.plugins.reflexium, firstRx = first.plugins.reflexium;
	const active = spec.upscaler !== 'off';
	const expected = spec.preset ? {w: 1280, h: 720} : {w: 1920, h: 1080};
	const checks = {
		world_loaded: first.world_loaded && last.world_loaded,
		screen_none: first.screen === 'none' && last.screen === 'none',
		measuring_same_run: current.phase === 'MEASURING' && Number(current.runIndex) === index,
		no_last_error: !rx.last_error,
		upscaler_effective: rx.upscaler_effective === (active ? 'dlss' : 'off'),
		upscaling_active: rx.upscaling_active === active,
		render_size: rx.render_size.w === expected.w && rx.render_size.h === expected.h,
		evaluated_counter: active ? rx.evaluated_frames > firstRx.evaluated_frames : rx.evaluated_frames === firstRx.evaluated_frames,
		preset_hint: spec.preset ? rx.dlss_preset_hint === spec.preset : true,
		window_secondary: last.window.x === 2560 && last.window.width === 1920 && last.window.height === 1080,
		pack_mellow: String(rx.pack).includes('Mellow'),
		render_distance_8: rx.render_distance === 8,
	};
	return {checks, valid: Object.values(checks).every(Boolean)};
}

async function main() {
	const hash = installJar();
	const pid = await launch(port, `lightpack-final-${build}-${config}-p${pass}`, `${scratch}/${build}.jar`);
	const c = client(port);
	ownedPid = pid; ownedClient = c;
	log(`client pid ${pid} at title; build ${hash}`);
	await c.rx('shader_pack', 'Mellow v3.4.1a.zip');
	await delay(12000);
	await idleGate(c, pid);

	await c.rx('frame_generation', 'off');
	await c.rx('reflex_mode', 'off');
	await c.rx('upscaler', spec.upscaler);
	if (spec.preset) {
		await c.rx('quality', spec.quality);
		await c.rx('dlss_preset', String(spec.preset));
	}
	await delay(12000);

	const genPlan = dhGeneratorPlan();
	const dhBefore = dhSnapshot(sceneWorld);
	const logOffset = logSize();
	const before = new Set(listResults());
	const label = `lightpack-${build}-${config}-pass${pass}`;
	for (let i = 0; i < 360; i++) {
		const state = await c.call('bm', {});
		if (['IDLE', 'FINISHED', 'ABORTED'].includes(state.phase)) break;
		await delay(1000);
	}
	await c.call('bm', {scene: 'benchmark-route', label});

	const crop = `${scratch}/crops/${label}.png`;
	fs.mkdirSync(`${scratch}/crops`, {recursive: true});
	let captured = null, everMeasuring = false, dhCrop = null, dhEnd = null;
	for (let count = 0; count < 480; count++) {
		const state = await c.call('bm', {});
		if (state.phase === 'MEASURING') {
			everMeasuring = true;
			const index = Number(state.runIndex);
			if (index === 2 && !captured) {
				const target = BigInt(state.startedNanos) + 21_000_000_000n;
				const remaining = Number(target - process.hrtime.bigint()) / 1e6;
				if (remaining > 1000 && remaining < 24000) await delay(remaining - 100);
				else if (Number(state.serverTick) < 460) await delay((480 - Number(state.serverTick)) * 50 - 100);
				const first = await c.call('status');
				const shot = await c.debugGet('/screenshot', {path: crop, gui: '1', crop_width: '640', crop_height: '360'});
				dhCrop = dhSnapshot(sceneWorld);
				await delay(250);
				const last = await c.call('status');
				const current = await c.call('bm', {});
				const mode = modeProof(first, last, current, index);
				captured = {index, first, last, shot, bm: current, mode};
				log(`crop taken, mode proof ${mode.valid}`);
				await delay(23000);
			}
		}
		if (everMeasuring && !dhEnd && (state.phase === 'FINISHED' || state.phase === 'IDLE')) dhEnd = dhSnapshot(sceneWorld);
		if (everMeasuring && (state.phase === 'FINISHED' || state.phase === 'IDLE')) break;
		if (state.phase === 'ABORTED') throw new Error('Benchmark Mod aborted ' + label);
		await delay(500);
	}
	if (!captured || !dhCrop || !dhEnd) throw new Error('No crop or DH end snapshot for ' + label);

	let file;
	for (let i = 0; i < 60; i++) {
		file = listResults().filter(f => !before.has(f)).find(f => readResult(f).label === label);
		if (file) break;
		await delay(500);
	}
	if (!file) throw new Error('Missing Benchmark Mod result ' + label);
	const result = readResult(file);
	const dhLog = dhLogSince(logOffset);
	const dhGate = genPlan === 'DISABLED' && sameDurable(dhBefore, dhEnd) && sameSnapshot(dhCrop, dhEnd) && dhLog.count === 0;
	const pair = result.runs.slice(1, 3);
	const pooled = metrics(pair.flatMap(r => r.frames.ms));
	const perRun = pair.map(r => ({bm_avg_fps: r.summary.avgFps, bm_low1_fps: r.summary.low1Fps, bm_low01_fps: r.summary.low01Fps, ...metrics(r.frames.ms)}));
	const csvDir = `${scratch}/capframex-all`;
	fs.mkdirSync(csvDir, {recursive: true});
	fs.writeFileSync(`${csvDir}/${label}.csv`, capframexCsv(pair));
	const bmDir = `${lightEvidence}/bmresults`;
	fs.mkdirSync(bmDir, {recursive: true});
	fs.copyFileSync(file, `${bmDir}/${label}.bmresult.json.gz`);

	const content = contentCheck([crop])[0];
	const gateFlags = {
		invalid: result.flags.invalid, load_timeout: result.flags.loadTimeout, focus_lost: result.flags.focusLost,
		backend_vulkan: result.environment.gpu.backend === 'Vulkan',
		settle_ready: pair.every(r => r.settleRelease === 'ready' && !r.settleTimeout),
	};
	const valid = captured.mode.valid && content.content_valid && dhGate && !gateFlags.invalid && !gateFlags.load_timeout
		&& gateFlags.backend_vulkan && gateFlags.settle_ready;
	const record = {
		key: label, build, config, pass, sha256: hash, label, bm_file: `${label}.bmresult.json.gz`,
		dh: {generator_plan: genPlan, durable_unchanged_before_to_end: sameDurable(dhBefore, dhEnd),
			unchanged_crop_to_end: sameSnapshot(dhCrop, dhEnd), log_generation_lines: dhLog.count, gate_valid: dhGate},
		mode: captured.mode,
		mode_status: {upscaler_effective: captured.last.plugins.reflexium.upscaler_effective,
			render_size: captured.last.plugins.reflexium.render_size, preset_hint: captured.last.plugins.reflexium.dlss_preset_hint,
			evaluated_frames: [captured.first.plugins.reflexium.evaluated_frames, captured.last.plugins.reflexium.evaluated_frames],
			status_line: captured.last.plugins.reflexium.status_line, window: captured.last.window},
		content, gate_flags: gateFlags, valid, pooled, per_run: perRun,
		avg_fps: pooled.avg_fps, low1_fps: pooled.low1_fps, low01_fps: pooled.low01_fps,
	};
	fs.mkdirSync(`${lightEvidence}/proofs`, {recursive: true});
	save(`${lightEvidence}/proofs/${label}-proof.json`, {record, crop_samples: captured});
	const passesFile = `${lightEvidence}/passes-${build}.json`;
	const passes = fs.existsSync(passesFile) ? JSON.parse(fs.readFileSync(passesFile, 'utf8')) : [];
	passes.push(record);
	save(passesFile, passes);
	log(`done: avg ${pooled.avg_fps.toFixed(1)} low1 ${pooled.low1_fps.toFixed(1)} low0.1 ${pooled.low01_fps.toFixed(1)} valid=${valid} dh=${dhGate} mode=${captured.mode.valid} content=${content.content_valid}`);
	await quit(c, pid);
	ownedPid = 0; ownedClient = null;
}

main().catch(async error => {
	console.error('FINAL PASS FAILED', error.stack || error);
	if (ownedPid && ownedClient) {
		try { await quit(ownedClient, ownedPid); }
		catch (closeError) { console.error('Owned shutdown after failure did not complete', closeError.message); }
	}
	process.exitCode = 1;
});
