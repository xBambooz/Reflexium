// Run only against worker A's isolated client while holding the shared GPU lock.
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';

const port = Number(process.argv[2]);
const panInputs = process.argv[3] === 'pan-inputs';
assert(port >= 25700 && port <= 25729);
assert.equal(createHash('sha256').update(fs.readFileSync('run/mods/iris-fabric-experimental-build-26.3.jar')).digest('hex'),
	'566bd7515cc1cb41320a6761932093c1c3ee813fc8cbfd2077e3d128746dc1c2', 'wrong Iris renderer');
const session = JSON.parse(fs.readFileSync(`C:/Users/Ryan/source/repos/mc-agent-harness/instances/${port}/session.json`, 'utf8').replace(/^\uFEFF/, ''));
const root = path.resolve('docs/validation/iq-issues-2026-10-30/continuation');
const raw = path.resolve('run/iq-continuation/signs');
fs.mkdirSync(root, {recursive: true});
fs.mkdirSync(raw, {recursive: true});
function owned() {
	const lock = JSON.parse(fs.readFileSync('C:/Users/Ryan/source/repos/Reflexium-gpu.lock', 'utf8').replace(/^\uFEFF/, ''));
	assert.equal(lock.owner, 'worker-A');
	assert(Date.now() - Date.parse(lock.started) < 40 * 60_000, 'capture session exceeded 40 minutes');
}
async function call(endpoint, body) {
	owned();
	const r = await fetch(`http://127.0.0.1:${port}/${endpoint}`, {
		method: body ? 'POST' : 'GET',
		headers: {Authorization: `Bearer ${session.token}`, 'Content-Type': 'application/json'},
		body: body ? JSON.stringify(body) : undefined,
		signal: AbortSignal.timeout(120_000),
	});
	const value = await r.json();
	assert(r.ok && value.ok !== false, JSON.stringify(value));
	return value;
}
const save = (name, value) => fs.writeFileSync(`${root}/${name}.json`, JSON.stringify(value, null, 2));
const set = (key, value) => call('reflexium', {key, value: String(value)});
async function status(name, expected) {
	const s = await call('status');
	assert.equal(path.resolve(s.game_directory).toLowerCase(), path.resolve('run').toLowerCase());
	const r = s.plugins.reflexium;
	assert.equal(r.last_error, '');
	assert.equal(r.upscaler_effective, expected);
	assert(Math.abs(s.camera.x - 200.5) < .01 && Math.abs(s.camera.y - 151) < .01);
	save(name, s);
	return s;
}

const initial = await call('status');
assert.equal(path.resolve(initial.game_directory).toLowerCase(), path.resolve('run').toLowerCase());
if (!initial.world_loaded) await call('join', {world: 'Reflexium Vitrail Smoke', timeout_s: 120});
await call('window', {monitor: 'secondary', x: 0, y: 0, width: 2560, height: 1440, behind: true});
await set('frame_generation', 'off');
await call('command', {command: 'gamemode spectator @a'});
await call('command', {command: 'tick freeze'});
if (!panInputs) {
	await call('command', {command: 'execute positioned 200.5 151 28 run kill @e[type=minecraft:horse,distance=..32]'});
	await call('command', {command: 'tick step 40'});
	for (const x of [199, 201]) for (const y of [152, 154]) {
		await call('command', {command: `data merge block ${x} ${y} 28 {front_text:{color:"white",has_glowing_text:${x === 201 ? '1b' : '0b'}}}`});
	}
}
for (const pack of [0, 1]) {
	await set('shaders', pack);
	for (const distance of [4, 8, 16, 24]) {
		for (const mode of ['off', 'native_aa', 'quality', 'ultra_performance']) {
			const name = `sign-p${pack}-d${distance}-${mode}`;
			if (panInputs && fs.existsSync(`${root}/${name}-input-pan-after.json`)) continue;
			await set('upscaler', mode === 'off' ? 'off' : 'dlss');
			if (mode !== 'off') await set('quality', mode);
			await call('camera', {x: 200.5, y: 151, z: 28 - distance, yaw: 0, pitch: 0});
			await set('omit_sign_text', false);
			await set('jitter', true);
			await call('wait', {ms: 10000});
			const before = await status(name, mode === 'off' ? 'off' : 'dlss');
			if (panInputs) {
				const sequence = call('capture_sequence', {dir: `${raw}/${name}-input-pan`, count: 20, interval_ms: 300, dyaw: 15, ticks: 100});
				await new Promise(resolve => setTimeout(resolve, 500));
				if (mode !== 'off') save(`${name}-moving-input`, await call('reflexium', {capture: `${name}-moving-input`, dir: raw}));
				save(`${name}-input-pan`, await sequence);
				const after = await call('status');
				assert.equal(after.plugins.reflexium.last_error, '');
				if (mode !== 'off') assert(after.plugins.reflexium.evaluated_frames > before.plugins.reflexium.evaluated_frames);
				save(`${name}-input-pan-after`, after);
				console.log(`Captured moving inputs ${name}`);
				continue;
			}
			for (const omit of [false, true]) {
				await set('omit_sign_text', omit);
				await call('wait', {ms: 1000});
				const arm = `${name}-${omit ? 'omitted' : 'normal'}`;
				await call('screenshot', {path: `${raw}/${arm}.png`});
				if (mode !== 'off') save(`${arm}-capture`, await call('reflexium', {capture: arm, dir: raw}));
			}
			await set('omit_sign_text', false);
			if (mode !== 'off') {
				await set('jitter', false);
				await call('wait', {ms: 10000});
				save(`${name}-no-jitter`, await call('reflexium', {capture: `${name}-no-jitter`, dir: raw}));
				await set('omit_sign_text', true);
				await call('wait', {ms: 10000});
				save(`${name}-no-jitter-omitted`, await call('reflexium', {capture: `${name}-no-jitter-omitted`, dir: raw}));
				await set('omit_sign_text', false);
				await set('jitter', true);
				await call('wait', {ms: 10000});
			}
			save(`${name}-static`, await call('capture_sequence', {dir: `${raw}/${name}-static`, count: 6, interval_ms: 150}));
			save(`${name}-pan`, await call('capture_sequence', {dir: `${raw}/${name}-pan`, count: 12, interval_ms: 300, dyaw: 15, ticks: 100}));
			const after = await call('status');
			assert.equal(after.plugins.reflexium.last_error, '');
			if (mode !== 'off') assert(after.plugins.reflexium.evaluated_frames > before.plugins.reflexium.evaluated_frames);
			save(`${name}-after`, after);
			console.log(`Captured ${name}`);
		}
	}
}
await set('omit_sign_text', false);
console.log(`Sign discriminating captures complete: ${raw}`);
