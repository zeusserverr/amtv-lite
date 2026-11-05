"""A/B: time the style recalculation when re-opening the current page, with and without our injected CSS.

usage: python -P tools/ab_playlist.py
Uses history.back()/forward() (the page's own navigation), so playback is not touched.
"""
import asyncio
import json
import urllib.request

import websockets

FIND = "Array.prototype.filter.call(document.querySelectorAll('style'), function (s) { return s.textContent.indexOf('backdrop-filter: none !important') >= 0; })"


async def main():
    pages = json.load(urllib.request.urlopen("http://127.0.0.1:9222/json"))
    page = next(p for p in pages if "music.apple.com" in p.get("url", "") and p.get("type") == "page")
    async with websockets.connect(page["webSocketDebuggerUrl"], max_size=2**26) as ws:
        n = 0

        async def call(method, params=None):
            nonlocal n
            n += 1
            mine = n
            await ws.send(json.dumps({"id": mine, "method": method, "params": params or {}}))
            while True:
                m = json.loads(await ws.recv())
                if m.get("id") == mine:
                    return m.get("result", {})

        async def ev(expr):
            r = await call("Runtime.evaluate", {"expression": expr, "returnByValue": True, "awaitPromise": True})
            return r.get("result", {}).get("value")

        async def metric():
            r = await call("Performance.getMetrics")
            d = {m["name"]: m["value"] for m in r["metrics"]}
            return d["RecalcStyleDuration"], d["TaskDuration"], d["LayoutDuration"]

        await call("Performance.enable")
        path = await ev("location.pathname")
        print("page:", path)

        async def reopen(label):
            await ev("history.back()")
            await asyncio.sleep(6)
            s0, t0, l0 = await metric()
            await ev("history.forward()")
            await asyncio.sleep(18)
            s1, t1, l1 = await metric()
            back = await ev("location.pathname")
            print(f"{label:12s} style {s1 - s0:6.2f}s  layout {l1 - l0:5.2f}s  total main-thread {t1 - t0:6.2f}s  (now on {back})")

        await reopen("with CSS")
        removed = await ev(f"(function(){{ var a = {FIND}; window.__tvSaved = a.map(function (s) {{ return s.textContent; }}); a.forEach(function (s) {{ s.remove(); }}); return a.length; }})()")
        print("removed our style elements:", removed)
        await reopen("without CSS")
        restored = await ev("(function(){ (window.__tvSaved || []).forEach(function (t) { var s = document.createElement('style'); s.textContent = t; document.head.appendChild(s); }); return (window.__tvSaved || []).length; })()")
        print("restored:", restored)


asyncio.run(main())
