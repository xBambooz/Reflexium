import {dispatch} from 'file:///C:/Users/Ryan/source/repos/mc-agent-harness/tools/mcp-server.mjs';
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..','..');
const session=JSON.parse((await fs.readFile('C:/Users/Ryan/source/repos/mc-agent-harness/instances/25624/session.json','utf8')).replace(/^\uFEFF/,''));
if(path.resolve(session.game_directory)!==path.join(root,'run'))throw Error('Wrong owned client');
const out=root+'/build/fg-round4';
async function m(name,args){const r=await dispatch({jsonrpc:'2.0',id:1,method:'tools/call',params:{name,arguments:{port:25624,...args}}});if(r.result?.isError)throw Error(JSON.stringify(r));const t=r.result.content.find(c=>c.type==='text');return JSON.parse(t.text);}
const call=(endpoint,params={})=>m('mc_call',{endpoint,params});
const set=(key,value)=>call('reflexium',{key,value:String(value)});
const cmd=command=>call('command',{command});
const mode=process.argv[2];
if(mode==='quit')console.log(await call('quit',{exit:1}));
else if(mode==='stress'){
 for(let i=0;i<600;i++)await cmd('summon cow 8 151 110 {Tags:["rx_fg_round4"],NoAI:1b,Silent:1b,Invulnerable:1b}');
 console.log('600 tagged stress cows');
}else if(mode==='cleanup'){
 await cmd('kill @e[tag=rx_fg_round4]');await cmd('weather clear');await set('frame_generation','off');
 console.log('stress removed, FG off');
}
else if(mode==='status')console.log(JSON.stringify(await m('mc_status',{})));
else if(mode==='setup'){
 console.log(await call('join',{world:'Reflexium Vitrail Smoke'}));
 await set('frame_generation','off');await set('shaders',0);await set('improved_transparency',0);await set('smart_reflex',0);await set('upscaler','dlss');await set('quality','quality');
 await cmd('gamemode spectator @a');await cmd('tick unfreeze');await cmd('time set noon');
 await cmd('fill -8 149 98 8 149 120 stone');await cmd('fill -8 150 115 8 158 115 bricks');await cmd('setblock -2 150 108 campfire');await cmd('setblock 2 150 108 campfire');
 await call('camera',{x:0,y:152,z:100,yaw:0,pitch:0});console.log('ready');
}else if(mode==='trace'){
 await cmd('execute as @e[tag=rx_fg_round4] run tp @s 0 150 108');
 await call('camera',{x:0,y:152,z:100,yaw:0,pitch:0});
 await set('fg_multiplier','auto');await call('wait',{ms:7000});
 const before=await m('mc_status',{});await fs.writeFile(out+'/trace-before.json',JSON.stringify(before,null,2));
 if(before.plugins.reflexium.fg_effective_multiplier<2)throw Error('Trace requires active Auto');
 await set('reflex_trace',32);await call('wait',{ms:2500});await fs.copyFile(root+'/run/logs/latest.log',out+'/trace-full.log');await fs.writeFile(out+'/trace-status.json',JSON.stringify(await m('mc_status',{}),null,2));
 const lines=(await fs.readFile(out+'/trace-full.log','utf8')).split(/\r?\n/).filter(x=>x.includes('[FG API]'));
 const ids=[...new Set(lines.filter(x=>x.endsWith('marker=INPUT_SAMPLE')).map(x=>x.match(/frame=(\d+)/)[1]))].slice(-32);
 const evidence=root+'/docs/validation/fg-evidence-2026-10-19/round4';await fs.mkdir(evidence,{recursive:true});
 await fs.writeFile(evidence+'/trace.log',lines.filter(x=>ids.includes(x.match(/frame=(\d+)/)[1])).join('\n')+'\n');console.log('trace saved');
}else{
 await set('particles_fg_replay',mode==='before'?0:1);
 for(const scene of ['smoke','rain'])for(const multiplier of (mode==='quality'?['2']:['2','auto'])){
 await set('quality',mode==='quality'?'quality':'native_aa');await cmd('weather '+(scene==='rain'?'rain':'clear'));await set('frame_generation','dlss');await set('fg_multiplier',multiplier);await call('camera',{x:0,y:152,z:100,yaw:-15,pitch:0});await call('wait',{ms:12000});await call('turn',{dyaw:40,ticks:200,async:true});await call('wait',{ms:500});
 const label=mode+'-'+scene+'-'+multiplier;let data;for(let attempt=0;attempt<4;attempt++){try{await call('camera',{x:0,y:152,z:100,yaw:-15,pitch:0});await call('turn',{dyaw:40,ticks:200,async:true});await call('wait',{ms:500});data=await call('reflexium',{fg_capture:1,dir:out+'/'+label});break;}catch(e){if(attempt===3)throw e;await call('wait',{ms:6000});}};await fs.writeFile(out+'/'+label+'.json',JSON.stringify(data,null,2));await fs.writeFile(out+'/'+label+'-status.json',JSON.stringify(await m('mc_status',{}),null,2));console.log(label+' captured');await call('wait',{ms:10000});
 }
}






