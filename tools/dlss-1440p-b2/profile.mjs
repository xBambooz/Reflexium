import fs from 'node:fs';
import {acquire,release,exclusive} from './lock.mjs';
import {profile,evidence,save,launch,client,quit,delay,idleGate} from './lib.mjs';
if (!process.argv.includes('--execute')) { console.log('DRY RUN: profile prepared; no lock, launch, or HTTP call. Later live run requires --execute.'); process.exit(0); }
await acquire();
let pid,c;
try {
 exclusive(); pid=await launch(25763,'b2-spans'); c=client(25763);
 save(evidence+'/spans/title-idle.json',await idleGate(c,pid));
 await c.call('fullscreen',{fullscreen:1,activate:1});
 await c.rx('shader_pack','ComplementaryReimagined_r5.9.3 + EuphoriaPatches_1.10.5');
 await c.rx('shaders','1'); await c.rx('vsync','0'); await c.rx('reflex_mode','on'); await c.rx('frame_generation','off');
 await c.call('join',{world:'Round17 Benchmark Preparation',timeout_s:120},150000);
 await c.call('camera',{x:180.5,y:121.61999988555908,z:60.5,yaw:60,pitch:20});
 await c.call('command',{command:'time set 6000'}); await c.call('command',{command:'weather clear'});
 await c.call('command',{command:'tick freeze'});
 for(const [name,quality,preset] of [['native',null,null],['q-k','quality',11],['up-l','ultra_performance',12],['q-e','quality',5]]) {
  await c.rx('upscaler',quality?'dlss':'off');
  if(quality){await c.rx('quality',quality);await c.rx('dlss_preset',preset);}
  await delay(12000);
  for(let pass=1;pass<=2;pass++) {
   const processes=exclusive(pid), first=await c.call('status');
   await c.call('reflexium',{profile:'reset'}); await delay(12000);
   const spans=await c.call('reflexium',{profile:'off'}),last=await c.call('status');
   save(`${evidence}/spans/${name}-${pass}.json`,{first,last,spans,processes,after:exclusive(pid)});
  }
  await c.debugGet('/screenshot',{path:`${evidence}/spans/${name}.png`,gui:'1',crop_width:'640',crop_height:'360'});
 }
 fs.copyFileSync(profile+'/logs/latest.log',evidence+'/spans/latest.log');
} finally {if(c&&pid)await quit(c,pid);release();}
