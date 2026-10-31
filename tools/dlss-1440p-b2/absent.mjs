import fs from 'node:fs';
import {acquire,release,exclusive} from './lock.mjs';
import {root,evidence,save,ps,delay,processInfo} from './lib.mjs';
const run=root+'/run',port=25764;
if (!process.argv.includes('--execute')) { console.log('DRY RUN: absent prepared; no lock, launch, or HTTP call. Later live run requires --execute.'); process.exit(0); }
await acquire();
let pid=0;
try {
 exclusive();
 if(fs.readdirSync(run+'/mods').some(n=>/minihud.*\.jar$/i.test(n)))throw Error('MiniHUD present in dev mods');
 let opts=fs.readFileSync(run+'/options.txt','utf8');
 opts=opts.replace(/^soundCategory_master:.*$/mg,'soundCategory_master:0.0').replace(/^preferredGraphicsBackend:.*$/mg,'preferredGraphicsBackend:"vulkan"').replace(/^fullscreen:.*$/mg,'fullscreen:false');
 fs.writeFileSync(run+'/options.txt',opts);
 process.env.MC_HARNESS_JVM_ARGS='["-XX:StackShadowPages=32"]';
 const result=JSON.parse(ps(`& 'C:/Users/Ryan/source/repos/mc-agent-harness/tools/launch.ps1' -Project '${root}' -RunDirectory '${run}' -Port ${port} -Monitor secondary -Width 1920 -Height 1080 -GradlePropertiesJson '{"rxDebug":"25794"}' -TimeoutSeconds 30 -Pending`));
 save(evidence+'/absent/launch.json',result);
 const sessionPath=`C:/Users/Ryan/source/repos/mc-agent-harness/instances/${port}/session.json`;
 const session=JSON.parse(fs.readFileSync(sessionPath,'utf8').replace(/^\uFEFF/,''));
 const call=async(route,params)=>{
  const response=await fetch(`http://127.0.0.1:${port}/${route}`,{method:params?'POST':'GET',headers:{Authorization:'Bearer '+session.token,'Content-Type':'application/json'},body:params?JSON.stringify(params):undefined,signal:AbortSignal.timeout(10000)});
  return await response.json();
 };
 let status;
 for(let i=0;i<120;i++){
  try{status=await call('status');if(status.pid){pid=status.pid;break;}}catch{}
  await delay(1000);
 }
 if(!pid)throw Error('Dev client did not become ready');
 save(evidence+'/absent/status.json',status);
 fs.copyFileSync(run+'/logs/latest.log',evidence+'/absent/latest.log');
 const info=processInfo(pid);
 if(!info?.CommandLine.includes('agentharness') || !info.CommandLine.includes(root.replaceAll('/','\\')) && !info.CommandLine.includes(root))throw Error('Dev shutdown ownership mismatch');
 await call('quit',{exit:true});
 for(let i=0;i<60 && processInfo(pid);i++)await delay(1000);
 if(processInfo(pid))throw Error('Dev shutdown timed out');
 console.log('ABSENT SMOKE COMPLETE',pid);
} finally {if(!pid || !processInfo(pid))release();}

