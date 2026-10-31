import fs from 'node:fs';
import {evidence,save,delay,profile,ps} from './lib.mjs';
import {exclusive} from './lock.mjs';
export async function quickCaptures(c,pid){
 await c.call('window',{monitor:'secondary',x:0,y:0,width:2560,height:1440,behind:true});
 await c.call('join',{world:'Round17 Benchmark Preparation',timeout_s:120},150000);
 await c.call('camera',{x:180.5,y:121.61999988555908,z:60.5,yaw:60,pitch:20});
 for(const command of ['time set 6000','weather clear','tick freeze'])await c.call('command',{command});
 for(const [name,quality,preset] of [['q-k','quality',11],['up-l','ultra_performance',12]]){
  await c.rx('upscaler','dlss');await c.rx('quality',quality);await c.rx('dlss_preset',preset);await delay(12000);
  const processes=exclusive(pid),first=await c.call('status');
  await c.call('reflexium',{profile:'reset'});await delay(8000);
  const spans=await c.call('reflexium',{profile:'off'}),last=await c.call('status');
  save(evidence+'/quick-spans/'+name+'.json',{first,last,spans,processes,after:exclusive(pid)});
  await c.debugGet('/screenshot',{path:evidence+'/quick-spans/'+name+'.png',gui:'1'});
 }
 const before=await c.call('status');
 save(evidence+'/smoke-retry/foreground-before.json',before.focus);
 try{
  await c.call('window',{monitor:'secondary',x:0,y:0,width:2560,height:1440,activate:true});
  await c.rx('frame_generation','dlss_multi');await c.rx('fg_multiplier','3');
  await delay(3000);const first=await c.call('status');await delay(5000);const last=await c.call('status');
  save(evidence+'/smoke-retry/fg-on-status.json',{first,last});
  await c.debugGet('/screenshot',{path:evidence+'/smoke-retry/fg-on.png',gui:'1'});
 }finally{
  ps(`Add-Type -TypeDefinition 'using System;using System.Runtime.InteropServices;public class RxRestore{[DllImport("user32.dll")]public static extern bool SetForegroundWindow(IntPtr h);}'; [RxRestore]::SetForegroundWindow([IntPtr]::new(${before.focus.foreground_hwnd}))`);
  save(evidence+'/smoke-retry/foreground-after.json',(await c.call('status')).focus);
 }
 fs.copyFileSync(profile+'/logs/latest.log',evidence+'/quick-latest.log');
}
