import fs from 'node:fs';
import assert from 'node:assert/strict';
const groups=new Map();
for(const line of fs.readFileSync(process.argv[2],'utf8').split(/\r?\n/)){
 const match=line.match(/\[FG API\] frame=(\d+) t=(\d+) (.*)/);if(!match||match[1]==='0')continue;
 const events=groups.get(match[1])??[];events.push(match[3]);groups.set(match[1],events);
}
const complete=[...groups].filter(([,events])=>events.includes('marker=INPUT_SAMPLE'));
assert.equal(complete.length,32,'32 complete real frames required');
let presents=0,replacements=0,realOnly=0;
for(const [id,events] of complete){
 const one=prefix=>events.filter(e=>e.startsWith(prefix));
 assert.equal(one('sleep call value=').length,1,`frame ${id}: sleep once`);
 assert.equal(one('sleep wait exit value=').length,1,`frame ${id}: completed sleep once`);
 assert(one('sleep wait exit value=')[0].endsWith('result=0'),`${id}: successful sleep wait`);
 let previous=-1;
 for(const marker of ['INPUT_SAMPLE','SIMULATION_START','SIMULATION_END','RENDERSUBMIT_START','RENDERSUBMIT_END','PRESENT_START','PRESENT_END']){
  assert.equal(one('marker='+marker).length,1,`frame ${id}: ${marker} once`);
  const index=events.indexOf('marker='+marker);assert(index>previous,`frame ${id}: ${marker} order`);previous=index;
 }
 assert(events.findIndex(e=>e.startsWith('sleep wait exit'))<events.indexOf('marker=INPUT_SAMPLE'));
 for(const kind of ['PRESENT','RENDERSUBMIT']){
  let open=false;
  for(const event of events){
   if(event===`marker=OUT_OF_BAND_${kind}_START`){assert(!open,`${id}: nested ${kind}`);open=true;}
   if(event===`marker=OUT_OF_BAND_${kind}_END`){assert(open,`${id}: unmatched ${kind}`);open=false;if(kind==='PRESENT')presents++;else replacements++;}
  }
  assert(!open,`${id}: unclosed ${kind}`);
 }
 const actual=one('present role=');assert(actual.length>=1,`${id}: real present required`);
 assert.equal(actual.filter(e=>e.startsWith('present role=real ')).length,1,`${id}: one real present`);
 if(actual.length===1){assert.equal(one('marker=OUT_OF_BAND_PRESENT_START').length,0);realOnly++;continue;}
 assert.equal(one('marker=OUT_OF_BAND_PRESENT_START').length,actual.length,`${id}: each physical present bracketed`);
 assert.equal(one('marker=OUT_OF_BAND_RENDERSUBMIT_START').length,actual.length-1,`${id}: each replacement submitted once`);
 for(let i=0;i<events.length;i++)if(events[i].startsWith('present role='))assert.equal(events[i-1],'marker=OUT_OF_BAND_PRESENT_END',`${id}: present end adjacency`);
}
assert(presents>0,'active generation required within Auto trace');
console.log(JSON.stringify({frames:complete.length,first:complete[0][0],last:complete.at(-1)[0],sleeps:32,realOnly,physicalPresents:presents+realOnly,replacementSubmits:replacements,result:'PASS'},null,2));
