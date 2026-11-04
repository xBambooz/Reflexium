// Run only while owning the GPU lock and this worktree's harness client.
import fs from 'node:fs';
import path from 'node:path';
const port = Number(process.argv[2]);
if (!Number.isInteger(port) || port < 25700 || port > 25729) throw new Error('worker A harness port required');
const session = JSON.parse(fs.readFileSync(`C:/Users/Ryan/source/repos/mc-agent-harness/instances/${port}/session.json`, 'utf8').replace(/^\uFEFF/, ''));
const dir = path.resolve('docs/validation/iq-issues-2026-10-30');
async function call(endpoint, body) {
	const response = await fetch(`http://127.0.0.1:${port}/${endpoint}`, {
		method: body ? 'POST' : 'GET',
		headers: {Authorization: `Bearer ${session.token}`, 'Content-Type': 'application/json'},
		body: body ? JSON.stringify(body) : undefined,
	});
	const result = await response.json();
	if (!response.ok || result.ok === false) throw new Error(JSON.stringify(result));
	return result;
}
const status = await call('status');
if (path.resolve(status.game_directory).toLowerCase() !== path.resolve('run').toLowerCase()) throw new Error('wrong worktree');
const lock = JSON.parse(fs.readFileSync('C:/Users/Ryan/source/repos/Reflexium-gpu.lock', 'utf8').replace(/^\uFEFF/, ''));
if (lock.owner !== 'worker-A') throw new Error('worker A does not own GPU lock');
const sequence = call('capture_sequence', {dir: `${dir}/final-motion-sequence`, count: 8, interval_ms: 100, dyaw: 15, ticks: 120});
await new Promise(resolve => setTimeout(resolve, 300));
const capture = await call('reflexium', {capture: 'final-motion', dir});
fs.writeFileSync(`${dir}/final-motion-result.json`, JSON.stringify(capture, null, 2));
fs.writeFileSync(`${dir}/final-motion-sequence.json`, JSON.stringify(await sequence, null, 2));
console.log('Saved motion inputs and simultaneous pan sequence');
