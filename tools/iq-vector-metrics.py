"""Camera correspondence grouped by the known arena surface, for no-wave controls. python -I script capture.json."""
import json
import sys
from pathlib import Path
import numpy as np
from PIL import Image

m=json.loads(Path(sys.argv[1]).read_text())
assert m["observed"]["bsl_foliage_motion"], "Foliage pass was not dispatched"
rgb=np.array(Image.open(m["files"]["motion"]["path"]),dtype=np.int64)[::4,::4]
d=np.array(Image.open(m["files"]["depth"]["path"]),dtype=np.int64)[::4,::4]
h,w=m["sizes"]["render_h"],m["sizes"]["render_w"]
y,x=np.mgrid[0:h:4,0:w:4]
ndc=np.stack(((x+.5)/w*2-1,1-(y+.5)/h*2,(d[:,:,0]*65536+d[:,:,1]*256+d[:,:,2])/16777215,np.ones_like(x)),axis=-1)
def matrix(key): return np.array(m["matrices"][key]).reshape((4,4),order="F")
p=ndc@matrix("current_to_previous").T
expected=(p[:,:,:2]/p[:,:,3,None]-(ndc[:,:,:2]-[m["jitter"]["ndc_x"],m["jitter"]["ndc_y"]]))*[w/2,h/2]
actual=np.stack(((rgb[:,:,0]*16+rgb[:,:,1]//16)/32-64,((rgb[:,:,1]%16)*256+rgb[:,:,2])/32-64),axis=-1)
world=ndc@np.linalg.inv(matrix("world_view_projection_jittered")).T
world=world[:,:,:3]/world[:,:,3,None]+[m["camera"][k] for k in ("x","y","z")]
valid=ndc[:,:,2]>0
leaf=valid&(world[:,:,0]>-26.1)&(world[:,:,0]<-20.9)&(world[:,:,1]>130.9)&(world[:,:,1]<135.1)&(world[:,:,2]>21.9)&(world[:,:,2]<23.1)
ground=valid&(world[:,:,1]>130.9)&(world[:,:,1]<131.05)&(world[:,:,2]>12)&(world[:,:,2]<20)
results={}
for name,mask in (("leaf",leaf),("ground",ground)):
    a,e=actual[mask],expected[mask]
    if not len(a):
        results[name]=dict(samples=0)
        continue
    error=np.linalg.norm(a-e,axis=1)
    results[name]=dict(samples=len(a),mean_px=float(np.mean(error)),p99_px=float(np.percentile(error,99)),
        expected_mean_px=float(np.linalg.norm(e,axis=1).mean()),actual_mean_px=float(np.linalg.norm(a,axis=1).mean()),
        within_quarter_pixel=float(np.mean(error<=.25)))
print(json.dumps(results,indent=2))
assert np.max(np.abs(matrix("world_view_projection")-matrix("previous_world_view_projection")))>1e-5, "Camera must move"
for result in results.values():
    assert result["samples"]>100 and result["expected_mean_px"]>.05 and result["p99_px"]<.05, "No-wave correspondence failed"
