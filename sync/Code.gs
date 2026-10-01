/**
 * Pocket SAM sync script.
 * Paste this whole file into your Google Sheet: Extensions -> Apps Script.
 * 1. Change the password on the next line (keep the quote marks).
 * 2. Deploy -> New deployment -> Web app -> Execute as: Me, Who has access: Anyone -> Deploy.
 * 3. Copy the Web app link into Pocket SAM (tap REFRESH the first time).
 */
const PASSCODE = "change-me";

const HIST = "_history";
const SITES = "Sites";        // one row per site: the full record the app uses
const DELETED = "Deleted";    // sites/counties removed, so other phones remove them too
const LOG = "Readings";       // easy-to-read list of every SAVE / CONFIRM

function doGet() {
  return ContentService.createTextOutput("Pocket SAM sync is running.");
}

function doPost(e) {
  let req;
  try { req = JSON.parse(e.postData.contents); } catch (x) { return out({ ok: false, error: "Bad request" }); }
  if (!req || req.pass !== PASSCODE) return out({ ok: false, error: "Wrong password" });
  if (req.action === "ping") return out({ ok: true });

  const lock = LockService.getScriptLock();
  lock.waitLock(25000);
  try {
    if (req.action === "push") push(req.ops || [], req.by || "");
    return out({ ok: true, data: readAll(), deleted: readDeleted() });
  } finally {
    lock.releaseLock();
  }
}

function out(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}

function sheet(name, header) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  let sh = ss.getSheetByName(name);
  if (!sh) { sh = ss.insertSheet(name); if (header) sh.appendRow(header); sh.setFrozenRows(1); }
  return sh;
}

function sitesSheet() { return sheet(SITES, ["County/City", "Site", "Updated", "By", "Data (used by the app, don't edit)"]); }
function deletedSheet() { return sheet(DELETED, ["County/City", "Site (blank = whole county)", "Deleted", "By"]); }

function readAll() {
  const rows = sitesSheet().getDataRange().getValues().slice(1);
  const db = {};
  rows.forEach(r => {
    if (!r[0] || !r[1]) return;
    let rec = {};
    try { rec = JSON.parse(r[4] || "{}"); } catch (x) {}
    (db[r[0]] = db[r[0]] || {})[r[1]] = rec;
  });
  return db;
}

function readDeleted() {
  return deletedSheet().getDataRange().getValues().slice(1)
    .filter(r => r[0]).map(r => ({ c: String(r[0]), s: String(r[1] || "") }));
}

function push(ops, by) {
  const sh = sitesSheet(), del = deletedSheet();
  const values = sh.getDataRange().getValues();
  const index = {};                       // "county\u0001site" -> sheet row number
  for (let i = 1; i < values.length; i++) index[values[i][0] + "\u0001" + values[i][1]] = i + 1;
  const now = new Date();

  ops.forEach(op => {
    const key = op.c + "\u0001" + (op.s || "");
    if (op.op === "put" && op.c && op.s) {
      const row = index[key];
      let existing = null;
      if (row) { try { existing = JSON.parse(sh.getRange(row, 5).getValue() || "{}"); } catch (x) {} }
      const merged = mergeSite(existing, op.rec || {}, true);
      const line = [op.c, op.s, now, by, JSON.stringify(merged)];
      if (row) sh.getRange(row, 1, 1, 5).setValues([line]);
      else { sh.appendRow(line); index[key] = sh.getLastRow(); }
      removeDeleted(del, op.c, op.s);
      if (op.log) logReading(op.c, op.s, by, op.log);
    } else if (op.op === "delReading" && op.c && op.s && op.date) {
      const row = index[key];
      if (!row) return;
      let rec = {};
      try { rec = JSON.parse(sh.getRange(row, 5).getValue() || "{}"); } catch (x) {}
      if (Array.isArray(rec[HIST])) {
        rec[HIST] = rec[HIST].filter(e => e.date !== op.date);
        if (!rec[HIST].length) delete rec[HIST];
      }
      sh.getRange(row, 3, 1, 3).setValues([[now, by, JSON.stringify(rec)]]);
    } else if (op.op === "del" && op.c) {
      // Delete one site, or a whole county when op.s is blank
      const data = sh.getDataRange().getValues();
      for (let i = data.length - 1; i >= 1; i--) {
        if (data[i][0] === op.c && (!op.s || data[i][1] === op.s)) sh.deleteRow(i + 1);
      }
      for (const k in index) delete index[k];
      const fresh = sh.getDataRange().getValues();
      for (let i = 1; i < fresh.length; i++) index[fresh[i][0] + "\u0001" + fresh[i][1]] = i + 1;
      removeDeleted(del, op.c, op.s || "");
      del.appendRow([op.c, op.s || "", now, by]);
    }
  });
}

function removeDeleted(del, c, s) {
  const data = del.getDataRange().getValues();
  for (let i = data.length - 1; i >= 1; i--) {
    // Re-adding a site clears its own delete mark and a whole-county mark for that county
    if (data[i][0] === c && (String(data[i][1] || "") === s || !data[i][1])) del.deleteRow(i + 1);
  }
}

function logReading(c, s, by, log) {
  const header = ["Saved", "By", "County/City", "Site", "Date", "Complete"].concat(log.cols || []);
  const sh = sheet(LOG, header);
  // When the app adds new boxes, add their column headings too
  const current = sh.getRange(1, 1, 1, Math.max(sh.getLastColumn(), 1)).getValues()[0];
  if (header.length > current.length || header.some((h, i) => h !== current[i])) sh.getRange(1, 1, 1, header.length).setValues([header]);
  sh.appendRow([new Date(), by, c, s, log.date || "", log.complete ? "Yes" : ""].concat(log.vals || []));
}

/* Same rules as the app:
   history: same date -> newer one wins; different dates -> both kept.
   Complete mark: from the phone that just saved (takeIncomingComplete), otherwise the most recent. */
function mergeSite(local, inc, takeIncomingComplete) {
  if (!local || typeof local !== "object") return inc;
  const byDate = {};
  (Array.isArray(local[HIST]) ? local[HIST] : []).forEach(e => { if (e && e.date) byDate[e.date] = e; });
  (Array.isArray(inc[HIST]) ? inc[HIST] : []).forEach(e => { if (e && e.date) byDate[e.date] = e; });
  const h = Object.keys(byDate).sort().map(d => byDate[d]);
  let merged;
  if (h.length) {
    const latest = h[h.length - 1];
    merged = {};
    Object.keys(latest).forEach(k => { if (k !== "date" && k !== "savedAt" && k !== "by" && k !== "complete") merged[k] = latest[k]; });
    merged[HIST] = h;
  } else {
    merged = Object.assign({}, local, inc);
  }
  delete merged.completeMonth; delete merged.completedOn;
  let mark = null;
  if (takeIncomingComplete) mark = inc.completeMonth ? inc : null;
  else {
    const marks = [local, inc].filter(r => r.completeMonth)
      .sort((a, b) => String(a.completedOn || a.completeMonth).localeCompare(String(b.completedOn || b.completeMonth)));
    mark = marks.length ? marks[marks.length - 1] : null;
  }
  if (mark) { merged.completeMonth = mark.completeMonth; if (mark.completedOn) merged.completedOn = mark.completedOn; }
  return merged;
}
