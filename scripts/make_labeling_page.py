#!/usr/bin/env python3
"""
Creates a local HTML page for labeling a sheet made by make_labeling_sheet.py.

The page shows each image with its numbered damage boxes and, next to it,
one row per damage with drop-downs for part, severity and action (allowed
values only) and a comment field. Progress is kept in the browser while
working. "Save CSV" downloads the filled sheet in exactly the same format
(UTF-8, ';' separated), ready for compare_labels.py / build_ground_truth.py.

Images are not copied: the page refers to them by a relative path, so keep
the page where it was generated. Open it in Chrome, Edge or Firefox.

Usage:
  python make_labeling_page.py --sheet ../benchmark/labels_dev_A.csv \
      --images-dir ../data/hitl/SYNDCAR/images --out ../benchmark/labeling_dev_A.html
"""

import argparse
import json
import os
import sys

from labels_io import SHEET_COLUMNS, read_sheet
from schema_values import ACTIONS, PARTS, SEVERITIES

RULES_HTML = """
<b>Severity</b> &mdash; minor: small scratches/scuffs (&lt; 5 cm), short cracks that do not spread;
moderate: long or deep scratches with paint damage, cracked but intact glass or light;
severe: shattered glass, broken light, cracks threatening the part or safety.<br>
<b>Action</b> &mdash; repair: scratch; crack if the part is not broken;
replacement: shattered glass, broken light, heavily damaged parts.<br>
<b>Part</b> &mdash; pre-filled automatically where possible (see comment); check it.
light = headlight or tail light; fender includes quarter panels; bumper includes front/rear panels;
windshield = front or rear windshield; other = roof, rocker panel, license plate.
"""

PAGE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Labeling: __TITLE__</title>
<style>
  body { font-family: system-ui, sans-serif; margin: 0; display: flex; height: 100vh; }
  #left { flex: 3; display: flex; flex-direction: column; min-width: 0; background: #222; }
  #bar { padding: 8px; background: #333; color: #eee; display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
  #bar button { padding: 4px 10px; }
  #stage { flex: 1; position: relative; overflow: auto; }
  #wrap { position: relative; display: inline-block; }
  #wrap img { display: block; max-width: none; }
  #wrap svg { position: absolute; left: 0; top: 0; }
  #right { flex: 2; overflow: auto; padding: 10px; font-size: 14px; }
  table { border-collapse: collapse; width: 100%; }
  td, th { border-bottom: 1px solid #ddd; padding: 4px; vertical-align: top; }
  tr.sel { background: #fff3c4; }
  tr.done td:first-child { color: #2a7a2a; font-weight: bold; }
  select, input { font-size: 13px; }
  .rules { font-size: 12px; color: #444; background: #f4f4f4; padding: 6px; margin-bottom: 8px; }
  .muted { color: #777; font-size: 12px; }
</style>
</head>
<body>
<div id="left">
  <div id="bar">
    <button id="prev">&larr; Prev</button>
    <span id="pos"></span>
    <button id="next">Next &rarr;</button>
    <label>Zoom <input id="zoom" type="range" min="10" max="100" value="30"></label>
    <span id="progress"></span>
    <button id="save"><b>Save CSV</b></button>
  </div>
  <div id="stage"><div id="wrap"><img id="img" alt=""><svg id="svg"></svg></div></div>
</div>
<div id="right">
  <div class="rules">__RULES__</div>
  <div id="fname" class="muted"></div>
  <table><thead><tr><th>#</th><th>type</th><th>part</th><th>severity</th><th>action</th><th>comment</th></tr></thead>
  <tbody id="rows"></tbody></table>
</div>
<script>
const DATA = __DATA__;
const COLUMNS = __COLUMNS__;
const OPTIONS = __OPTIONS__;
const STORE_KEY = "labeling:" + DATA.sheet;

// restore unsaved work from the browser, if any
try {
  const saved = JSON.parse(localStorage.getItem(STORE_KEY) || "null");
  if (saved && saved.length === DATA.rows.length) {
    saved.forEach((s, i) => { if (s.annotation_id === DATA.rows[i].annotation_id) Object.assign(DATA.rows[i], s); });
  }
} catch (e) { /* storage unavailable: work is kept until the page is closed */ }

const images = [...new Set(DATA.rows.map(r => r.file_name))];
let current = 0, selected = null;

function persist() {
  try { localStorage.setItem(STORE_KEY, JSON.stringify(DATA.rows)); } catch (e) { }
}
function isDone(r) { return r.part && r.severity && r.action; }
function updateProgress() {
  const done = DATA.rows.filter(isDone).length;
  document.getElementById("progress").textContent = `done ${done} / ${DATA.rows.length}`;
}
function select(id) {
  selected = id;
  document.querySelectorAll("#rows tr").forEach(tr => tr.classList.toggle("sel", tr.dataset.id === id));
  document.querySelectorAll("#svg rect").forEach(r => r.setAttribute("stroke", r.dataset.id === id ? "#ffd400" : "#ff3b3b"));
}
function makeSelect(row, field) {
  const s = document.createElement("select");
  s.append(new Option("", ""));
  OPTIONS[field].forEach(v => s.append(new Option(v, v)));
  s.value = row[field] || "";
  s.onchange = () => { row[field] = s.value; persist(); render(false); };
  s.onfocus = () => select(row.annotation_id);
  return s;
}
function render(reloadImage = true) {
  const file = images[current];
  const rows = DATA.rows.filter(r => r.file_name === file);
  document.getElementById("pos").textContent = `image ${current + 1} / ${images.length}`;
  document.getElementById("fname").textContent = file;
  const img = document.getElementById("img");
  if (reloadImage) img.src = DATA.image_base + encodeURIComponent(file);

  const tbody = document.getElementById("rows");
  tbody.innerHTML = "";
  rows.forEach((r, i) => {
    const tr = document.createElement("tr");
    tr.dataset.id = r.annotation_id;
    if (isDone(r)) tr.classList.add("done");
    const num = document.createElement("td"); num.textContent = i + 1;
    const type = document.createElement("td"); type.textContent = r.damage_type;
    const part = document.createElement("td"); part.append(makeSelect(r, "part"));
    const sev = document.createElement("td"); sev.append(makeSelect(r, "severity"));
    const act = document.createElement("td"); act.append(makeSelect(r, "action"));
    const com = document.createElement("td");
    const inp = document.createElement("input"); inp.value = r.comment || ""; inp.size = 22;
    inp.oninput = () => { r.comment = inp.value; persist(); };
    inp.onfocus = () => select(r.annotation_id);
    com.append(inp);
    tr.append(num, type, part, sev, act, com);
    tr.onclick = () => select(r.annotation_id);
    tbody.append(tr);
  });
  drawBoxes(rows);
  updateProgress();
  if (selected) select(selected);
}
function drawBoxes(rows) {
  const img = document.getElementById("img");
  const svg = document.getElementById("svg");
  const apply = () => {
    const w = img.naturalWidth, h = img.naturalHeight;
    const zoom = document.getElementById("zoom").value / 100;
    img.style.width = (w * zoom) + "px";
    svg.setAttribute("width", w * zoom); svg.setAttribute("height", h * zoom);
    svg.setAttribute("viewBox", `0 0 ${w} ${h}`);
    svg.innerHTML = "";
    const stroke = Math.max(3, w / 400), font = Math.max(24, w / 50);
    rows.forEach((r, i) => {
      const [x, y, bw, bh] = [r.bbox_x, r.bbox_y, r.bbox_w, r.bbox_h].map(Number);
      const rect = document.createElementNS("http://www.w3.org/2000/svg", "rect");
      Object.entries({x, y, width: bw, height: bh, fill: "none", stroke: "#ff3b3b", "stroke-width": stroke})
        .forEach(([k, v]) => rect.setAttribute(k, v));
      rect.dataset.id = r.annotation_id;
      rect.style.cursor = "pointer";
      rect.style.pointerEvents = "all";
      rect.onclick = () => select(r.annotation_id);
      const label = document.createElementNS("http://www.w3.org/2000/svg", "text");
      Object.entries({x: x + 4, y: Math.max(font, y - 6), fill: "#ffd400", "font-size": font, "font-weight": "bold",
        stroke: "#000", "stroke-width": font / 12}).forEach(([k, v]) => label.setAttribute(k, v));
      label.textContent = i + 1;
      svg.append(rect, label);
    });
    if (selected) select(selected);
  };
  if (img.complete && img.naturalWidth) apply(); else img.onload = apply;
}
function csvCell(v) {
  v = v == null ? "" : String(v);
  return /[;"\\n\\r]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
}
document.getElementById("save").onclick = () => {
  const lines = [COLUMNS.join(";")].concat(DATA.rows.map(r => COLUMNS.map(c => csvCell(r[c])).join(";")));
  const blob = new Blob(["\\ufeff" + lines.join("\\r\\n") + "\\r\\n"], {type: "text/csv;charset=utf-8"});
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = DATA.sheet;
  a.click();
};
document.getElementById("prev").onclick = () => { current = (current - 1 + images.length) % images.length; selected = null; render(); };
document.getElementById("next").onclick = () => { current = (current + 1) % images.length; selected = null; render(); };
document.getElementById("zoom").oninput = () => render(false);
render();
</script>
</body>
</html>
"""


def build_page(rows, sheet_name, image_base):
    data = {"sheet": sheet_name, "image_base": image_base, "rows": rows}
    options = {"part": PARTS, "severity": SEVERITIES, "action": ACTIONS}
    page = PAGE.replace("__TITLE__", sheet_name).replace("__RULES__", RULES_HTML)
    page = page.replace("__COLUMNS__", json.dumps(SHEET_COLUMNS))
    page = page.replace("__OPTIONS__", json.dumps(options))
    # "</" must not appear inside the inline script
    return page.replace("__DATA__", json.dumps(data, ensure_ascii=False).replace("</", "<\\/"))


def image_base_url(out_path, images_dir):
    rel = os.path.relpath(os.path.abspath(images_dir), os.path.dirname(os.path.abspath(out_path)))
    return rel.replace(os.sep, "/").rstrip("/") + "/"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--sheet", required=True, help="CSV from make_labeling_sheet.py (or a partly filled one)")
    parser.add_argument("--images-dir", required=True)
    parser.add_argument("--out", required=True, help="HTML page to create")
    args = parser.parse_args(argv)

    rows = read_sheet(args.sheet)
    missing = sorted({r["file_name"] for r in rows
                      if not os.path.exists(os.path.join(args.images_dir, r["file_name"]))})
    if missing:
        print(f"ERROR: {len(missing)} image(s) not found in {args.images_dir}, e.g. {missing[0]}", file=sys.stderr)
        return 1

    page = build_page(rows, os.path.basename(args.sheet), image_base_url(args.out, args.images_dir))
    with open(args.out, "w", encoding="utf-8") as f:
        f.write(page)
    print(f"{len(rows)} damages on {len({r['file_name'] for r in rows})} images -> {args.out}")
    print("Open it in a browser. 'Save CSV' downloads the filled sheet with the same file name.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
