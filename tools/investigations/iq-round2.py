"""Owned harness captures. Run with python -I; Pillow and numpy are analysis-only tools."""
import json
import sys
import time
import threading
import shutil
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build/validation/round2"
SESSION = Path("C:/Users/Ryan/source/repos/mc-agent-harness/instances/25612/session.json")

def call(endpoint, **body):
    session = json.loads(SESSION.read_text(encoding="utf-8-sig"))
    assert Path(session["game_directory"]).resolve() == (ROOT / "run").resolve()
    request = urllib.request.Request("http://127.0.0.1:25612/" + endpoint,
        data=json.dumps(body).encode(), headers={"Authorization": "Bearer " + session["token"], "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=240) as response:
        result = json.load(response)
    assert result.get("ok"), result
    return result

def setting(key, value):
    call("reflexium", key=key, value=str(value))

def pose():
    call("camera", x=-24.5, y=131, z=11.5, yaw=0, pitch=0)
    call("wait", ms=1000)

def capture(label):
    setting("frame_generation", "off")
    call("command", command="tick freeze")
    for shaders in ("1", "0"):
        setting("shaders", shaders)
        call("wait", ms=2500)
        for quality in ("native", "quality", "balanced", "performance"):
            setting("upscaler", "off" if quality == "native" else "dlss")
            if quality != "native":
                setting("quality", quality)
            call("wait", ms=2500)
            prefix = f"{label}-{'bsl' if shaders == '1' else 'vanilla'}-{quality}"
            status = call("status")
            (OUT / (prefix + "-status.json")).write_text(json.dumps(status, indent=2))
            for kind, motion in (("static", {}), ("pan", {"dyaw":12,"ticks":24}), ("strafe", {"dx":1,"ticks":24})):
                pose()
                result = call("capture_sequence", dir=str(OUT / (prefix + "-" + kind)), count=12, interval_ms=100, **motion)
                (OUT / (prefix + "-" + kind + ".json")).write_text(json.dumps(result, indent=2))
            pose()
            call("key", name="key.attack", down=True, ticks=24)
            result = call("capture_sequence", dir=str(OUT / (prefix + "-hand")), count=12, interval_ms=100)
            (OUT / (prefix + "-hand.json")).write_text(json.dumps(result, indent=2))
            if quality != "native":
                call("key", name="key.attack", down=True, ticks=24)
                result = call("reflexium", capture=prefix + "-hand", dir=str(OUT))
            print(prefix, flush=True)

def analyze():
    import numpy as np
    from PIL import Image, ImageDraw
    rows = []
    for label in ("before", "after"):
        for pack in ("bsl", "vanilla"):
            native_path = OUT / f"{label}-{pack}-native-static/frame_011.png"
            if not native_path.exists():
                continue
            native = np.asarray(Image.open(native_path).convert("RGB"), dtype=float)
            for quality in ("native", "quality", "balanced", "performance"):
                prefix = f"{label}-{pack}-{quality}"
                paths = sorted((OUT / (prefix + "-static")).glob("frame_*.png"))
                if len(paths) != 12:
                    continue
                frames = [np.asarray(Image.open(p).convert("RGB"), dtype=float) for p in paths]
                for region, (x,y,w,h) in {"ground":(400,1100,1000,180), "leaves":(940,490,480,380), "hand":(1790,1030,430,380)}.items():
                    ref = native[y:y+h,x:x+w]
                    crops = [f[y:y+h,x:x+w] for f in frames]
                    mse = float(np.mean((ref-crops[-1])**2))
                    temporal = float(np.mean([np.abs(b-a).mean() for a,b in zip(crops,crops[1:])]))
                    rows.append(dict(path=prefix,region=region,mse=mse,psnr=10*np.log10(255**2/max(mse,1e-12)),temporal_mae=temporal))
                for kind in ("pan", "strafe", "hand", "static"):
                    files = [OUT / f"{prefix}-{kind}/frame_{i:03}.png" for i in (0,5,11)]
                    if not all(p.exists() for p in files):
                        continue
                    # Native pixels, three selected frames. Labels occupy separate margins.
                    canvas = Image.new("RGB", (1440,440), "#202020")
                    draw = ImageDraw.Draw(canvas)
                    for col, p in enumerate(files):
                        crop = Image.open(p).crop((1780,1000,2260,1400)) if kind == "hand" else Image.open(p).crop((930,480,1410,880))
                        canvas.paste(crop, (col*480,40))
                        draw.text((col*480+8,10), f"{prefix} {kind} frame {p.stem}", fill="white")
                    canvas.save(OUT / (prefix + "-" + kind + "-contact.png"))
    (OUT / "metrics.json").write_text(json.dumps(rows,indent=2))
    print(json.dumps(rows,indent=2))

def hands():
    # Server freeze also freezes attack animation. Keep these separate from the static-world matrix.
    call("command", command="tick unfreeze")
    for shaders in ("1", "0"):
        setting("shaders", shaders)
        setting("upscaler", "dlss")
        setting("quality", "quality")
        call("wait", ms=2500)
        for enabled in ("0", "1"):
            setting("hand_motion", enabled)
            pose()
            prefix = f"swing-{'bsl' if shaders == '1' else 'vanilla'}-vectors{enabled}"
            stop = threading.Event()
            def swing():
                while not stop.is_set():
                    call("key", name="key.attack", down=True, ticks=1)
                    stop.wait(0.15)
            worker = threading.Thread(target=swing)
            worker.start()
            try:
                result = call("capture_sequence", dir=str(OUT / prefix), count=24, interval_ms=50)
            finally:
                stop.set()
                worker.join()
            (OUT / (prefix + ".json")).write_text(json.dumps(result,indent=2))
            call("key", name="key.attack", down=False)
            print(prefix,flush=True)
    setting("hand_motion", "1")
    call("command", command="tick freeze")

def water():
    setting("frame_generation", "off")
    call("command", command="tick freeze")
    for shaders in ("1","0"):
        setting("shaders",shaders)
        for quality in ("native","quality","balanced","performance"):
            setting("upscaler","off" if quality=="native" else "dlss")
            if quality!="native": setting("quality",quality)
            call("camera",x=-37.5,y=132,z=30,yaw=0,pitch=35)
            call("wait",ms=2500)
            prefix=f"water-{'bsl' if shaders=='1' else 'vanilla'}-{quality}"
            result=call("capture_sequence",dir=str(OUT/prefix),count=8,interval_ms=100)
            (OUT/(prefix+".json")).write_text(json.dumps(result,indent=2))
            print(prefix,flush=True)

def equip():
    call("command",command="tick unfreeze")
    for shaders in ("1","0"):
        setting("shaders",shaders)
        setting("upscaler","dlss")
        setting("quality","quality")
        pose()
        call("wait",ms=2500)
        for enabled in ("0","1"):
            setting("hand_motion",enabled)
            stop=threading.Event()
            def swap():
                i=0
                while not stop.is_set():
                    call("command",command="item replace entity @p weapon.mainhand with minecraft:"+("stone" if i%2 else "air"))
                    i+=1
                    stop.wait(.5)
            worker=threading.Thread(target=swap)
            worker.start()
            prefix=f"equip-{'bsl' if shaders=='1' else 'vanilla'}-vectors{enabled}"
            try:
                result=call("capture_sequence",dir=str(OUT/prefix),count=24,interval_ms=50)
            finally:
                stop.set()
                worker.join()
            (OUT/(prefix+".json")).write_text(json.dumps(result,indent=2))
            print(prefix,flush=True)
    call("command",command="item replace entity @p weapon.mainhand with minecraft:air")
    setting("hand_motion","1")
    call("command",command="tick freeze")

def control():
    options=ROOT/"run/shaderpacks/BSL_v10.1.8.zip.txt"
    original=options.read_bytes() if options.exists() else None
    try:
        options.write_text("ANIMATION_SPEED=0.00\n")
        setting("shaders","0")
        call("wait",ms=500)
        setting("shaders","1")
        setting("upscaler","dlss")
        setting("quality","quality")
        call("wait",ms=3000)
        pose()
        call("turn",dyaw=30,ticks=120,**{"async":True})
        call("wait",ms=300)
        call("reflexium",capture="no-wave-pan",dir=str(OUT))
        print("no-wave-pan",flush=True)
    finally:
        if original is None: options.unlink()
        else: options.write_bytes(original)
        setting("shaders","0")
        call("wait",ms=500)
        setting("shaders","1")
        call("wait",ms=3000)


def evidence():
    from PIL import Image, ImageDraw
    target=ROOT/"docs/validation/iq-evidence-2026-10-19/round2"
    target.mkdir(parents=True,exist_ok=True)
    for label in ("before","after"):
        for pack,quality in (("bsl","native"),("bsl","balanced"),("bsl","performance"),("vanilla","performance")):
            for kind in ("pan","strafe"):
                name=f"{label}-{pack}-{quality}-{kind}-contact.png"
                shutil.copyfile(OUT/name,target/name)
    for pack in ("bsl","vanilla"):
        canvas=Image.new("RGB",(1440,840),"#202020")
        draw=ImageDraw.Draw(canvas)
        for row,enabled in enumerate(("0","1")):
            for col,i in enumerate((3,7,12)):
                image=Image.open(OUT/f"equip-{pack}-vectors{enabled}/frame_{i:03}.png")
                canvas.paste(image.crop((1780,1000,2260,1400)),(col*480,row*420+20))
                draw.text((col*480+8,row*420),f"{pack} equip vectors {enabled} frame {i}",fill="white")
        canvas.save(target/f"equip-{pack}.png")
        canvas=Image.new("RGB",(1600,1240),"#202020")
        draw=ImageDraw.Draw(canvas)
        for row,quality in enumerate(("native","quality","balanced","performance")):
            for col,i in enumerate((0,7)):
                image=Image.open(OUT/f"water-{pack}-{quality}/frame_{i:03}.png")
                canvas.paste(image.crop((500,250,1300,540)),(col*800,row*310+20))
                draw.text((col*800+8,row*310),f"{pack} water {quality} frame {i}",fill="white")
        canvas.save(target/f"water-{pack}.png")
    for name in ("metrics.json","no-wave-pan-metrics.json","foliage-motion-visible.png"):
        shutil.copyfile(OUT/name,target/name)
    print(target)

if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    if sys.argv[1] == "analyze":
        analyze()
    elif sys.argv[1] == "hands":
        hands()
    elif sys.argv[1] == "water":
        water()
    elif sys.argv[1] == "equip":
        equip()
    elif sys.argv[1] == "control":
        control()
    elif sys.argv[1] == "evidence":
        evidence()
    else:
        capture(sys.argv[1])
