import { EditorState, Compartment } from "@codemirror/state";
import { EditorView, keymap } from "@codemirror/view";
import { basicSetup } from "codemirror";
import { indentWithTab, insertTab, undo, redo, cursorCharLeft, cursorCharRight } from "@codemirror/commands";
import { openSearchPanel } from "@codemirror/search";
import { html } from "@codemirror/lang-html";
import { css } from "@codemirror/lang-css";
import { javascript } from "@codemirror/lang-javascript";
import { json } from "@codemirror/lang-json";
import { markdown } from "@codemirror/lang-markdown";
import { python } from "@codemirror/lang-python";
import { oneDark } from "@codemirror/theme-one-dark";

const SESSION_KEY = "qe_session_v1";
const PREFS_KEY = "qe_prefs_v1";
const A = window.Android;

const themeC = new Compartment();
const fontC = new Compartment();
const wrapC = new Compartment();
const langC = new Compartment();

const mq = window.matchMedia ? window.matchMedia("(prefers-color-scheme: dark)") : null;
const isDark = () => !!(mq && mq.matches);

let prefs = { font: 15, wrap: true };
try {
  const p = JSON.parse(localStorage.getItem(PREFS_KEY));
  if (p && typeof p.font === "number") prefs.font = p.font;
  if (p && typeof p.wrap === "boolean") prefs.wrap = p.wrap;
} catch (e) {}
function savePrefs() { try { localStorage.setItem(PREFS_KEY, JSON.stringify(prefs)); } catch (e) {} }

let tabs = [];
let activeId = null;
let mode = "edit";
let view = null;

/* ---------- ekstensi editor ---------- */
function langFor(name) {
  const ext = (name.indexOf(".") >= 0 ? name.split(".").pop() : "").toLowerCase();
  switch (ext) {
    case "html": case "htm": case "xhtml": case "svg": case "xml": return html();
    case "css": return css();
    case "js": case "mjs": case "cjs": case "jsx": return javascript({ jsx: true });
    case "ts": case "tsx": return javascript({ jsx: true, typescript: true });
    case "json": return json();
    case "md": case "markdown": return markdown();
    case "py": return python();
    default: return [];
  }
}
const themeExt = () => (isDark() ? oneDark : []);
const fontExt = () => EditorView.theme({ "&": { fontSize: prefs.font + "px" } });
const wrapExt = () => (prefs.wrap ? EditorView.lineWrapping : []);
const baseTheme = EditorView.theme({
  "&": { height: "100%" },
  ".cm-scroller": {
    overflow: "auto",
    fontFamily: "ui-monospace, 'Roboto Mono', Menlo, Consolas, monospace"
  },
  ".cm-panels input, .cm-panels button": { fontSize: "16px", minHeight: "34px" },
  ".cm-panels": { fontSize: "15px" }
});

function makeState(text, name) {
  const exts = [
    keymap.of([indentWithTab]),
    basicSetup,
    langC.of(langFor(name)),
    themeC.of(themeExt()),
    fontC.of(fontExt()),
    wrapC.of(wrapExt()),
    baseTheme,
    EditorView.updateListener.of(onUpdate)
  ];
  if (text.indexOf("\r\n") >= 0) exts.push(EditorState.lineSeparator.of("\r\n"));
  return EditorState.create({ doc: text, extensions: exts });
}

function onUpdate(u) {
  if (!u.docChanged) return;
  const t = activeTab();
  if (t && !t.dirty) { t.dirty = true; renderTabs(); }
  scheduleDraft();
  schedulePreview();
}

/* ---------- tab ---------- */
const uid = () => "t" + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
const activeTab = () => tabs.find((t) => t.id === activeId) || null;
function syncActive() { const t = activeTab(); if (t && view) t.state = view.state; }

function addTab(o) {
  const name = o.name || "baru.html";
  const t = { id: o.id || uid(), uri: o.uri || "", name, dirty: !!o.dirty, state: makeState(o.text || "", name) };
  tabs.push(t);
  activate(t.id);
  return t;
}

function activate(id) {
  if (view && activeId) syncActive();
  const t = tabs.find((x) => x.id === id);
  if (!t) return;
  activeId = id;
  view.setState(t.state);
  view.dispatch({ effects: [themeC.reconfigure(themeExt()), fontC.reconfigure(fontExt()), wrapC.reconfigure(wrapExt())] });
  renderTabs();
  scheduleDraft();
  if (mode !== "edit") pushPreview();
}

function closeTab(id) {
  const t = tabs.find((x) => x.id === id);
  if (!t) return;
  if (t.dirty && !confirm("\u201C" + t.name + "\u201D belum disimpan. Tutup tanpa menyimpan?")) return;
  const i = tabs.indexOf(t);
  const wasActive = id === activeId;
  if (!wasActive) syncActive();
  tabs.splice(i, 1);
  if (!tabs.length) { activeId = null; addTab({}); persistNow(); return; }
  if (wasActive) { activeId = null; activate(tabs[Math.min(i, tabs.length - 1)].id); }
  else renderTabs();
  persistNow();
}

const tabsEl = document.getElementById("tabs");
function renderTabs() {
  tabsEl.textContent = "";
  tabs.forEach((t) => {
    const el = document.createElement("div");
    el.className = "tab" + (t.id === activeId ? " active" : "");
    const nm = document.createElement("span");
    nm.className = "nm";
    nm.textContent = t.name + (t.dirty ? " \u25CF" : "");
    const x = document.createElement("button");
    x.className = "x";
    x.textContent = "\u00D7";
    x.setAttribute("aria-label", "Tutup " + t.name);
    x.addEventListener("click", (e) => { e.stopPropagation(); closeTab(t.id); });
    el.appendChild(nm);
    el.appendChild(x);
    el.addEventListener("click", () => { if (t.id !== activeId) activate(t.id); });
    tabsEl.appendChild(el);
    if (t.id === activeId) el.scrollIntoView({ inline: "nearest", block: "nearest" });
  });
}

/* ---------- sesi / draf ---------- */
let draftTimer = null;
function scheduleDraft() { clearTimeout(draftTimer); draftTimer = setTimeout(persistNow, 700); }
function persistNow() {
  try {
    syncActive();
    const data = {
      active: activeId,
      tabs: tabs.map((t) => ({ id: t.id, uri: t.uri, name: t.name, dirty: t.dirty, text: t.state.sliceDoc() }))
    };
    localStorage.setItem(SESSION_KEY, JSON.stringify(data));
  } catch (e) {}
}
function restore() {
  let data = null;
  try { data = JSON.parse(localStorage.getItem(SESSION_KEY)); } catch (e) {}
  if (data && Array.isArray(data.tabs) && data.tabs.length) {
    data.tabs.forEach((d) => {
      const name = d.name || "baru.html";
      tabs.push({ id: d.id || uid(), uri: d.uri || "", name, dirty: !!d.dirty, state: makeState(d.text || "", name) });
    });
    const want = tabs.find((t) => t.id === data.active) || tabs[0];
    activeId = null;
    activate(want.id);
  } else {
    addTab({});
  }
}

/* ---------- pratinjau ---------- */
let previewTimer = null;
function schedulePreview() {
  if (mode !== "split") return;
  clearTimeout(previewTimer);
  previewTimer = setTimeout(pushPreview, 900);
}
const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
function pushPreview() {
  const t = activeTab();
  if (!t || !A) return;
  syncActive();
  const text = t.state.sliceDoc();
  const isHtml = /\.(html?|xhtml)$/i.test(t.name);
  const out = isHtml
    ? text
    : '<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{margin:0;padding:12px;font:14px/1.5 ui-monospace,monospace;color-scheme:light dark}pre{white-space:pre-wrap;overflow-wrap:anywhere;margin:0}</style><pre>' + esc(text) + "</pre>";
  A.preview(t.name, out);
}

/* ---------- API untuk native ---------- */
window.App = {
  openTab(info) {
    syncActive();
    const existing = tabs.find((t) => t.uri && t.uri === info.uri);
    if (existing) {
      if (!existing.dirty) { existing.name = info.name; existing.state = makeState(info.text, info.name); }
      activeId = null;
      activate(existing.id);
      return;
    }
    if (tabs.length === 1) {
      const t = tabs[0];
      if (!t.uri && !t.dirty && t.state.doc.length === 0) { tabs = []; activeId = null; }
    }
    addTab({ uri: info.uri, name: info.name, text: info.text });
  },
  newTab() { syncActive(); addTab({}); view.focus(); },
  requestSave(asNew) {
    const t = activeTab();
    if (!t || !A) return;
    syncActive();
    A.save(asNew ? "" : t.uri, t.name, t.state.sliceDoc(), t.id);
  },
  saved(id, uri, name) {
    const t = tabs.find((x) => x.id === id);
    if (!t) return;
    t.uri = uri;
    t.dirty = false;
    if (name && name !== t.name) {
      t.name = name;
      const eff = langC.reconfigure(langFor(name));
      if (id === activeId) view.dispatch({ effects: eff });
      else t.state = t.state.update({ effects: eff }).state;
    }
    renderTabs();
    persistNow();
    if (mode !== "edit") pushPreview();
  },
  setMode(m) { mode = m; if (m !== "edit") pushPreview(); },
  pushPreview,
  find() { openSearchPanel(view); view.focus(); },
  font(d) {
    prefs.font = Math.max(10, Math.min(30, prefs.font + d));
    savePrefs();
    view.dispatch({ effects: fontC.reconfigure(fontExt()) });
  },
  toggleWrap() {
    prefs.wrap = !prefs.wrap;
    savePrefs();
    view.dispatch({ effects: wrapC.reconfigure(wrapExt()) });
  },
  persist: persistNow
};

/* ---------- bar simbol ---------- */
function insertText(s, back) {
  const sel = view.state.selection.main;
  view.dispatch({
    changes: { from: sel.from, to: sel.to, insert: s },
    selection: { anchor: sel.from + s.length - (back || 0) },
    userEvent: "input"
  });
  view.focus();
}
const KEYS = [
  { l: "\u21E5", run: (v) => insertTab(v) },
  { l: "\u21B6", run: undo },
  { l: "\u21B7", run: redo },
  { l: "\u25C0", run: cursorCharLeft },
  { l: "\u25B6", run: cursorCharRight },
  { l: "{ }", ins: "{}", back: 1 },
  { l: "( )", ins: "()", back: 1 },
  { l: "[ ]", ins: "[]", back: 1 },
  { l: "< >", ins: "<>", back: 1 },
  { l: '" "', ins: '""', back: 1 },
  { l: "' '", ins: "''", back: 1 },
  { l: "`", ins: "``", back: 1 },
  { l: "/", ins: "/" }, { l: ";", ins: ";" }, { l: ":", ins: ":" }, { l: "=", ins: "=" },
  { l: "!", ins: "!" }, { l: "&", ins: "&" }, { l: "|", ins: "|" }, { l: "#", ins: "#" },
  { l: "$", ins: "$" }, { l: "_", ins: "_" }, { l: "-", ins: "-" }, { l: "+", ins: "+" },
  { l: "*", ins: "*" }, { l: "\\", ins: "\\" }, { l: "%", ins: "%" }, { l: "@", ins: "@" },
  { l: "Cari", run: (v) => openSearchPanel(v), wide: true }
];
const symEl = document.getElementById("symbols");
KEYS.forEach((k) => {
  const b = document.createElement("button");
  b.type = "button";
  b.className = "sym" + (k.wide ? " wide" : "");
  b.textContent = k.l;
  b.addEventListener("mousedown", (e) => e.preventDefault());   // jaga fokus & keyboard
  b.addEventListener("click", () => {
    if (k.run) { k.run(view); view.focus(); } else insertText(k.ins, k.back);
  });
  symEl.appendChild(b);
});

/* ---------- mulai ---------- */
view = new EditorView({ state: makeState("", "baru.html"), parent: document.getElementById("editor") });
restore();
if (mq && mq.addEventListener) {
  mq.addEventListener("change", () => view.dispatch({ effects: themeC.reconfigure(themeExt()) }));
}
document.addEventListener("visibilitychange", () => { if (document.hidden) persistNow(); });
window.addEventListener("pagehide", persistNow);
if (A && A.ready) A.ready();
