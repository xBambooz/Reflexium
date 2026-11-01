import fs from 'node:fs/promises';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {dispatch} from 'file:///C:/Users/Ryan/source/repos/mc-agent-harness/tools/mcp-server.mjs';
const root=path.resolve(import.meta.dirname,'../..'), run=path.join(root,'run');
const lock='C:/Users/Ryan/source/repos/Reflexium-gpu.lock', owner='view-bob-c9f451d0';
const port=Number(process.argv[3]??25930), mode=process.argv[2];
const out=path.join(root,'build/validation/view-bob-20261101');
async function tool(name,args){const r=await dispatch({jsonrpc:'2.0',id:1,method:'tools/call',params:{name,arguments:args}});const v=JSON.parse((r.result?.content??r.content).find(x=>x.type==='text').text);if(r.result?.isError||v.ok===false)throw Error(JSON.stringify(v));return v;}
const call=(endpoint,params={})=>tool('mc_call',{port,endpoint,params});
const set=(key,value)=>call('reflexium',{key,value:String(value)});
function clients(){return JSON.parse(execFileSync('powershell.exe',['-NoProfile','-Command',"ConvertTo-Json -Compress -InputObject @(Get-CimInstance Win32_Process | Where-Object Name -Match '^javaw?\\.exe$' | Select-Object ProcessId,CommandLine)"],{encoding:'utf8'}));}
function checkReal(){if(clients().some(p=>p.CommandLine?.includes('ModrinthApp\\profiles\\Fabric 26.3')))throw Error('Real profile active');}
if(mode==='acquire'){
 checkReal();const f=await fs.open(lock,'wx');await f.writeFile(JSON.stringify({owner,pid:process.pid,started:new Date().toISOString(),purpose:'short view bob validation'}));await f.close();console.log('acquired');
}else if(mode==='launch'){
 checkReal();if(JSON.parse(await fs.readFile(lock,'utf8')).owner!==owner)throw Error('Not own GPU lock');
 if(clients().some(p=>p.CommandLine?.includes('net.fabricmc.devlaunchinjector.Main')))throw Error('Another client active');
 process.env.MC_HARNESS_JVM_ARGS=JSON.stringify(['-Dreflexium.streamline=true',...(process.argv[4]==='before'?['-Dreflexium.debug.earlyViewHistory=true']:[])]);
 console.log(JSON.stringify(await tool('mc_launch',{project:root,port,monitor:'secondary',width:1920,height:1080,run_directory:run,gradle_properties:{rxDebug:String(port+1),rxIqUser:'RxBobCheck',reflexiumInstallDir:path.join(root,'build/install')}})));
}else if(mode==='quit'){
 console.log(JSON.stringify(await call('quit',{exit:true})));
}else if(mode==='release'){
 if(clients().some(p=>p.CommandLine?.includes(run)))throw Error('Owned client still alive');
 if(JSON.parse(await fs.readFile(lock,'utf8')).owner!==owner)throw Error('Not own GPU lock');
 await fs.unlink(lock);console.log('released');
}else if(mode==='status')console.log(JSON.stringify(await tool('mc_status',{port})));
else if(mode==='call')console.log(JSON.stringify(await call(process.argv[4],JSON.parse(process.argv[5]??'{}'))));
else if(mode==='setup'){
 await call('join',{world:'Reflexium Vitrail Smoke',timeout_s:120});
 for(const command of ['gamemode survival @a','tick unfreeze','time set 6000','weather clear'])await call('command',{command});
 await set('shader_pack','ComplementaryReimagined_r5.9.3 + EuphoriaPatches_1.10.5');
 for(const [k,v] of Object.entries({upscaler:'dlss',quality:'ultra_performance',dlss_model:'transformer',dlss_preset:12,frame_generation:'off',smart_reflex:0,reflex_interval_us:16667}))await set(k,v);
 await call('wait',{ms:5000});console.log('setup');
}else if(mode==='capture'){
 await fs.mkdir(out,{recursive:true});const label=process.argv[4];
 for(const quality of ['ultra_performance','quality'])for(const bob of (label==='before'?[1]:[1,0])){
  await set('quality',quality);await set('bob_view',bob);
  await call('camera',{x:200.5,y:151,z:1.5,yaw:0,pitch:3});await call('wait',{ms:1500});
  await call('key',{name:'key.forward',down:true});
  try{
   for(let i=0;i<5;i++){
    const name=`${label}-${quality}-bob${bob}-${i}`;
    await call('reflexium',{capture:name,dir:out,extras:1});await call('wait',{ms:120});
   }
  }finally{await call('key',{name:'key.forward',down:false});}
  console.log(`${label}-${quality}-bob${bob}`);
 }
}else throw Error('Unknown command');
