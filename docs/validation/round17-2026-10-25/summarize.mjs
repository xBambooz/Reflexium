import fs from 'node:fs';import path from 'node:path';import zlib from 'node:zlib';import {fileURLToPath} from 'node:url';
const dir=path.dirname(fileURLToPath(import.meta.url));
function read(file){const b=fs.readFileSync(file);return JSON.parse(b.toString(b[0]===255?'utf16le':'utf8').replace(/^\uFEFF/,''));}
const metricsRaw=read(path.join(dir,'image-check.json'));const metrics=Array.isArray(metricsRaw)?metricsRaw:[metricsRaw];
const spread=(a,b)=>100*(Math.max(a,b)/Math.min(a,b)-1);
const rows=[];
for(const file of fs.readdirSync(dir).filter(s=>s.endsWith('.bmresult.json.gz'))) {
 const result=JSON.parse(zlib.gunzipSync(fs.readFileSync(path.join(dir,file))));
 if(!result.label.startsWith('rx17-full-'))continue;
 const key=result.label.slice(5);const proofFile=path.join(dir,key+'-proof.json');if(!fs.existsSync(proofFile))continue;
 const proof=read(proofFile),image=metrics.find(m=>m.key===key),pair=result.runs.slice(1,3);
 const avg=pair.map(r=>r.summary.avgFps),low=pair.map(r=>r.summary.low1Fps);
 const reasons=[];
 if(pair.length!==2||proof.samples.length!==2)reasons.push('missing pair');
 if(result.environment.gpu.backend!=='Vulkan')reasons.push('backend');
 if(result.flags.invalid||result.flags.loadTimeout)reasons.push('BM invalid/load timeout');
 if(pair.some(r=>r.settleTimeout||r.settleRelease!=='ready'))reasons.push('terrain settle');
 if(!proof.idle||proof.idle.cpu_cv>=.1||proof.idle.gpu_range>5)reasons.push('idle load');
 for(const sample of proof.samples) {
  if(!sample.mode_valid)reasons.push('mode proof');
  for(const status of [sample.first,sample.last]) {
   const rx=status.plugins.reflexium;
   if(!rx.pack.includes(proof.pack.replace(/\.zip$/,'')))reasons.push('pack');
   if(rx.render_distance!==8)reasons.push('render distance');
   if(rx.last_error||!rx.status_line)reasons.push('status error');
   if(rx.frame_generation!=='off'||rx.reflex_mode!=='OFF')reasons.push('FG/Reflex mismatch');
   if(status.focus.os_minimized)reasons.push('minimized');
  }
 }
 if(!image)reasons.push('missing image');
 else {
  for(const slot of ['left','right'])if(!image[slot].content_valid||!image[slot+'_native'].valid)reasons.push('content/correlation');
  if(!image.repeat.valid)reasons.push('repeat view');
 }
 const nativePose=read(path.join(dir,key.replace(/-(quality|performance)$/,'-native')+'-proof.json')).samples;
 const pose=proof.samples.map((s,i)=>{const a=s.shot.camera,b=nativePose[i].shot.camera;return {distance:Math.hypot(a.x-b.x,a.y-b.y,a.z-b.z),angle:Math.max(Math.abs(a.yaw-b.yaw),Math.abs(a.pitch-b.pitch))};});
 if(pose.some(p=>p.distance>.25||p.angle>.25))reasons.push('pose mismatch');
 const averageSpread=spread(...avg),lowSpread=spread(...low),valid=reasons.length===0;
 const row={key,file,mode:proof.mode,pack:proof.pack,dh:key.startsWith('full-dh-'),avg_pair:avg,low1_pair:low,avg_mean:(avg[0]+avg[1])/2,avg_repeat_pct:averageSpread,low1_repeat_pct:lowSpread,proof_valid:valid,average_valid:valid&&averageSpread<=3,low1_valid:valid&&lowSpread<=3,reasons:[...new Set(reasons)],pose,image};
 rows.push(row);
}
for(const row of rows){const baseline=rows.find(n=>n.key===row.key.replace(/-(quality|performance)$/,'-native'));row.average_delta_pct=row.average_valid&&baseline?.average_valid?100*(row.avg_mean/baseline.avg_mean-1):null;}
fs.writeFileSync(path.join(dir,'results-summary.json'),JSON.stringify(rows,null,2));
for(const row of rows)console.log(`${row.key}: ${row.avg_pair.map(x=>x.toFixed(1)).join('/')} avg [${row.average_valid?'valid':'invalid'}], ${row.low1_pair.map(x=>x.toFixed(1)).join('/')} low1 [${row.low1_valid?'valid':'invalid'}], delta ${row.average_delta_pct?.toFixed(1)??'invalid'}%, reasons ${row.reasons.join(', ')}`);
