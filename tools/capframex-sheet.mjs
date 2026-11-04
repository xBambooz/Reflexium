// Renders docs/validation/dlss-light-pack-2026-10-26/capframex-sheet.png from the accepted CapFrameX
// CSVs of the final route batch. Metrics use MsBetweenPresents with the same functions as the driver.
// Usage: node tools/capframex-sheet.mjs
import fs from 'node:fs';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {metrics} from './dlss-cost/lib.mjs';

const evidence = process.env.RX_EVIDENCE || 'C:/Users/Ryan/source/repos/Reflexium/docs/validation/dlss-light-pack-2026-10-26';
const CONFIGS = [
	{config: 'native', label: 'Native', color: '#6b7280'},
	{config: 'cnn-q', label: 'DLSS CNN Quality (5)', color: '#2563eb'},
	{config: 'transformer-q', label: 'DLSS Transformer Quality (11)', color: '#ea580c'},
];
const ARTIFACT = '3E6CF91CDA7FEA653447297791541B945A2B3A149B47911FD66E805CA07450DF';
const SOURCE = 'round6-validation d2e029d, d6894cd, aa5879f';

function readFrames(file) {
	const [header, ...rows] = fs.readFileSync(file, 'utf8').trim().split(/\r?\n/);
	const names = header.split(',');
	const msIndex = names.indexOf('MsBetweenPresents'), tIndex = names.indexOf('TimeInSeconds');
	const ms = [], t = [];
	for (const row of rows) {
		const cols = row.split(',');
		ms.push(Number(cols[msIndex]));
		t.push(Number(cols[tIndex]));
	}
	return {ms, t};
}

const passesFile = path.join(evidence, 'passes-lightpack.json');
const allPasses = fs.existsSync(passesFile) ? JSON.parse(fs.readFileSync(passesFile, 'utf8')) : [];
const dirFiles = fs.existsSync(path.join(evidence, 'capframex')) ? fs.readdirSync(path.join(evidence, 'capframex')) : [];
const data = CONFIGS.map(cfg => {
	const wanted = new Set(allPasses.filter(p => p.config === cfg.config && p.accepted).map(p => `${p.key}.csv`));
	const files = dirFiles.filter(f => wanted.has(f)).sort();
	const passes = files.map(f => {
		const frames = readFrames(path.join(evidence, 'capframex', f));
		// metrics() also returns a frame count named `frames`, so keep the frametime data last.
		return {...metrics(frames.ms), file: f, pass: Number(f.match(/pass(\d)/)[1]), frames};
	});
	const mean = key => passes.length ? passes.reduce((a, p) => a + p[key], 0) / passes.length : null;
	const avgs = passes.map(p => p.avg_fps);
	const spread = avgs.length === 2 ? 100 * (Math.max(...avgs) / Math.min(...avgs) - 1) : null;
	const first = passes[0];
	const buckets = [];
	if (first) for (let i = 0; i < first.frames.ms.length; i++) {
		const b = Math.floor(first.frames.t[i] / 0.5);
		(buckets[b] ??= []).push(first.frames.ms[i]);
	}
	const series = buckets.map((vals, i) => ({x: i * 0.5, y: vals.reduce((a, v) => a + v, 0) / vals.length}));
	return {...cfg, passes, avg: mean('avg_fps'), low1: mean('low1_fps'), low01: mean('low01_fps'), spread, series};
});
const validity = {};
if (allPasses.length) {
	for (const p of allPasses) {
		const key = p.config;
		validity[key] ??= {runs: 0, mode: 0, content: 0, dh: 0, passes: []};
		const v = validity[key];
		v.runs++;
		if (p.mode.valid) v.mode++;
		if (p.content.content_valid) v.content++;
		// Durable DH gate: the raw snapshot also changes with the transient SQLite -wal companion.
		if (p.dh.generator_plan === 'DISABLED' && p.dh.durable_unchanged_before_to_end && p.dh.log_generation_lines === 0) v.dh++;
		v.passes.push({key: p.key, valid: p.valid});
	}
}
const summaryFile = path.join(evidence, 'summary-lightpack.json');
const summary = fs.existsSync(summaryFile) ? JSON.parse(fs.readFileSync(summaryFile, 'utf8')) : {};

const f0 = v => v == null ? '-' : v.toFixed(1);
function barChart() {
	const W = 760, H = 420, x0 = 60, y0 = 30, cw = 680, ch = 300;
	const groups = [['Average FPS', 'avg'], ['1% low FPS', 'low1'], ['0.1% low FPS', 'low01']];
	const values = data.flatMap(d => [d.avg, d.low1, d.low01]).filter(v => v != null);
	const max = Math.ceil(Math.max(...values) * 1.12 / 50) * 50;
	const y = v => y0 + ch - (v / max) * ch;
	let svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" font-family="Segoe UI, Arial" font-size="12">`;
	for (let t = 0; t <= max; t += max / 5) svg += `<line x1="${x0}" x2="${x0 + cw}" y1="${y(t)}" y2="${y(t)}" stroke="#e5e7eb"/><text x="${x0 - 8}" y="${y(t) + 4}" text-anchor="end" fill="#6b7280">${Math.round(t)}</text>`;
	const gw = cw / groups.length, bw = 38, gap = 6;
	groups.forEach(([title, key], gi) => {
		const gx = x0 + gi * gw + (gw - data.length * (bw + gap)) / 2;
		data.forEach((d, di) => {
			const v = d[key];
			if (v == null) return;
			const bx = gx + di * (bw + gap);
			svg += `<rect x="${bx}" y="${y(v)}" width="${bw}" height="${y0 + ch - y(v)}" fill="${d.color}" stroke="${d.color}"/>`;
			svg += `<text x="${bx + bw / 2}" y="${y(v) - 5}" text-anchor="middle" fill="#111827" font-size="10">${Math.round(v)}</text>`;
		});
		svg += `<text x="${x0 + gi * gw + gw / 2}" y="${y0 + ch + 22}" text-anchor="middle" font-weight="600">${title}</text>`;
	});
	svg += `<line x1="${x0}" x2="${x0 + cw}" y1="${y0 + ch}" y2="${y0 + ch}" stroke="#9ca3af"/></svg>`;
	return svg;
}

function frametimeChart() {
	const W = 760, H = 420, x0 = 60, y0 = 30, cw = 680, ch = 300;
	const series = data.filter(d => d.series.length);
	const maxX = Math.max(...series.map(d => d.series.at(-1).x));
	const maxY = Math.ceil(Math.max(...series.flatMap(d => d.series.map(s => s.y))) * 1.1);
	const X = x => x0 + (x / maxX) * cw, Y = y => y0 + ch - (y / maxY) * ch;
	let svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" font-family="Segoe UI, Arial" font-size="12">`;
	for (let t = 0; t <= maxY; t += 1) svg += `<line x1="${x0}" x2="${x0 + cw}" y1="${Y(t)}" y2="${Y(t)}" stroke="#f3f4f6"/><text x="${x0 - 8}" y="${Y(t) + 4}" text-anchor="end" fill="#6b7280">${t}</text>`;
	for (let t = 0; t <= maxX; t += 10) svg += `<text x="${X(t)}" y="${y0 + ch + 16}" text-anchor="middle" fill="#6b7280">${t}s</text>`;
	for (const d of series) {
		const points = d.series.map(s => `${X(s.x).toFixed(1)},${Y(s.y).toFixed(1)}`).join(' ');
		svg += `<polyline points="${points}" fill="none" stroke="${d.color}" stroke-width="1.6"/>`;
	}
	svg += `<text x="${x0 + cw / 2}" y="${y0 + ch + 36}" text-anchor="middle" font-weight="600">Time in route (s)</text>`;
	svg += `<text x="16" y="${y0 + ch / 2}" transform="rotate(-90 16 ${y0 + ch / 2})" text-anchor="middle" font-weight="600">Frametime (ms, 0.5 s mean)</text>`;
	svg += `<line x1="${x0}" x2="${x0 + cw}" y1="${y0 + ch}" y2="${y0 + ch}" stroke="#9ca3af"/></svg>`;
	return svg;
}

const legend = data.map(d => `<span class="key"><i style="background:${d.color}"></i>${d.label}</span>`).join('');
const rows = data.map(d => {
	const v = validity[d.config] || {mode: 0, content: 0, dh: 0, runs: 0, passes: []};
	const s = summary[d.config] || {};
	return `<tr><td>${d.label}</td><td>${d.passes.map(p => p.file.replace('.csv', '')).join(', ') || '-'}</td>
<td class="n">${f0(d.avg)}</td><td class="n">${f0(d.low1)}</td><td class="n">${f0(d.low01)}</td>
<td class="n">${d.spread == null ? '-' : d.spread.toFixed(2) + '%'}</td>
<td>${v.mode}/${v.runs}</td><td>${v.content}/${v.runs}</td><td>${v.dh}/${v.runs}</td>
<td>${s.status || 'no summary'}${s.extra_pass ? ' (extra pass)' : ''}</td></tr>`;
}).join('\n');

const html = `<!doctype html><html><head><meta charset="utf-8"><title>Reflexium DLSS light pack route sheet</title>
<style>
body{margin:0;padding:20px 26px;font-family:"Segoe UI",Arial,sans-serif;color:#111827;background:#fff;width:1550px}
h1{font-size:20px;margin:0 0 6px}
.meta{font-size:12px;color:#374151;line-height:1.5;margin-bottom:12px}
.meta code{font-size:11px}
.row{display:flex;gap:18px}
h2{font-size:14px;margin:6px 0 2px}
.key{display:inline-block;margin:2px 14px 6px 0;font-size:12px}
.key i{display:inline-block;width:12px;height:12px;margin-right:5px;vertical-align:-1px}
table{border-collapse:collapse;width:100%;font-size:12px;margin-top:10px}
th,td{border-bottom:1px solid #e5e7eb;padding:4px 7px;text-align:left;vertical-align:top}
th{background:#f9fafb;font-weight:600}
td.n{text-align:right;font-variant-numeric:tabular-nums}
</style></head><body>
<h1>Reflexium DLSS light pack route, ${SOURCE}</h1>
<div class="meta">1920x1080 secondary monitor, Mellow v3.4.1a, DH on with generation disabled, render distance 8, FG off, Reflex off, VSync off, uncapped.
Artifact SHA-256 <code>${ARTIFACT}</code><br>
Average and low values are means of the accepted passes. 1% and 0.1% low = FPS of the mean of the slowest 1% or 0.1% of frames (CapFrameX style).</div>
<div class="row">
<div><h2>Average and low FPS</h2>${barChart()}</div>
<div><h2>Frametime, first accepted pass</h2>${frametimeChart()}</div>
</div>
<div>${legend}</div>
<table><thead><tr><th>Configuration</th><th>Accepted passes (CSV)</th><th>Avg FPS</th><th>1% low</th><th>0.1% low</th><th>Avg spread</th>
<th>Mode proof</th><th>Content</th><th>DH gate</th><th>Repeat status</th></tr></thead><tbody>
${rows}
</tbody></table>
<div class="meta">Validity columns count every pass run for that configuration, including rejected passes: mode proof (render size, evaluated counter, preset hint, no last error, secondary window), content (640x360 crop above the floors), durable DH gate (generator plan disabled, durable LOD files unchanged across the pass, zero generation log lines). A transient SQLite -wal size change is recorded separately and is not treated as LOD generation.</div>
</body></html>`;

const htmlPath = path.join(evidence, 'capframex-sheet.html');
fs.writeFileSync(htmlPath, html);
const pngPath = path.join(evidence, 'capframex-sheet.png');
execFileSync('C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe', [
	'--headless=new', '--disable-gpu', '--hide-scrollbars', '--window-size=1600,1080', `--screenshot=${pngPath}`,
	'file:///' + htmlPath.replace(/\\/g, '/'),
], {timeout: 90000, stdio: ['ignore', 'ignore', 'pipe']});
console.log('wrote', pngPath);
console.log(JSON.stringify(data.map(d => ({config: d.config, avg: d.avg == null ? null : +d.avg.toFixed(2),
	low1: d.low1 == null ? null : +d.low1.toFixed(2), low01: d.low01 == null ? null : +d.low01.toFixed(2),
	spread: d.spread == null ? null : +d.spread.toFixed(2), passes: d.passes.map(p => p.file)})), null, 1));
