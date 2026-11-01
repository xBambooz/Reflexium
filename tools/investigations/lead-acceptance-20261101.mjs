// Final acceptance of one built jar: Testing Instance launch, then the dev client with the exact pack.
// Secondary monitor, never focused, muted. Refuses to start while Ryan's real Fabric 26.3 client runs.
import fs from 'node:fs';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {dispatch} from 'file:///C:/Users/Ryan/source/repos/mc-agent-harness/tools/mcp-server.mjs';

const root = path.resolve(import.meta.dirname, '../..').replaceAll('\\', '/');
const out = root + '/docs/validation/lead-acceptance-20261101';
const lock = 'C:/Users/Ryan/source/repos/Reflexium-gpu.lock';
const profile = process.env.APPDATA.replaceAll('\\', '/') + '/ModrinthApp/profiles/26.3 Testing Instance';
const expected = process.argv[2];
const delay = ms => new Promise(r => setTimeout(r, ms));
const ps = c => execFileSync('powershell.exe', ['-NoProfile', '-Command', c], {encoding: 'utf8'}).trim();
const save = (name, value) => fs.writeFileSync(`${out}/${name}`, JSON.stringify(value, null, 2));
fs.mkdirSync(out, {recursive: true});

function javaProcesses() {
	const raw = ps("@(Get-CimInstance Win32_Process | Where-Object {$_.Name -match '^javaw?\\.exe$'} | Select-Object ProcessId,CommandLine) | ConvertTo-Json -Compress");
	const list = raw ? JSON.parse(raw) : [];
	return Array.isArray(list) ? list : [list];
}
function refuseIfRealProfile() {
	if (javaProcesses().some(p => /ModrinthApp\\profiles\\Fabric 26\.3[\\"]/.test(p.CommandLine ?? ''))) throw Error('Real profile client is running');
}
function refuseIfClient() {
	refuseIfRealProfile();
	if (javaProcesses().some(p => /KnotClient|devlaunchinjector\.Main/.test(p.CommandLine ?? ''))) throw Error('Another client is running');
}

async function tool(name, args) {
	const r = await dispatch({jsonrpc: '2.0', id: 1, method: 'tools/call', params: {name, arguments: args}});
	const v = JSON.parse((r.result?.content ?? r.content).find(x => x.type === 'text').text);
	if (r.result?.isError || v.ok === false) throw Error(JSON.stringify(v));
	return v;
}

async function testingInstance() {
	const port = 25960, debugPort = port + 1;
	const jar = `${profile}/mods/Reflexium-1.2.0-alpha-fabric-26.3.jar`;
	const hash = ps(`(Get-FileHash -LiteralPath '${jar}' -Algorithm SHA256).Hash`);
	if (hash !== expected) throw Error(`Testing Instance jar ${hash} is not ${expected}`);
	const template = fs.readFileSync(process.env.TEMP + '/opencode/modrinth-java.args', 'utf8')
		.replace(/-Dreflexium.debugPort=\d+/, '-Dreflexium.debugPort=' + debugPort)
		.replace(/(--gameDir\s*\n)[^\n]+/, '$1' + JSON.stringify(profile));
	const argsFile = process.env.TEMP + '/opencode/lead-acceptance.args';
	fs.writeFileSync(argsFile, `-Dagentharness.port=${port}\n-Dagentharness.monitor=secondary\n-Dagentharness.width=1920\n-Dagentharness.height=1080\n-Dagentharness.focusable=false\n` + template);
	const harness = 'C:/Users/Ryan/source/repos/mc-agent-harness/build/libs/agentharness-1.0.0.jar';
	const pid = JSON.parse(ps(`& '${root}/tools/launch-modrinth-profile.ps1' -JavaArgsFile '${argsFile}' -HarnessJar '${harness}'`)).pid;
	const call = async (endpoint, body) => {
		const token = fs.readFileSync(profile + '/.agentharness-token', 'utf8').trim();
		const r = await fetch(`http://127.0.0.1:${port}/${endpoint}`, {method: body ? 'POST' : 'GET',
			headers: {Authorization: `Bearer ${token}`, 'Content-Type': 'application/json'},
			body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(150000)});
		const data = await r.json();
		if (!r.ok || data.ok === false) throw Error(JSON.stringify(data));
		return data;
	};
	try {
		let status;
		for (let i = 0; i < 150; i++) {
			try { status = await call('status'); if (status.screen.includes('Title')) break; } catch { /* still starting */ }
			await delay(1000);
		}
		if (status?.pid !== pid) throw Error(`Harness answered for ${status?.pid}, launched ${pid}`);
		await call('join', {world: 'Reflexium Acceptance', timeout_s: 120});
		await delay(12000);
		status = await call('status');
		const debug = await (await fetch(`http://127.0.0.1:${debugPort}/status`)).json();
		save('testing-status.json', status);
		save('testing-debug-status.json', debug);
		await fetch(`http://127.0.0.1:${debugPort}/screenshot?` + new URLSearchParams({path: `${out}/testing-world.png`, gui: '1'}));
		fs.copyFileSync(profile + '/logs/latest.log', `${out}/testing-latest.log`);
		return {hash, pid, screen: status.screen, focus: status.focus, last_error: debug.last_error ?? null,
			upscaler_active: debug.active ?? debug.upscaler_active ?? null, reflex_mode: debug.reflex_mode ?? status.plugins?.reflexium?.reflex_mode ?? null};
	} finally {
		await call('quit', {exit: true});
		for (let i = 0; i < 60 && ps(`if(Get-Process -Id ${pid} -ErrorAction SilentlyContinue){'alive'}`); i++) await delay(1000);
		if (ps(`if(Get-Process -Id ${pid} -ErrorAction SilentlyContinue){'alive'}`)) throw Error('Testing Instance client still alive');
	}
}

async function packClient() {
	const port = 25962;
	const call = (endpoint, params = {}) => tool('mc_call', {port, endpoint, params});
	const set = (key, value) => call('reflexium', {key, value: String(value)});
	process.env.MC_HARNESS_JVM_ARGS = JSON.stringify(['-Dreflexium.streamline=true']);
	await tool('mc_launch', {project: root, port, monitor: 'secondary', width: 1920, height: 1080, run_directory: root + '/run',
		gradle_properties: {rxDebug: String(port + 1), rxIqUser: 'RxLeadCheck', reflexiumInstallDir: root + '/build/install'}});
	try {
		await call('join', {world: 'Reflexium Vitrail Smoke', timeout_s: 120});
		for (const command of ['gamemode survival @a', 'tick unfreeze', 'time set 6000', 'weather clear']) await call('command', {command});
		await set('shader_pack', 'ComplementaryReimagined_r5.9.3 + EuphoriaPatches_1.10.5');
		for (const [k, v] of Object.entries({upscaler: 'dlss', quality: 'ultra_performance', dlss_model: 'transformer', dlss_preset: 12, frame_generation: 'off', reflex_interval_us: 16667})) await set(k, v);
		await call('wait', {ms: 6000});
		await call('command', {command: 'setblock 200 150 24 minecraft:stone'});
		await call('command', {command: 'setblock 200 151 24 minecraft:oak_sign[rotation=8]{front_text:{messages:["READ ME","Sharp","0123456789","Reflexium"]}}'});
		await call('camera', {x: 200.5, y: 151, z: 20, yaw: 0, pitch: 0});
		await call('wait', {ms: 3000});
		const signs = (await tool('mc_status', {port})).plugins.reflexium;
		await call('screenshot', {path: `${out}/pack-signs.png`});
		await call('camera', {x: 200.5, y: 151, z: 1.5, yaw: 0, pitch: 3});
		await call('wait', {ms: 1500});
		await call('key', {name: 'key.forward', down: true});
		await call('wait', {ms: 800});
		const walking = (await tool('mc_status', {port})).plugins.reflexium;
		await call('screenshot', {path: `${out}/pack-walking.png`});
		await call('key', {name: 'key.forward', down: false});
		save('pack-signs-status.json', signs);
		save('pack-walking-status.json', walking);
		const pick = s => ({last_error: s.last_error ?? null, upscaling_active: s.upscaling_active,
			drawn_view_has_view_effects: s.observed?.drawn_view_has_view_effects, drawn_projection_has_view_effects: s.observed?.drawn_projection_has_view_effects,
			world_text_nodes: s.observed?.world_text_nodes, world_text_prepared: s.observed?.world_text_prepared, world_text_limitation: s.observed?.world_text_limitation,
			world_text_pack_levels: s.observed?.world_text_pack_levels, world_text_pack_levels_source_verified: s.observed?.world_text_pack_levels_source_verified,
			world_text_pack_levels_applied: s.observed?.world_text_pack_levels_applied, reflex_mode: s.reflex_mode});
		return {signs: pick(signs), walking: pick(walking)};
	} finally {
		await call('quit', {exit: true});
	}
}

refuseIfClient();
fs.writeFileSync(lock, JSON.stringify({owner: 'lead-acceptance', pid: process.pid, started: new Date().toISOString(), purpose: 'capture'}), {flag: 'wx'});
const result = {};
try {
	if (process.argv[3] !== 'pack-only') {
		result.testing = await testingInstance();
		refuseIfClient();
	}
	result.pack = await packClient();
	for (let i = 0; i < 60 && javaProcesses().some(p => /KnotClient|devlaunchinjector\.Main/.test(p.CommandLine ?? '')); i++) await delay(1000);
	result.client_left = javaProcesses().some(p => /KnotClient|devlaunchinjector\.Main/.test(p.CommandLine ?? ''));
} finally {
	save('result.json', result);
	if (JSON.parse(fs.readFileSync(lock, 'utf8')).owner === 'lead-acceptance') fs.unlinkSync(lock);
}
console.log(JSON.stringify(result, null, 1));
process.exit(0);
