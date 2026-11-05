"""Profile the AMTV Lite page (music.apple.com) on an Android TV device through Chrome DevTools over adb.

usage: python -P tools/profile_page.py <seconds> <out.json>
Prints a per-second timeline of main-thread work and, at the end, the hottest functions and the
elements with running CSS animations (class names only, never page text).
Requires a debug build (WebView debugging on) and `adb forward` done by the caller.
"""
import asyncio
import json
import sys
import urllib.request
from collections import Counter

import websockets

PORT = 9222


async def main(seconds, out_path):
    pages = json.load(urllib.request.urlopen(f"http://127.0.0.1:{PORT}/json"))
    page = next(p for p in pages if "music.apple.com" in p.get("url", "") and p.get("type") == "page")
    async with websockets.connect(page["webSocketDebuggerUrl"], max_size=2**28) as ws:
        msg_id = 0

        async def call(method, params=None):
            nonlocal msg_id
            msg_id += 1
            mine = msg_id
            await ws.send(json.dumps({"id": mine, "method": method, "params": params or {}}))
            while True:
                m = json.loads(await ws.recv())
                if m.get("id") == mine:
                    return m.get("result", {})

        async def metrics():
            r = await call("Performance.getMetrics")
            return {m["name"]: m["value"] for m in r["metrics"]}

        await call("Performance.enable")
        await call("Profiler.enable")
        await call("Profiler.setSamplingInterval", {"interval": 2000})
        await call("Profiler.start")
        prev = await metrics()
        timeline = []
        print(" t  task%  script%  layout%  style%  heapMB  url")
        for t in range(1, seconds + 1):
            await asyncio.sleep(1)
            cur = await metrics()
            d = {k: cur.get(k, 0) - prev.get(k, 0) for k in ("TaskDuration", "ScriptDuration", "LayoutDuration", "RecalcStyleDuration")}
            href = (await call("Runtime.evaluate", {"expression": "location.pathname", "returnByValue": True}))["result"].get("value", "")
            row = {"t": t, **{k: round(v * 100) for k, v in d.items()}, "heapMB": round(cur.get("JSHeapUsedSize", 0) / 2**20), "path": href}
            timeline.append(row)
            print(f"{t:2d}  {row['TaskDuration']:5d}  {row['ScriptDuration']:7d}  {row['LayoutDuration']:7d}  {row['RecalcStyleDuration']:6d}  {row['heapMB']:6d}  {href}")
            prev = cur
        prof = (await call("Profiler.stop"))["profile"]

        # self time per function
        nodes = {n["id"]: n for n in prof["nodes"]}
        counts = Counter(prof.get("samples", []))
        self_ms = Counter()
        interval = (prof["endTime"] - prof["startTime"]) / max(1, len(prof.get("samples", []))) / 1000
        for node_id, n in counts.items():
            cf = nodes[node_id]["callFrame"]
            url = cf.get("url", "").split("/")[-1][:40]
            name = cf.get("functionName") or "(anonymous)"
            self_ms[f"{name} @ {url}:{cf.get('lineNumber', 0)}"] += n * interval
        print("\nhottest functions (self ms):")
        for k, v in self_ms.most_common(25):
            print(f"  {v:8.0f}  {k}")

        anim_js = """
          (function () {
            var out = {};
            document.getAnimations().forEach(function (a) {
              var el = a.effect && a.effect.target;
              var key = el ? (el.tagName + '.' + String(el.className && el.className.baseVal !== undefined ? el.className.baseVal : el.className).split(' ').slice(0, 3).join('.')) : '?';
              out[key] = (out[key] || 0) + 1;
            });
            return JSON.stringify({ animations: out, canvases: document.querySelectorAll('canvas').length,
                                    videos: document.querySelectorAll('video').length,
                                    nodes: document.getElementsByTagName('*').length });
          })()
        """
        snap = json.loads((await call("Runtime.evaluate", {"expression": anim_js, "returnByValue": True}))["result"]["value"])
        print("\nend state:", json.dumps(snap, indent=1)[:3000])
        json.dump({"timeline": timeline, "hot": self_ms.most_common(60), "end": snap}, open(out_path, "w"), indent=1)


asyncio.run(main(int(sys.argv[1]), sys.argv[2]))
