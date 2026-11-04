// Shared harness helpers for the DLSS cost measurement (docs/specs/DLSS_COST_PLAN.md).
// Copies the round 17 method: detached javaw launch through tools/launch-modrinth-profile.ps1,
// harness and debug ports per launch, title idle gate, Benchmark Mod route passes, proofs.
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import {execFileSync} from 'node:child_process';

export const root = 'C:/Users/Ryan/source/repos/Reflexium';
export const profile = process.env.APPDATA + '/ModrinthApp/profiles/Fabric 26.3 RxRound17';
export const bmRoot = 'C:/Users/Ryan/Documents/Benchmark Mod RxRound17';
export const resultsDir = bmRoot + '/results';
export const sceneWorld = bmRoot + '/scenes/benchmark-route/world';
export const prepWorld = profile + '/saves/Round17 Benchmark Preparation';
export const evidence = root + '/docs/validation/dlss-cost-2026-10-26';
export const scratch = root + '/build/dlss-cost';
export const jarName = 'Reflexium-1.2.0-alpha-fabric-26.3.jar';
export const PROTECTED_PID = 25084;
export const delay = ms => new Promise(r => setTimeout(r, ms));
export const save = (file, value) => {
	fs.mkdirSync(path.dirname(file), {recursive: true});
	fs.writeFileSync(file, JSON.stringify(value, null, 2));
};
export const ps = command => execFileSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', command], {encoding: 'utf8', windowsHide: true}).trim();

// Harness endpoints on `port`; the debug server listens on port + 1 (round 17 convention).
export function client(port) {
	const token = () => fs.readFileSync(path.join(profile, '.agentharness-token'), 'utf8').trim();
	const call = async (endpoint, params, timeout = 30000) => {
		const res = await fetch(`http://127.0.0.1:${port}/${endpoint}`, {
			method: params ? 'POST' : 'GET',
			headers: {Authorization: `Bearer ${token()}`, 'Content-Type': 'application/json'},
			body: params ? JSON.stringify(params) : undefined,
			signal: AbortSignal.timeout(timeout),
		});
		const body = await res.json();
		if (!res.ok || body.ok === false) throw new Error(JSON.stringify(body));
		return body;
	};
	const rx = (key, value) => call('reflexium', {key, value: String(value)});
	const debugGet = async (route, query, timeout = 20000) => {
		const res = await fetch(`http://127.0.0.1:${port + 1}${route}?${new URLSearchParams(query)}`, {signal: AbortSignal.timeout(timeout)});
		const body = await res.json();
		if (!res.ok || body.ok === false) throw new Error(JSON.stringify(body));
		return body;
	};
	return {port, call, rx, debugGet};
}

export function processInfo(pid) {
	return JSON.parse(ps(`Get-CimInstance Win32_Process -Filter 'ProcessId=${pid}' | Select-Object ProcessId,Name,CommandLine | ConvertTo-Json -Compress`) || 'null');
}

// Detached launch via the repo launcher (Win32_Process.Create, javaw). Mirrors round 17's launch().
export async function launch(port, label, jar) {
	const config = path.join(profile, 'config/reflexium.properties');
	fs.writeFileSync(config, fs.readFileSync(config, 'utf8')
		.replace(/^upscaler=.*$/mg, 'upscaler=dlss').replace(/^upscale_quality=.*$/mg, 'upscale_quality=quality')
		.replace(/^frame_generation=.*$/mg, 'frame_generation=off'));
	const opts = path.join(profile, 'options.txt');
	fs.writeFileSync(opts, fs.readFileSync(opts, 'utf8')
		.replace(/^preferredGraphicsBackend:.*$/mg, 'preferredGraphicsBackend:"vulkan"')
		.replace(/^soundCategory_master:.*$/mg, 'soundCategory_master:0.0')
		.replace(/^fullscreen:.*$/mg, 'fullscreen:false'));
	const template = fs.readFileSync(process.env.TEMP + '/opencode/round17-profile.args', 'utf8')
		.replace(/-Dagentharness.port=\d+/, '-Dagentharness.port=' + port)
		.replace(/-Dreflexium.debugPort=\d+/, '-Dreflexium.debugPort=' + (port + 1))
		.replace('build/validation/round17/hs_err', 'build/dlss-cost/hs_err');
	const args = process.env.TEMP + '/opencode/' + label + '.args';
	fs.writeFileSync(args, template);
	const result = JSON.parse(ps(`& '${root}/tools/launch-modrinth-profile.ps1' -Profile 'Fabric 26.3 RxRound17' -JavaArgsFile '${args}' -HarnessJar '${profile}/agentharness-1.0.0.jar'`));
	const info = processInfo(result.pid);
	if (info.Name !== 'javaw.exe' || !info.CommandLine.includes('agentharness-1.0.0.jar') || !info.CommandLine.includes('Fabric 26.3 RxRound17') || result.pid === PROTECTED_PID)
		throw new Error('Launch ownership or console mismatch: ' + label);
	const client_ = client(port);
	for (let i = 0; i < 120; i++) {
		try {
			const status = await client_.call('status', undefined, 3000);
			if (status.pid !== result.pid || path.resolve(status.game_directory) !== path.resolve(profile)) throw new Error('Session ownership mismatch');
			if (status.screen.includes('Title')) {
				save(path.join(scratch, 'launches', label + '-title.json'), status);
				return result.pid;
			}
		} catch (error) {
			if (!processInfo(result.pid)) throw error;
		}
		await delay(1000);
	}
	throw new Error('Title timeout: ' + label);
}

export async function quit(client_, pid) {
	const status = await client_.call('status');
	const info = processInfo(pid);
	if (status.pid !== pid || path.resolve(status.game_directory) !== path.resolve(profile) || !info?.CommandLine.includes('agentharness-1.0.0.jar') || !info.CommandLine.includes('Fabric 26.3 RxRound17') || pid === PROTECTED_PID)
		throw new Error('Shutdown ownership mismatch');
	await client_.call('quit', {exit: true});
	for (let i = 0; i < 60; i++) {
		if (!processInfo(pid)) return;
		await delay(1000);
	}
	throw new Error('Owned shutdown timed out');
}

// Title-screen idle gate (round 17): CPU ms rate CV below 0.10 and GPU utilisation range within 5 points.
export async function idleGate(client_, pid) {
	let status = await client_.call('status');
	// A loaded world goes to the title screen through `quit` without exit (round 17 harness note).
	// Other screens (for example the Benchmark Mod results screen) get Escape presses.
	for (let i = 0; i < 12 && !status.screen.includes('Title'); i++) {
		if (status.world_loaded) await client_.call('quit', {exit: false});
		else await client_.call('key', {scancode: 41, keycode: 27});
		await delay(1500);
		status = await client_.call('status');
	}
	if (status.world_loaded || !status.screen.includes('Title')) throw new Error('Idle gate requires title: ' + status.screen);
	const samples = [];
	for (let i = 0; i < 75; i++) {
		const cpu = Number(ps(`(Get-Process -Id ${pid}).TotalProcessorTime.TotalMilliseconds`));
		const gpu = ps('& nvidia-smi --query-gpu=utilization.gpu,clocks.gr,power.draw --format=csv,noheader,nounits');
		samples.push({time: Date.now(), cpu_ms: cpu, gpu});
		if (samples.length >= 6) {
			const last = samples.slice(-6);
			const rates = last.slice(1).map((s, j) => (s.cpu_ms - last[j].cpu_ms) / (s.time - last[j].time));
			const mean = rates.reduce((a, b) => a + b) / rates.length;
			const cv = Math.sqrt(rates.reduce((a, b) => a + (b - mean) ** 2, 0) / rates.length) / mean;
			const gs = last.map(s => Number(s.gpu.split(',')[0]));
			const gpuRange = Math.max(...gs) - Math.min(...gs);
			if (cv < 0.10 && gpuRange <= 5) return {samples: last, cpu_cv: cv, gpu_range: gpuRange, passed: true};
		}
		await delay(2000);
	}
	throw new Error('Title load did not settle');
}

// DH LOD storage in a world: any file or folder whose path contains DistantHorizons under dimensions/ or data/.
export function dhSnapshot(worldDir) {
	const files = [];
	const walk = (dir, rel) => {
		if (!fs.existsSync(dir)) return;
		for (const entry of fs.readdirSync(dir, {withFileTypes: true})) {
			const full = path.join(dir, entry.name), relative = rel + '/' + entry.name;
			if (entry.isDirectory()) walk(full, relative);
			else if (/DistantHorizons|Distant_Horizons/i.test(relative)) {
				const stat = fs.statSync(full);
				// SQLite -wal/-shm/-journal companions exist only while the world is open.
				files.push({rel: relative, size: stat.size, mtime_ms: Math.round(stat.mtimeMs), transient: /-(wal|shm|journal)$/.test(relative)});
			}
		}
	};
	walk(path.join(worldDir, 'dimensions'), 'dimensions');
	walk(path.join(worldDir, 'data'), 'data');
	files.sort((a, b) => a.rel.localeCompare(b.rel));
	return {files, count: files.length, bytes: files.reduce((a, f) => a + f.size, 0)};
}
// Same files with the same sizes (mtimes are excluded: DH checkpoints on world unload).
export const sameSnapshot = (a, b) => JSON.stringify(a.files.map(f => [f.rel, f.size])) === JSON.stringify(b.files.map(f => [f.rel, f.size]));
export const sameDurable = (a, b) => {
	const keep = s => s.files.filter(f => !f.transient).map(f => [f.rel, f.size]);
	return JSON.stringify(keep(a)) === JSON.stringify(keep(b));
};

export function dhGeneratorPlan() {
	const text = fs.readFileSync(path.join(profile, 'config/DistantHorizons.toml'), 'utf8');
	return text.match(/generatorPlan\s*=\s*"([^"]+)"/)?.[1] ?? 'missing';
}

export function logSize() {
	return fs.existsSync(path.join(profile, 'logs/latest.log')) ? fs.statSync(path.join(profile, 'logs/latest.log')).size : 0;
}
// DH log lines written since `offset` that mention generation or tasks (count plus a short sample).
export function dhLogSince(offset) {
	const file = path.join(profile, 'logs/latest.log');
	const buffer = fs.readFileSync(file).subarray(offset);
	const lines = buffer.toString('utf8').split(/\r?\n/)
		.filter(line => /distant|\bDH\b|lod/i.test(line) && /generat|task/i.test(line) && !/rendering definition/i.test(line));
	return {count: lines.length, sample: lines.slice(0, 5)};
}

export function listResults(dir = resultsDir) {
	const out = [];
	const walk = d => {
		for (const e of fs.readdirSync(d, {withFileTypes: true})) {
			const p = path.join(d, e.name);
			if (e.isDirectory()) walk(p);
			else if (p.endsWith('.bmresult.json.gz')) out.push(p);
		}
	};
	if (fs.existsSync(dir)) walk(dir);
	return out;
}
export function readResult(file) {
	return JSON.parse(zlib.gunzipSync(fs.readFileSync(file)));
}

// CapFrameX-format CSV from Benchmark Mod frame times, using the same row layout as the mod's own
// ResultStore.exportCapFrameX (Benchmark Mod source). Pooled runs are offset in time like the mod does.
export function capframexCsv(runs) {
	const lines = ['Application,ProcessID,SwapChainAddress,Runtime,SyncInterval,PresentFlags,AllowsTearing,PresentMode,Dropped,TimeInSeconds,MsBetweenPresents,MsBetweenDisplayChange,MsInPresentAPI,MsUntilRenderComplete,MsUntilDisplayed,Comment'];
	let offset = 0;
	for (const run of runs) {
		const {t, ms} = run.frames;
		for (let i = 0; i < ms.length; i++) {
			const time = offset + t[i];
			lines.push(`Minecraft.exe,0,0x0,Unknown,0,0,0,Hardware Composed: Independent Flip,0,${time.toFixed(6)},${ms[i].toFixed(4)},${ms[i].toFixed(4)},0,0,0,"Benchmark Mod synthetic frametime export"`);
		}
		if (ms.length) offset += t.at(-1) + ms.at(-1) / 1000;
	}
	return lines.join('\n') + '\n';
}

// CapFrameX-style metrics from frame times in ms: average FPS = frames / seconds,
// 1% and 0.1% low = FPS of the mean of the slowest 1% / 0.1% of frames.
export function metrics(ms) {
	const n = ms.length, seconds = ms.reduce((a, b) => a + b, 0) / 1000;
	const slowest = [...ms].sort((a, b) => b - a);
	const low = fraction => {
		const k = Math.max(1, Math.round(n * fraction));
		return 1000 / (slowest.slice(0, k).reduce((a, b) => a + b, 0) / k);
	};
	return {frames: n, seconds, avg_fps: n / seconds, low1_fps: low(0.01), low01_fps: low(0.001)};
}

// PowerShell content check (System.Drawing) over one or more PNG crops.
export function contentCheck(files) {
	const script = root + '/tools/dlss-cost/content-check.ps1';
	const list = files.map(f => `'${f}'`).join(',');
	const parsed = JSON.parse(ps(`& '${script}' -Files @(${list}) | ConvertTo-Json -Depth 4`));
	return Array.isArray(parsed) ? parsed : [parsed];
}

export const passKey = (build, config, pass) => `${build}-${config}-pass${pass}`;
