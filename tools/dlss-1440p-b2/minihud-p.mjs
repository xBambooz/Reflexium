import fs from 'node:fs';
import {acquire,release,exclusive} from './lock.mjs';
import {profile,evidence,save,launch,client,quit,delay} from './lib.mjs';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
export async function captureMiniHud(c,pid){
 await c.rx('shaders','1');
 await c.rx('upscaler','dlss');await c.rx('quality','quality');
 await c.rx('exclusive_fullscreen','0');
 await c.call('fullscreen',{fullscreen:0});await c.call('fullscreen',{fullscreen:1,activate:1});
 await c.call('join',{world:'Round17 Benchmark Preparation',timeout_s:120},150000);
 await c.call('camera',{x:180.5,y:121.61999988555908,z:60.5,yaw:60,pitch:20});
 await c.rx('frame_generation','dlss_multi');await c.rx('fg_multiplier','3');
 await delay(12000);
 const first=await c.call('status');await delay(10000);const last=await c.call('status');
 save(evidence+'/minihud/status.json',{first,last,processes:exclusive(pid)});
 await c.debugGet('/screenshot',{path:evidence+'/minihud/fg3x.png',gui:'1'});
 save(evidence+'/minihud/debug-status.json',await c.debugGet('/status',{}));
 fs.copyFileSync(profile+'/logs/latest.log',evidence+'/minihud/latest.log');
 console.log('MiniHUD capture saved');
 await c.rx('frame_generation','off');
}
if(process.argv[1] && path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 if(!process.argv.includes('--execute')){console.log('Dry run');process.exit(0);}
 await acquire('capture');
 let pid,c;
 try{exclusive();pid=await launch(25779,'p-minihud');c=client(25779);await captureMiniHud(c,pid);}
 finally{if(c&&pid)await quit(c,pid);release();}
}
