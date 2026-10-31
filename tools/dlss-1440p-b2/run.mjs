import {acquire,release,exclusive} from './lock.mjs';
// One build's FPS session: `node tools/dlss-cost/run.mjs <baseline|fixed> <port>`.
// Installs the build's jar into the copy profile only, launches it detached, then runs
// visit 1 (native, CNN Q, Transformer Q), visit 2 (reverse order) and at most one extra pass per
// config. Each pass: title idle gate, DH gate, Benchmark Mod route, mode proof, 640 px crop.
// Profiling stays off for every pass here (the copy proof runs separately).
import fs from 'node:fs';
import {quickCaptures} from './quick-capture.mjs';
import {
	root, scratch, evidence, profile, jarName, sceneWorld, delay, save, ps, client, launch, quit, idleGate,
	dhSnapshot, sameSnapshot, sameDurable, dhGeneratorPlan, logSize, dhLogSince, listResults, readResult,
	capframexCsv, metrics, contentCheck, passKey,
} from './lib.mjs';

const [build, portArg, selection = 'forward'] = process.argv.slice(2);
const port = Number(portArg);
const quick=selection==='quick';
let bmSettingsOriginal=null;
const EXPECTED = {b3: ps(`(Get-FileHash -LiteralPath '${root}/build/libs/${jarName}' -Algorithm SHA256).Hash`)};
const CONFIGS = {
 native: {upscaler:'off',w:2560,h:1440},
 'q-k': {upscaler:'dlss',quality:'quality',preset:11,w:1707,h:960},
 'b-k': {upscaler:'dlss',quality:'balanced',preset:11,w:1485,h:835},
 'p-m': {upscaler:'dlss',quality:'performance',preset:13,w:1280,h:720},
 'up-l': {upscaler:'dlss',quality:'ultra_performance',preset:12,w:853,h:480},
 'q-e': {upscaler:'dlss',quality:'quality',preset:5,w:1707,h:960},
 'b-e': {upscaler:'dlss',quality:'balanced',preset:5,w:1485,h:835},
 'p-e': {upscaler:'dlss',quality:'performance',preset:5,w:1280,h:720},
 'up-e': {upscaler:'dlss',quality:'ultra_performance',preset:5,w:853,h:480},
 'native-qsize': {upscaler:'off',w:1707,h:960},
};
const ORDER = Object.keys(CONFIGS);
const shaderless = selection.startsWith('shaderless');
const rows = quick ? ['native','q-k','up-l','native-qsize'] : selection==='validate' ? ['native','q-k'] : shaderless ? ['native','q-k','q-e','up-l'] : ORDER;
const group = quick ? 'quick' : selection==='validate' ? 'validation' : shaderless ? 'shaderless' : build;
let ownedPid=0, ownedClient=null;
let sessionStarted=0;
const jar = `${root}/build/libs/${jarName}`;
const passesFile = `${evidence}/passes-b3.json`;
const passes = fs.existsSync(passesFile) ? JSON.parse(fs.readFileSync(passesFile,'utf8')) : [];
const persist = () => save(`${evidence}/passes-${build}.json`, passes);
const log = (...args) => console.log(new Date().toISOString(), ...args);

function installJar() {
	const dest = `${profile}/mods/${jarName}`;
	fs.copyFileSync(jar, dest);
	const hash = ps(`(Get-FileHash -LiteralPath '${dest}' -Algorithm SHA256).Hash`);
	if (hash !== EXPECTED[build]) throw new Error(`Installed ${build} jar hash mismatch: ${hash}`);
	return hash;
}

// Round 17 mode proof, extended with the preset hint and the secondary window check.
function modeProof(spec, first, last, current, index) {
	const rx = last.plugins.reflexium, firstRx = first.plugins.reflexium;
	const active = spec.upscaler !== 'off';
	const expected = {w:spec.w,h:spec.h};
	const checks = {
		world_loaded: first.world_loaded && last.world_loaded,
		screen_none: first.screen === 'none' && last.screen === 'none',
		not_paused: !last.paused,
		measuring_same_run: current.phase === 'MEASURING' && Number(current.runIndex) === index,
		no_last_error: !rx.last_error,
		upscaler_effective: rx.upscaler_effective === (active ? 'dlss' : 'off'),
		upscaling_active: rx.upscaling_active === active,
		render_size: rx.render_size.w === expected.w && rx.render_size.h === expected.h,
		evaluated_counter: active ? rx.evaluated_frames > firstRx.evaluated_frames : rx.evaluated_frames === firstRx.evaluated_frames,
		preset_hint: spec.preset ? rx.dlss_preset_hint === spec.preset : true,
		window_primary_borderless: last.window.x === 0 && last.window.width === 2560 && last.window.height === 1440 && last.window.fullscreen.borderless && !last.window.fullscreen.exclusive_fullscreen && last.window.fullscreen.display_id === 1,
        focused: last.focus.os_foreground && last.focus.os_input_focus,
		pack_expected: shaderless ? String(rx.pack).includes('no shader pack drawing') : String(rx.pack).includes('Euphoria'),
        immediate: rx.swapchain_present_mode === 'IMMEDIATE',
        reflex_on: rx.reflex_mode === 'ON',
        fg_off: rx.frame_generation === 'off',
		render_distance_20: rx.render_distance === 20,
	};
	return {checks, valid: Object.values(checks).every(Boolean)};
}

async function runPass(c, pid, config, pass, visit) {
	const spec = CONFIGS[config];
	if(Date.now()-sessionStarted>43*60000) throw Error('GPU session time limit reached');
	const key = passKey(group, config, pass);
	log(`pass ${key} (visit ${visit}) starting`);
	await c.rx('frame_generation', 'off');
	await c.rx('reflex_mode', 'on');
    await c.call('fullscreen',{fullscreen:1,activate:1});
	await c.rx('upscaler', spec.upscaler);
	if (spec.preset) {
		await c.rx('quality', spec.quality);
		await c.debugGet('/set', {key: 'dlss_preset', value: String(spec.preset)});
	}
	await delay(12000);
	const idle = await idleGate(c, pid);
	save(`${evidence}/idle/${key}.json`,idle);

	const genPlan = dhGeneratorPlan();
    const processesBefore = exclusive(pid);
	const dhBefore = dhSnapshot(sceneWorld);
	const logOffset = logSize();
	const before = new Set(listResults());
	const label = 'dlss-cost-' + key;
	for (let i = 0; i < 360; i++) {
		const state = await c.call('bm', {});
		if (['IDLE', 'FINISHED', 'ABORTED'].includes(state.phase)) break;
		await delay(1000);
	}
	await c.call('bm', {scene: 'benchmark-route', label});

	const crop = `${evidence}/crops/${key}.png`;
	fs.mkdirSync(`${evidence}/crops`, {recursive: true});
	let captured = null, everMeasuring = false, dhCrop = null, dhEnd = null;
	for (let count = 0; count < 480; count++) {
		const state = await c.call('bm', {});
		if (state.phase === 'MEASURING') {
			everMeasuring = true;
			const index = Number(state.runIndex);
			// Result run 1 (the first of the reported pair) is state index 2.
			if (index === (quick?1:2) && !captured) {
				const target = BigInt(state.startedNanos) + 21_000_000_000n;
				const remaining = Number(target - process.hrtime.bigint()) / 1e6;
				if (remaining > 1000 && remaining < 24000) await delay(remaining - 100);
				else if (Number(state.serverTick) < 460) await delay((480 - Number(state.serverTick)) * 50 - 100);
				const first = await c.call('status');
				const shot = await c.debugGet('/screenshot', {path: crop, gui: '1', crop_width: config==='native-qsize'?'427':'640', crop_height: config==='native-qsize'?'240':'360'});
				dhCrop = dhSnapshot(sceneWorld);
				await delay(250);
				const last = await c.call('status');
				const current = await c.call('bm', {});
				const mode = modeProof(spec, first, last, current, index);
				captured = {index, first, last, shot, bm: current, mode};
				log(`pass ${key} crop taken, mode proof ${mode.valid}`);
				// Leave the render thread alone until this measured run finishes (round 17).
				await delay(23000);
			}
		}
		// Snapshot while the world is still open, before DH checkpoints on unload.
		if (everMeasuring && !dhEnd && (state.phase === 'FINISHED' || state.phase === 'IDLE')) dhEnd = dhSnapshot(sceneWorld);
		if (everMeasuring && (state.phase === 'FINISHED' || state.phase === 'IDLE')) break;
		if (state.phase === 'ABORTED') throw new Error('Benchmark Mod aborted ' + label);
		await delay(500);
	}
	if (!captured || !dhCrop || !dhEnd) throw new Error('No crop or DH end snapshot for ' + key);

	let file;
	for (let i = 0; i < 60; i++) {
		file = listResults().filter(f => !before.has(f)).find(f => readResult(f).label === label);
		if (file) break;
		await delay(500);
	}
	if (!file) throw new Error('Missing Benchmark Mod result ' + label);
	const result = readResult(file);
	const dhLog = dhLogSince(logOffset);
	// Gate: DH generation disabled; durable LOD files unchanged from before the route to the end of
	// the measured window; no SQLite companion or file change between the crop and the window end;
	// no generation log lines since the pass started.
	const processesAfter = exclusive(pid);
	const dhGate = genPlan === 'DISABLED' && sameDurable(dhBefore, dhEnd) && sameSnapshot(dhCrop, dhEnd) && dhLog.count === 0;

	const pair = quick?result.runs.slice(0,1):result.runs.slice(1, 3);
	const frameMs = pair.flatMap(r => r.frames.ms);
	const pooled = metrics(frameMs);
	const perRun = pair.map(r => ({bm_avg_fps: r.summary.avgFps, bm_low1_fps: r.summary.low1Fps, bm_low01_fps: r.summary.low01Fps, ...metrics(r.frames.ms)}));
	const csvDir = `${evidence}/capframex-all`;
	fs.mkdirSync(csvDir, {recursive: true});
	fs.writeFileSync(`${csvDir}/${key}.csv`, capframexCsv(pair));
	const bmDir = `${evidence}/bmresults`;
	fs.mkdirSync(bmDir, {recursive: true});
	fs.copyFileSync(file, `${bmDir}/${key}.bmresult.json.gz`);

	const content = contentCheck([crop])[0];
	const gateFlags = {
		invalid: result.flags.invalid, load_timeout: result.flags.loadTimeout, focus_lost: result.flags.focusLost,
		backend_vulkan: result.environment.gpu.backend === 'Vulkan',
		settle_ready: pair.every(r => r.settleRelease === 'ready' && !r.settleTimeout),
	};
	const valid = !gateFlags.focus_lost && captured.mode.valid && content.content_valid && dhGate && idle.passed
		&& !gateFlags.invalid && !gateFlags.load_timeout && gateFlags.backend_vulkan && gateFlags.settle_ready;
	const record = {
		key, build, config, pass, visit, label, bm_file: `${key}.bmresult.json.gz`,
		idle: {cpu_cv: idle.cpu_cv, gpu_range: idle.gpu_range},
		dh: {generator_plan: genPlan, before: {files: dhBefore.files}, crop: {files: dhCrop.files}, end: {files: dhEnd.files},
			durable_unchanged_before_to_end: sameDurable(dhBefore, dhEnd), unchanged_crop_to_end: sameSnapshot(dhCrop, dhEnd),
			log_generation_lines: dhLog.count, log_sample: dhLog.sample, gate_valid: dhGate},
		processesBefore, processesAfter, mode: captured.mode,
		mode_status: {upscaler_effective: captured.last.plugins.reflexium.upscaler_effective,
			render_size: captured.last.plugins.reflexium.render_size,
			preset_hint: captured.last.plugins.reflexium.dlss_preset_hint,
			evaluated_frames: [captured.first.plugins.reflexium.evaluated_frames, captured.last.plugins.reflexium.evaluated_frames],
			status_line: captured.last.plugins.reflexium.status_line,
			window: captured.last.window},
		content, gate_flags: gateFlags, valid,
		pooled, per_run: perRun,
		avg_fps: pooled.avg_fps, low1_fps: pooled.low1_fps, low01_fps: pooled.low01_fps,
	};
	save(`${evidence}/proofs/${key}-proof.json`, {record, crop_samples: captured});
	passes.push(record);
	persist();
	log(`pass ${key} done: avg ${pooled.avg_fps.toFixed(1)} low1 ${pooled.low1_fps.toFixed(1)} low0.1 ${pooled.low01_fps.toFixed(1)} valid=${valid} dh=${dhGate} mode=${captured.mode.valid} content=${content.content_valid}`);
	return record;
}

const spread = (a, b) => 100 * (Math.max(a, b) / Math.min(a, b) - 1);

async function main() {
    await acquire('timed');
    sessionStarted=Date.now();
    exclusive();
	if(quick){
  const settings=process.env.USERPROFILE+'/Documents/Benchmark Mod RxRound17/settings.json';
  bmSettingsOriginal=fs.readFileSync(settings,'utf8');save(evidence+'/quick-bm-settings-original.json',JSON.parse(bmSettingsOriginal.replace(/^\uFEFF/,'')));
  fs.writeFileSync(settings,JSON.stringify({...JSON.parse(bmSettingsOriginal.replace(/^\uFEFF/,'')),measuredRuns:1},null,2));
  process.env.RX_VIEWPORT_DIAGNOSTIC='1';process.env.RX_FOCUSABLE='1';
 }
 installJar();
	log(`installed ${build} jar into copy profile`);
	const pid = await launch(port, `dlss-cost-${build}`, jar);
	const c = client(port);
    ownedPid=pid; ownedClient=c;
	log(`client pid ${pid} at title`);
	await c.rx('shader_pack', 'ComplementaryReimagined_r5.9.3 + EuphoriaPatches_1.10.5');
    await c.rx('dlss_model','transformer');
    await c.rx('vsync','0');
    await c.rx('shaders',shaderless?'0':'1');
	await delay(12000);

	const visit1 = (selection.endsWith('reverse') || selection.startsWith('extra:')?[]:rows).map(cfg => [cfg, 1, 1]);
	const visit2 = (selection.endsWith('reverse')?[...rows].reverse():[]).map(cfg => [cfg, 2, 2]);
	for (const [cfg, pass, visit] of [...visit1, ...visit2]) await runPass(c, pid, cfg, pass, visit);
	if(selection.startsWith('extra:')) {
		const cfg=selection.slice(6);
		if(!CONFIGS[cfg])throw Error('Unknown extra row '+cfg);
		await runPass(c,pid,cfg,3,3);
	}

	persist();
	if(quick)await quickCaptures(c,pid);
 log('raw passes saved; quitting owned client');
	await quit(c, pid);
    ownedPid=0;
    release();
	log('done');
}

if (!process.argv.includes('--execute')) { console.log('DRY RUN: run prepared; no lock, launch, or HTTP call. Later live run requires --execute.'); process.exit(0); }
main().catch(error => {
	console.error('RUN FAILED', error.stack || error);
	process.exitCode = 1;
}).finally(async()=>{
    if(ownedPid){try{await quit(ownedClient,ownedPid);ownedPid=0;}catch(e){console.error('cleanup failed; reservation retained',e.message);}}
    if(!ownedPid){if(bmSettingsOriginal!==null)fs.writeFileSync(process.env.USERPROFILE+'/Documents/Benchmark Mod RxRound17/settings.json',bmSettingsOriginal);release();}
});
