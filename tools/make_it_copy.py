#!/usr/bin/env python3
"""Make the "IT copy" of Pocket SAM: same app, with every way of sending data out removed.
The original files are only read, never changed. Run from the repo folder:
    python3 tools/make_it_copy.py OUTPUT_FOLDER
It stops with an error if Pocket SAM changed so that a removal no longer matches, so nothing slips through."""
import os, re, shutil, sys, zipfile

src = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
out = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else "it-copy")
site = os.path.join(out, "pocketsam")
shutil.rmtree(out, ignore_errors=True); os.makedirs(site)

NO_NET = ('<script>/* IT copy: this app sends no data anywhere. Every network call is replaced by noNet(). */'
          'const noNet = () => Promise.reject(new Error("This copy does not send data anywhere"));</script>\n'
          '<style>#refreshBtn,#syncStatus,#dynLink{display:none!important}</style>\n')

def cut(s, start, end, keep):
    """Replace the code from `start` up to (not including) `end` with `keep`."""
    i = s.find(start); j = s.find(end, i)
    if i < 0 or j < 0: sys.exit(f"can't find the block starting {start[:50]!r}. Pocket SAM changed; update this script.")
    return s[:i] + keep + s[j:]

def patch(name, swaps, must_not_contain, cuts=()):
    s = open(os.path.join(src, name), encoding="utf-8").read()
    for start, end, keep in cuts: s = cut(s, start, end, keep)
    for a, b in swaps:
        n = s.count(a)
        if n < 1: sys.exit(f"{name}: can't find {a[:60]!r}. Pocket SAM changed; update this script.")
        s = s.replace(a, b)
    s = s.replace("<head>", "<head>\n" + NO_NET, 1)
    for bad in must_not_contain:
        if re.search(bad, s): sys.exit(f"{name}: still contains {bad!r}")
    open(os.path.join(site, name), "w", encoding="utf-8").write(s)

patch("index.html", [
    # Team sync (Google sheet): never set up, never contacted
    ('let syncCfg = lsGet(SYNC_KEY, null);', 'let syncCfg = null;'),
    ('const r = await fetch(cfg.url, { method: "POST", body: JSON.stringify({ pass: cfg.pass, ...body }) });', 'const r = await noNet();'),
    # Dynamics test links (visits, bookings, day): always off, never contacted
    ('let bkUrl = lsGet(BK_KEY, "")', 'let bkUrl = ""'),
    ('let dynUrl = lsGet(DYN_KEY, "");', 'let dynUrl = "";'),
    ('let dayUrl = lsGet(DAY_URL_KEY, "");', 'let dayUrl = "";'),
    ('const r = await fetch(bkUrl, { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });', 'const r = await noNet();'),
    ('async function postDyn(body) { const r = await fetch(dynUrl, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }); return r; }', 'async function postDyn(body) { return noNet(); }'),
    ('const r = await fetch(dayUrl, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });', 'const r = await noNet();'),
    ('<a href="#" id="dynLink">Dynamics test: off</a>', '<a href="#" id="dynLink" hidden></a>'),
], [r"\bfetch\(", r"script\.google"], cuts=[
    # The screen for connecting the Google sheet is taken out completely
    ('function setupSync(problem) {', '$("refreshBtn").onclick', 'function setupSync() { /* IT copy: no team sync */ }\n'),
])
patch("tanks.html", [
    ('const cfg = lsGet("pocketsam-sync", null);', 'const cfg = null;'),
    ('const r = await fetch(cfg.url, { method: "POST", body: JSON.stringify({ pass: cfg.pass, action: "push", ops: ops || [], by }) });', 'const r = await noNet();'),
], [r"\bfetch\("])
for f in ["sw.js", "manifest.webmanifest", "icon-192.png", "icon-512.png"]:
    shutil.copy(os.path.join(src, f), site)

open(os.path.join(site, "README-IT.txt"), "w", encoding="utf-8").write("""Pocket SAM

What it is
  A field app for odor-control techs: sites, readings, chemical tank forecast, Delta Dose summary, Take 5, parts used.
  Plain HTML and JavaScript. No server code, no build step, no accounts or passwords.

Where data is kept
  In the browser on each device (IndexedDB and localStorage), under the address it's served from.
  Users can save a backup file themselves (EXPORT SAM BACKUP, .json) and a readings file (EXPORT READINGS CSV, .csv).

Outside addresses it uses, only when the user taps
  - DIRECTIONS opens Google Maps to that site's GPS point (www.google.com/maps).
  - The site-group map loads a map library (cdnjs.cloudflare.com, Leaflet 1.9.4) and street-map pictures
    (tile.openstreetmap.org). The site pins are drawn on the device.
  Location (GPS) is used on the device to show distances and save a site's location, and only with the user's OK.

How to host it
  Any static web host over HTTPS (needed for offline use and location), e.g. Azure Static Web Apps or IIS.
  Keep these files together in one folder: index.html, tanks.html, sw.js, manifest.webmanifest, icon-192.png, icon-512.png.
  sw.js keeps a copy of these files on the device so the app opens without signal.
  On phones: open the address, then "Add to Home Screen" (iPhone: Share menu; Android: browser menu).
""")

zp = os.path.join(out, "PocketSAM-IT-copy.zip")
with zipfile.ZipFile(zp, "w", zipfile.ZIP_DEFLATED) as z:
    for f in sorted(os.listdir(site)): z.write(os.path.join(site, f), "pocketsam/" + f)
print(zp)
