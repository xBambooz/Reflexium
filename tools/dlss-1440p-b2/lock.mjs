import fs from 'node:fs';
import {ps,save,evidence,delay} from './lib.mjs';
const lock='C:/Users/Ryan/source/repos/Reflexium-gpu.lock';
let held=false;
export async function acquire(purpose='capture') {
 for(;;) {
  const cooldown=evidence+'/last-release.json';
  if(fs.existsSync(cooldown)){const remaining=300000-(Date.now()-JSON.parse(fs.readFileSync(cooldown)).time);if(remaining>0){console.log('GPU cooldown',Math.ceil(remaining/1000),'seconds');await delay(remaining);}}

  try {
   ps(`$ErrorActionPreference='Stop'; New-Item -ItemType File -Path '${lock}' -ErrorAction Stop | Out-Null`);
   fs.writeFileSync(lock,JSON.stringify({owner:'worker-B',pid:process.pid,started:new Date().toISOString(),purpose}));
   held=true; save(`${evidence}/lock-acquired-${Date.now()}.json`,{pid:process.pid,time:new Date().toISOString(),purpose}); return;
  } catch(e) {
   if(!fs.existsSync(lock))throw e;
   const raw=fs.readFileSync(lock,'utf8').replace(/^\uFEFF/,'');
   let owner;try{owner=JSON.parse(raw);}catch{await delay(1000);continue;}
   const alive=ps(`if(Get-Process -Id ${Number(owner.pid)} -ErrorAction SilentlyContinue){'alive'}`)==='alive';
   const stale=!alive || Date.now()-Date.parse(owner.started)>3600000;
   save(`${evidence}/lock-check-${Date.now()}.json`,{checked:new Date().toISOString(),owner,alive,stale});
   if(stale && fs.readFileSync(lock,'utf8').replace(/^\uFEFF/,'')===raw){fs.unlinkSync(lock);continue;}
   console.log(new Date().toISOString(),'waiting for GPU',owner.owner,owner.pid);
   await delay(180000);
  }
 }
}
export function release(){
 if(held && fs.existsSync(lock) && JSON.parse(fs.readFileSync(lock,'utf8')).pid===process.pid){fs.unlinkSync(lock);held=false;save(evidence+'/last-release.json',{time:Date.now(),pid:process.pid});}
}
export function exclusive(own=0){
 const all=JSON.parse(ps("@(Get-CimInstance Win32_Process | Where-Object {$_.Name -match '^javaw?\\.exe$'} | Select-Object ProcessId,ParentProcessId,CommandLine) | ConvertTo-Json -Compress")||'[]');
 const census=Array.isArray(all)?all:[all];
 const idleDaemons=[];
 const others=census.filter(p=>{
  if(p.ProcessId===own)return false;
  if(/GradleDaemon/.test(p.CommandLine)){
   const state=ps(`Get-ChildItem '$env:USERPROFILE/.gradle/daemon/*/daemon-${p.ProcessId}.out.log' -ErrorAction SilentlyContinue | Get-Content | Select-String 'Marking the daemon as (idle|busy)' | Select-Object -Last 1`);
   if(state.includes('Marking the daemon as idle')){idleDaemons.push({pid:p.ProcessId,state});return false;}
  }
  return true;
 });
 if(others.length) throw Error('Other Java workload: '+JSON.stringify(others.map(p=>p.ProcessId)));
 return {checked:new Date().toISOString(),processes:all,idleDaemons,exclusive:true};
}
