import fs from 'node:fs';
import {ps,save,evidence,delay} from './lib.mjs';
const lock='C:/Users/Ryan/source/repos/Reflexium-gpu.lock';
let held=false;
export async function acquire() {
 const start=Date.now();
 while(Date.now()-start < 4*3600*1000) {
  try {
   const fd=fs.openSync(lock,'wx');
   fs.writeFileSync(fd,JSON.stringify({owner:'worker-B2',pid:process.pid,started:new Date().toISOString()}));
   fs.closeSync(fd); held=true; save(`${evidence}/lock-acquired-${Date.now()}.json`,{pid:process.pid,time:new Date().toISOString()}); return;
  } catch(e) {
   if(e.code!=='EEXIST') throw e;
   const raw=fs.readFileSync(lock,'utf8').replace(/^\uFEFF/,'');
   const owner=JSON.parse(raw);
   const alive=ps(`if(Get-Process -Id ${Number(owner.pid)} -ErrorAction SilentlyContinue){'alive'}`)==='alive';
   const stale=!alive || Date.now()-Date.parse(owner.started)>3600000;
   save(`${evidence}/lock-check-${Date.now()}.json`,{checked:new Date().toISOString(),owner,alive,stale});
   if(stale && fs.readFileSync(lock,'utf8').replace(/^\uFEFF/,'')===raw){fs.unlinkSync(lock);continue;}
   console.log(new Date().toISOString(),'waiting for GPU',owner.owner,owner.pid);
   await delay(180000);
  }
 }
 throw Error('GPU reservation unavailable after four hours');
}
export function release(){
 if(held && JSON.parse(fs.readFileSync(lock,'utf8')).pid===process.pid){fs.unlinkSync(lock);held=false;}
}
export function exclusive(own=0){
 const all=JSON.parse(ps("@(Get-CimInstance Win32_Process | Where-Object {$_.Name -match '^javaw?\\.exe$'} | Select-Object ProcessId,ParentProcessId,CommandLine) | ConvertTo-Json -Compress")||'[]');
 const others=(Array.isArray(all)?all:[all]).filter(p=>p.ProcessId!==own && !/GradleDaemon|GradleWrapperMain|gradle-worker|Gradle Test Executor/.test(p.CommandLine));
 if(others.length) throw Error('Other Java workload: '+JSON.stringify(others.map(p=>p.ProcessId)));
 return {checked:new Date().toISOString(),processes:all,exclusive:true};
}
