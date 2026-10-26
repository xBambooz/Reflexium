// Owned Round 4 client only. Run using the installed Electron Node runtime.
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..','..');
const out=path.join(root,'build/validation/round4');
await fs.mkdir(out,{recursive:true});
const session=JSON.parse((await fs.readFile('C:/Users/Ryan/source/repos/mc-agent-harness/instances/25614/session.json','utf8')).replace(/^\uFEFF/,''));
if(path.resolve(session.game_directory)!==path.join(root,'run')) throw Error('Wrong client');
async function call(endpoint,body={}) {
 const r=await fetch('http://127.0.0.1:25614/'+endpoint,{method:'POST',headers:{Authorization:'Bearer '+session.token,'Content-Type':'application/json'},body:JSON.stringify(body)});
 const result=await r.json(); if(!result.ok)throw Error(JSON.stringify(result));return result;
}
const setting=(key,value)=>call('reflexium',{key,value:String(value)});
const command=command=>call('command',{command});
if(process.env.IQ_MODE==='evidence') {
 const evidence=path.join(root,'docs/validation/iq-evidence-2026-10-19/round4');await fs.mkdir(evidence,{recursive:true});
 for(const name of ['near-cloud-1','oit-cloud']) {
  const meta=JSON.parse(await fs.readFile(path.join(out,name+'.json'),'utf8'));
  for(const [kind,file] of Object.entries(meta.files)) {
   const destination=path.join(evidence,name+'_'+kind+'.png');await fs.copyFile(file.path,destination);
   file.path=path.relative(root,destination).replaceAll('\\','/');
  }
  await fs.writeFile(path.join(evidence,name+'.json'),JSON.stringify(meta,null,2));
 }
 await fs.copyFile(path.join(out,'composition-checks.json'),path.join(evidence,'composition-checks.json'));
 console.log('portable evidence copied');
} else if(process.env.IQ_MODE==='analyze') {
 const results=[];
 for(const file of (await fs.readdir(out)).filter(f=>/^final-(vanilla|bsl)-.*\.json$/.test(f))) {
  const data=JSON.parse(await fs.readFile(path.join(out,file),'utf8'));
  const rx=data.frames[0].plugins.reflexium;
  const expected=file.includes('vanilla')&&!file.includes('native')&&file.endsWith('-1.json');
  if(rx.last_error||rx.observed.post_upscale_particles!==expected)throw Error('Composition check failed '+file);
  results.push({file,yaws:data.frames.map(f=>f.camera.yaw),post:rx.observed.post_upscale_particles,render:rx.render_size,display:rx.display_size});
 }
 await fs.writeFile(path.join(out,'composition-checks.json'),JSON.stringify(results,null,2));
 console.log(results.length+' composition checks passed');
} else if(process.env.IQ_MODE==='setup') {
 await setting('frame_generation','off');await setting('shaders',0);
 await command('gamemode spectator @a');await command('tick unfreeze');await command('time set noon');await command('weather clear');
 await command('fill -8 149 98 8 149 120 stone');
 await command('fill -8 150 115 8 158 115 bricks');
 await command('setblock -2 150 108 campfire');await command('setblock 2 150 108 campfire');
 await command('fill -1 150 110 1 154 110 glass');
 console.log('fixture ready');
} else if(process.env.IQ_MODE==='final') {
 await setting('frame_generation','off');await command('gamemode spectator @a');await command('tick unfreeze');
 await command('fill -8 150 104 8 158 106 air');
 for(const shaders of [0,1]) {
  await setting('shaders',shaders);await call('wait',{ms:2500});
  for(const scene of ['smoke','rain','fast']) {
   await command('weather '+(scene==='rain'?'rain':'clear'));await call('wait',{ms:5000});
   for(const mode of ['native','quality','performance'])for(const deferred of (mode==='native'?[0]:[0,1])) {
    await setting('particles_defer',deferred);await setting('upscaler',mode==='native'?'off':'dlss');if(mode!=='native')await setting('quality',mode);
    await call('camera',scene==='fast'?{x:-24.5,y:131,z:11.5,yaw:-45,pitch:0}:{x:0,y:151,z:100,yaw:0,pitch:0});await call('wait',{ms:1600});
    const label=`final-${shaders?'bsl':'vanilla'}-${scene}-${mode}-${deferred}`;
    if(scene==='fast') {await call('turn',{dyaw:90,ticks:10,async:true});await call('wait',{ticks:3});}
    const data=await call('capture_sequence',{dir:path.join(out,label),count:4,interval_ms:50});
    const rx=data.frames[0].plugins.reflexium;
    if(rx.last_error)throw Error(rx.last_error);
    if(rx.observed.post_upscale_particles!==Boolean(!shaders&&mode!=='native'&&deferred))throw Error('Unexpected particle composition: '+label);
    if(mode!=='native'&&(rx.display_size.w!==2560||rx.display_size.h!==1440))throw Error('Wrong output size');
    await fs.writeFile(path.join(out,label+'.json'),JSON.stringify(data,null,2));console.log(label);
   }
  }
 }
 await setting('particles_defer',1);await command('weather clear');
 await fs.writeFile(path.join(out,'final-status.json'),JSON.stringify(await call('status'),null,2));
} else if(process.env.IQ_MODE==='occlusion-final') {
 await setting('shaders',0);await setting('frame_generation','off');await command('tick unfreeze');await command('weather clear');
 await command('fill -3 150 104 -2 154 104 bricks');
 await command('fill 2 150 106 3 155 106 red_stained_glass');
 await call('camera',{x:0,y:151,z:100,yaw:0,pitch:0});
 for(const mode of ['native','quality','performance']) {
  await setting('particles_defer',1);await setting('upscaler',mode==='native'?'off':'dlss');if(mode!=='native')await setting('quality',mode);
  await call('wait',{ms:3000});
  const data=await call('capture_sequence',{dir:path.join(out,'occlusion-final-'+mode),count:4,interval_ms:50});
  await fs.writeFile(path.join(out,'occlusion-final-'+mode+'.json'),JSON.stringify(data,null,2));console.log(mode);
 }
 await command('fill -3 150 104 -2 154 104 air');await command('fill 2 150 106 3 155 106 air');
} else if(process.env.IQ_MODE==='burst') {
 await setting('shaders',0);await setting('frame_generation','off');await setting('improved_transparency',0);await command('tick unfreeze');
 await call('camera',{x:0,y:151,z:100,yaw:0,pitch:0});await command('setblock -4 150 108 torch');
 for(const mode of ['native','quality','performance'])for(const deferred of (mode==='native'?[0]:[0,1])) {
  await setting('upscaler',mode==='native'?'off':'dlss');if(mode!=='native')await setting('quality',mode);await setting('particles_defer',deferred);await call('wait',{ms:1500});
  await command('particle minecraft:block{block_state:{id:"minecraft:stone"}} 0 153 108 0.5 0.5 0 0.1 40 force');
  await command('particle minecraft:flame -2 153 108 0.25 0.25 0 0 20 force');
  await call('wait',{ticks:2});
  const label='burst-'+mode+'-'+deferred;
  const data=await call('capture_sequence',{dir:path.join(out,label),count:4,interval_ms:50});
  await fs.writeFile(path.join(out,label+'.json'),JSON.stringify(data,null,2));console.log(label);
 }
 await setting('particles_defer',1);
} else if(process.env.IQ_MODE==='fg-check') {
 await setting('smart_reflex',0);await setting('frame_generation','dlss');await setting('fg_multiplier',2);await call('wait',{ms:1500});
 const before=(await call('status')).plugins.reflexium;await call('wait',{ms:3000});const after=(await call('status')).plugins.reflexium;
 await fs.writeFile(path.join(out,'fg-compatibility.json'),JSON.stringify({before,after},null,2));
 await setting('frame_generation','off');await setting('smart_reflex',1);
 if(after.last_error||after.fg_evaluated_frames<=before.fg_evaluated_frames)throw Error('FG compatibility did not advance');console.log('FG compatibility advanced');
} else if(process.env.IQ_MODE==='smoke-check') {
 await setting('shaders',0);await setting('frame_generation','off');await setting('upscaler','dlss');await setting('quality','performance');await setting('particles_defer',1);
 await setting('improved_transparency',1);await call('camera',{x:0,y:190,z:100,yaw:0,pitch:-20});
 await setting('smart_reflex',0);await setting('reflex_interval_us',33333);await call('wait',{ms:2500});
 const oit=await call('reflexium',{capture:'oit-cloud',dir:out,extras:true});
 if(oit.capture.observed.post_upscale_particles||!oit.capture.observed.vanilla_cloud_motion)throw Error('OIT fallback failed');
 await call('screenshot',{path:path.join(out,'oit-final.png')});
 await setting('improved_transparency',0);await setting('reflex_interval_us',-1);await setting('smart_reflex',1);
 await call('window',{monitor:'primary',x:0,y:0,width:2432,height:1368,behind:true});await call('wait',{ms:1500});
 await call('window',{monitor:'primary',x:0,y:0,width:2560,height:1440,behind:true});await call('wait',{ms:1500});
 for(const mode of ['native','quality','performance']) {
  await setting('upscaler',mode==='native'?'off':'dlss');if(mode!=='native')await setting('quality',mode);await call('wait',{ms:1200});
  const rx=(await call('status')).plugins.reflexium;if(rx.last_error)throw Error(rx.last_error);
 }
 await setting('shaders',1);await call('wait',{ms:2500});await setting('shaders',0);await call('wait',{ms:2000});
 await fs.writeFile(path.join(out,'smoke-status.json'),JSON.stringify(await call('status'),null,2));console.log('OIT, resize and switches passed');
} else if(process.env.IQ_MODE==='cloud-control') {
 await setting('shaders',0);await setting('frame_generation','off');await setting('particles_defer',0);
 await setting('upscaler','dlss');await setting('quality','performance');await setting('smart_reflex',0);await setting('reflex_interval_us',33333);
 await command('tick unfreeze');
 for(const enabled of [0,1]) {
  await setting('cloud_motion',enabled);await call('camera',{x:0,y:190,z:100,yaw:0,pitch:-20});await call('wait',{ms:2000});
  const data=await call('reflexium',{capture:'near-cloud-'+enabled,dir:out,extras:true});
  console.log(data.json);
 }
 await setting('cloud_motion',1);await setting('reflex_interval_us',-1);await setting('smart_reflex',1);
} else { throw Error('Unknown IQ_MODE'); }
