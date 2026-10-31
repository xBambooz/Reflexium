import fs from 'node:fs';
import {acquire,release,exclusive} from './lock.mjs';
import {profile,evidence,save,launch,client,quit,delay,idleGate} from './lib.mjs';
if (!process.argv.includes('--execute')) { console.log('DRY RUN: smoke prepared; no lock, launch, or HTTP call. Later live run requires --execute.'); process.exit(0); }
await acquire();
let pid,c;
try {
 exclusive();
 const opts=profile+'/options.txt';
 let text=fs.readFileSync(opts,'utf8');
 for(const [key,value] of Object.entries({renderDistance:20,simulationDistance:8,maxFps:260,enableVsync:false,soundCategory_master:0.0,fullscreen:false,preferredGraphicsBackend:'"vulkan"'})){
  const re=new RegExp('^'+key+':.*$','mg'); text=re.test(text)?text.replace(re,key+':'+value):text+'\n'+key+':'+value;
 }
 fs.writeFileSync(opts,text);
 const dh=profile+'/config/DistantHorizons.toml';
 fs.writeFileSync(dh,fs.readFileSync(dh,'utf8').replace(/enableRendering\s*=\s*true/g,'enableRendering = false').replace(/generatorPlan\s*=\s*"[^"]+"/g,'generatorPlan = "DISABLED"'));
 pid=await launch(25760,'b2-smoke'); c=client(25760);
 save(evidence+'/smoke/title-idle.json',await idleGate(c,pid));
 await c.rx('shader_pack','ComplementaryReimagined_r5.9.3 + EuphoriaPatches_1.10.5');
 await c.rx('vsync','0'); await c.rx('reflex_mode','on');
 await c.call('window',{monitor:'secondary',x:0,y:0,width:2560,height:1440,behind:true});
 await c.call('join',{world:'Round17 Benchmark Preparation',timeout_s:120},150000);
 await c.call('camera',{x:180.5,y:121.61999988555908,z:60.5,yaw:60,pitch:20});
 await delay(12000);
 save(evidence+'/smoke/fg-off-status.json',await c.call('status'));
 await c.debugGet('/screenshot',{path:evidence+'/smoke/fg-off.png',gui:'1'});
 await c.rx('frame_generation','dlss'); await c.rx('fg_multiplier','3');
 const first=await c.call('status'); await delay(10000);
 const last=await c.call('status');
 save(evidence+'/smoke/fg-on-status.json',{first,last});
 await c.debugGet('/screenshot',{path:evidence+'/smoke/fg-on.png',gui:'1'});
 fs.copyFileSync(profile+'/logs/latest.log',evidence+'/smoke/latest.log');
 console.log('SMOKE CAPTURED',pid);
} finally {
 if(c && pid) await quit(c,pid);
 release();
}

