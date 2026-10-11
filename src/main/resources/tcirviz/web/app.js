// tcirviz - an interactive visualiser for the TornadoVM TC compiler.
// Copyright (c) 2026, Michalis Papadimitriou. SPDX-License-Identifier: GPL-2.0-only

import { esc, forKind, java as hlJava } from './highlight.js';
import { STAGES, PASSES, opcodeHelp } from './docs.js';

const $ = (s, el = document) => el.querySelector(s);
const store = {
  get(k, d) { try { const v = localStorage.getItem('tcirviz.' + k); return v === null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('tcirviz.' + k, JSON.stringify(v)); } catch { /* private mode */ } },
};

const S = {
  info: null,
  backend: store.get('backend', 'cuda'),
  targetOptions: store.get('targetOptions', {}),
  method: null,
  className: null,    // a class of the application compiled from its class file (no source found)
  args: {},           // parameter name -> value the kernel is specialised for
  result: null,
  fn: 0,
  snaps: [],          // snapshots of the current function: bytecode, IR stages and passes, structured, source
  irPrev: [],         // index of the previous snapshot of the same kind family, for diffs and deltas
  panes: { a: { idx: -1, diff: false, graph: false }, b: { idx: -1, diff: false, graph: false } },
  sel: { ids: new Set(), line: null, bci: null },
  showUnchanged: store.get('showUnchanged', false),
  life: new Map(),    // value id -> { first, last, gone } snapshot indices
  succ: new Map(),    // value id -> the value that replaced it
  pred: new Map(),    // value id -> the value it replaced
  stale: false,
};

// ------------------------------------------------------------------ boot

async function boot() {
  const theme = store.get('theme', matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
  document.documentElement.dataset.theme = theme;
  S.info = await (await fetch('api/info')).json();
  renderBackends();
  for (const name of S.info.examples) $('#example').append(new Option(name, name));
  $('#show-unchanged').checked = S.showUnchanged;
  $('#open').hidden = !S.info.app;
  wire();
  const source = S.info.initialSource || store.get('source', null);
  if (source) {
    setSource(source);
    compile();
  } else {
    await loadExample('VectorAdd');
  }
}

function renderBackends() {
  const seg = $('#backends');
  seg.innerHTML = '';
  for (const b of S.info.backends) {
    const btn = document.createElement('button');
    btn.textContent = b.label;
    btn.title = b.note;
    btn.setAttribute('role', 'radio');
    btn.setAttribute('aria-checked', String(b.id === S.backend));
    btn.onclick = () => { S.backend = b.id; store.set('backend', b.id); renderBackends(); compile(); };
    seg.append(btn);
  }
  const opts = $('#backend-options');
  opts.innerHTML = '';
  const backend = S.info.backends.find((b) => b.id === S.backend) || S.info.backends[0];
  for (const [name, choices] of Object.entries(backend.options || {})) {
    const sel = document.createElement('select');
    sel.title = name;
    for (const c of choices) sel.append(new Option(`${label(name)} ${c}`, c));
    sel.value = S.targetOptions[name] ?? choices[0];
    sel.onchange = () => { S.targetOptions[name] = sel.value; store.set('targetOptions', S.targetOptions); compile(); };
    opts.append(sel);
  }
}

const label = (name) => ({ computeCapability: 'sm_', fp64: 'fp64', fp16: 'fp16' }[name] ?? name);

async function openClass(name) {
  const r = await (await fetch('api/open?name=' + encodeURIComponent(name))).json();
  S.method = null;
  S.args = {};
  S.sel = { ids: new Set(), line: null, bci: null };
  $('#example').value = '';
  if (r.source) {
    S.className = null;
    setSource(r.source);
  } else {
    S.className = name;
    setSource(`// ${name}: no source found (add --sourcepath, or a -sources.jar next to the jar).\n// The pipeline is compiled from the class file; Java lines still link through the bytecode.\n`);
  }
  compile();
}

function openDialog() {
  const dlg = $('#open-dlg');
  const q = $('#open-q');
  const list = $('#open-list');
  let t = 0;
  const search = async () => {
    const r = await (await fetch('api/classes?q=' + encodeURIComponent(q.value))).json();
    list.innerHTML = r.classes.map((c) => `<li data-c="${esc(c)}">${esc(c)}</li>`).join('') || '<li>no class matches</li>';
  };
  q.oninput = () => { clearTimeout(t); t = setTimeout(search, 120); };
  q.onkeydown = (e) => { if (e.key === 'Enter') { const first = list.querySelector('[data-c]'); if (first) { dlg.close(); openClass(first.dataset.c); } } };
  list.onclick = (e) => { const li = e.target.closest('[data-c]'); if (li) { dlg.close(); openClass(li.dataset.c); } };
  dlg.showModal();
  q.focus();
  search();
}

async function loadExample(name) {
  S.className = null;
  const r = await (await fetch('api/example?name=' + encodeURIComponent(name))).json();
  $('#example').value = name;
  S.method = null;
  S.args = {};
  S.sel = { ids: new Set(), line: null, bci: null };
  setSource(r.source);
  compile();
}

// ------------------------------------------------------------------ Java editor

function setSource(text) {
  $('#java').value = text;
  renderJava();
}

function renderJava() {
  const text = $('#java').value;
  const lines = text.split('\n');
  const mapped = mappedLines();
  $('#java-hl').innerHTML = lines.map((l, i) => `<span class="jl" data-l="${i + 1}">${hlJava(l) || ' '}</span>`).join('');
  $('#gutter').innerHTML = lines.map((_, i) => `<span data-l="${i + 1}" class="${mapped.has(i + 1) ? 'mapped' : ''}">${i + 1}</span>`).join('');
  markJava();
}

function mappedLines() {
  const set = new Set();
  const file = S.result?.fileName;
  for (const s of S.snaps) for (const l of s.lines) if (l.f === file && l.l > 0 && s.kind !== 'bytecode') set.add(l.l);
  return set;
}

function caretLine() {
  const ta = $('#java');
  return ta.value.slice(0, ta.selectionStart).split('\n').length;
}

// ------------------------------------------------------------------ compile

let inflight = 0;
async function compile() {
  const id = ++inflight;
  const source = $('#java').value;
  store.set('source', source);
  $('#compile').disabled = true;
  status('compiling…');
  const body = { source: S.className ? '' : source, className: S.className, method: S.method, backend: S.backend, targetOptions: S.targetOptions, mathOptimizations: $('#mathopt').checked, arguments: S.args };
  let r;
  try {
    r = await (await fetch('api/compile', { method: 'POST', body: JSON.stringify(body) })).json();
  } catch (e) {
    status('server unreachable: is tcirviz still running?', true);
    $('#compile').disabled = false;
    return;
  }
  if (id !== inflight) return;
  $('#compile').disabled = false;
  S.stale = false;
  S.result = r;
  renderKernels(r);
  renderArgs(r);
  renderProblems(r);
  if (r.error) { status(r.error, true); return; }
  S.fn = 0;
  renderFunctionPick();
  useFunction();
  const ms = (n) => (n / 1e6).toFixed(n < 1e7 ? 1 : 0);
  status(r.ok ? `${r.method} → ${r.backend.label}: TC ${ms(r.tcNanos)} ms · ${countIr()} IR snapshots` : `${r.diagnostics?.[0]?.code ?? 'error'}: ${r.diagnostics?.[0]?.message ?? 'see problems'}`, !r.ok);
}

function countIr() { return S.snaps.filter((s) => s.kind === 'ir').length; }

function status(text, err = false) {
  const el = $('#status');
  el.textContent = text;
  el.classList.toggle('err', err);
}

function renderKernels(r) {
  const sel = $('#kernel');
  sel.innerHTML = '';
  for (const k of r.kernels || []) sel.append(new Option(k.signature, k.name + ' ' + k.descriptor));
  if (r.method) {
    sel.value = r.method + ' ' + r.descriptor;
    S.method = r.method;
  }
}

function renderArgs(r) {
  const box = $('#args');
  const params = r.params || [];
  if (!params.length) { box.innerHTML = ''; return; }
  box.innerHTML = '<span class="t">Arguments</span>' + params.map((p) => p.kind === 'context'
    ? `<label title="${esc(p.hint)}">${esc(p.name)} <span class="k">KernelContext</span></label>`
    : `<label title="${esc(p.type)}: ${esc(p.hint)}">${esc(p.name)} <span class="k">${esc(p.kind === 'array' ? p.type + ' length' : p.kind === 'object' ? p.type + ' size' : p.type)}</span><input data-name="${esc(p.name)}" value="${esc(p.value)}" placeholder="unknown"></label>`).join('');
  let t = 0;
  for (const input of box.querySelectorAll('input')) {
    input.oninput = () => {
      S.args[input.dataset.name] = input.value;
      clearTimeout(t);
      t = setTimeout(compile, 400);
    };
  }
}

function renderProblems(r) {
  const box = $('#problems');
  const items = [];
  for (const p of r.problems || []) items.push(`<div class="diag">javac ${p.kind.toLowerCase()}${p.line > 0 ? ` (line ${p.line})` : ''}: ${esc(p.message)}</div>`);
  for (const d of r.diagnostics || []) items.push(`<div class="diag">${esc(d.code)}: ${esc(d.message)}${d.l > 0 ? `\n  at ${esc(d.f)}:${d.l} (bci ${d.b})` : ''}</div>`);
  box.innerHTML = items.join('');
}

function renderFunctionPick() {
  const fns = S.result.functions || [];
  const span = $('#fn-pick');
  if (fns.length < 2) { span.innerHTML = ''; return; }
  span.innerHTML = '<label class="ctl">Function <select></select></label>';
  const sel = $('select', span);
  fns.forEach((f, i) => sel.append(new Option(f.name, i)));
  sel.onchange = () => { S.fn = +sel.value; useFunction(); };
}

function useFunction() {
  const fn = (S.result.functions || [])[S.fn];
  S.snaps = fn ? fn.snapshots : [];
  // previous snapshot of a comparable kind (IR to IR), for size deltas and diffs
  S.irPrev = S.snaps.map((s, i) => {
    for (let j = i - 1; j >= 0; j--) if (S.snaps[j].kind === s.kind) return j;
    return -1;
  });
  computeLife();
  const keepA = S.panes.a.idx >= 0 && S.panes.a.idx < S.snaps.length ? S.panes.a.idx : -1;
  const firstIr = S.snaps.findIndex((s) => s.kind === 'ir');
  const src = S.snaps.findIndex((s) => s.kind === 'source');
  S.panes.a.idx = keepA >= 0 && S.snaps.length ? Math.min(keepA, S.snaps.length - 1) : (firstIr >= 0 ? firstIr : 0);
  S.panes.b.idx = src >= 0 ? src : S.snaps.length - 1;
  renderJava();
  renderAll();
}

// A line with its value names replaced by value ids: equal when only the printed numbering differs.
function canon(l) {
  const ids = [...(l.d || []), ...(l.u || [])];
  let k = 0;
  return l.t.replace(/%[\w.$?]+/g, () => '%#' + (ids[k++] ?? '?')).trim();
}

// The value that took over from a removed one: defined in the removing snapshot, at the same Java
// line and bci, preferring the same name (ABI lowering turns `%x = array.load` into `%x = load`).
function successor(id) {
  const life = S.life.get(id);
  const v = S.result.values[id];
  if (!life || life.gone === undefined || !v || !v.f) return null;
  const snap = S.snaps[life.gone];
  let best = null;
  for (const l of snap.lines) {
    for (const d of l.d || []) {
      const w = S.result.values[d];
      if (!w || w.f !== v.f || w.l !== v.l || w.b !== v.b || S.life.get(d)?.first !== life.gone) continue;
      if (w.name === v.name) return d;
      best ??= d;
    }
  }
  return best;
}

// The whole line of values one value belongs to: its ancestors (the values it replaced) and successors.
function chainOf(id) {
  let root = id;
  for (let k = 0; k < 16 && S.pred.has(root); k++) root = S.pred.get(root);
  const chain = [root];
  for (let next = S.succ.get(root); next !== undefined && !chain.includes(next) && chain.length < 16; next = S.succ.get(next)) chain.push(next);
  return chain;
}

// value id -> where it is first defined, last defined, and the snapshot that removed it
function computeLife() {
  S.life = new Map();
  S.snaps.forEach((s, i) => {
    if (s.kind !== 'ir') return;
    for (const l of s.lines) {
      const ids = [...(l.d || []), ...(l.i !== undefined && !(l.d || []).includes(l.i) ? [l.i] : [])];
      for (const id of ids) {
        const e = S.life.get(id) || { first: i, last: i };
        e.last = i;
        S.life.set(id, e);
      }
    }
  });
  const lastIr = S.snaps.reduce((a, s, i) => (s.kind === 'ir' ? i : a), -1);
  for (const e of S.life.values()) {
    if (e.last < lastIr) {
      for (let j = e.last + 1; j < S.snaps.length; j++) if (S.snaps[j].kind === 'ir') { e.gone = j; break; }
    }
  }
  S.succ = new Map();
  S.pred = new Map();
  for (const id of S.life.keys()) {
    const next = successor(id);
    if (next !== null && !S.pred.has(next)) { S.succ.set(id, next); S.pred.set(next, id); }
  }
}

// ------------------------------------------------------------------ rendering

function renderAll() {
  renderRail();
  renderSpark();
  renderPane('a');
  renderPane('b');
  applySelection();
}

function snapName(s) {
  if (s.pass) return s.pass;
  return STAGES[s.stage]?.title ?? s.stage;
}

function snapLabel(s, i) {
  const g = s.pass ? `${STAGES[s.stage]?.title ?? s.stage} › ${s.pass}` : snapName(s);
  return `${i}. ${g}${s.pass && !s.changed ? ' (no change)' : ''}`;
}

function renderRail() {
  const rail = $('#rail');
  rail.innerHTML = '';
  let group = null;
  let groupStage = null;
  S.snaps.forEach((s, i) => {
    const visible = s.changed || !s.pass || S.showUnchanged || i === S.panes.a.idx || i === S.panes.b.idx;
    const inGroup = !!s.pass;
    if (inGroup && groupStage !== s.stage) {
      group = document.createElement('div');
      group.className = 'group';
      group.innerHTML = `<span class="gname">${esc(STAGES[s.stage]?.title ?? s.stage)}</span>`;
      rail.append(group);
      groupStage = s.stage;
    } else if (!inGroup) {
      group = null;
      groupStage = null;
    }
    if (!visible) return;
    const chip = document.createElement('button');
    chip.className = 'chip' + (s.pass && !s.changed ? ' same' : '');
    chip.dataset.i = i;
    const prev = S.irPrev[i];
    const delta = s.kind === 'ir' && prev >= 0 ? s.size - S.snaps[prev].size : 0;
    const time = s.nanos > 0 ? `${(s.nanos / 1e3).toFixed(s.nanos < 1e5 ? 0 : 0)}µs` : '';
    chip.innerHTML = `<div class="n">${esc(snapName(s))}</div><div class="m">${s.kind === 'ir' ? s.size + ' insts' : s.lines.length + ' lines'}${delta ? ` <span class="d ${delta > 0 ? 'up' : 'down'}">${delta > 0 ? '+' : ''}${delta}</span>` : ''}${time ? ' · ' + time : ''}</div>`;
    chip.onclick = (e) => showSnap(e.shiftKey ? 'b' : 'a', i);
    chip.onmouseenter = (e) => tip(e, `<b>${esc(snapName(s))}</b><br>${esc(s.pass ? PASSES[s.pass] ?? '' : STAGES[s.stage]?.what ?? '')}`);
    chip.onmouseleave = untip;
    (group || rail).append(chip);
  });
  markRail();
}

function markRail() {
  const id = S.sel.primary ?? [...S.sel.ids][0];
  const life = id !== undefined ? S.life.get(id) : null;
  for (const chip of $('#rail').querySelectorAll('.chip')) {
    const i = +chip.dataset.i;
    chip.classList.toggle('a', i === S.panes.a.idx);
    chip.classList.toggle('b', i === S.panes.b.idx);
    chip.classList.toggle('hit', !!life && i === life.first);
    chip.classList.toggle('gone', !!life && i === life.gone);
  }
  reveal($('#rail .chip.a'), $('#rail'), true);
}

function renderSpark() {
  const pts = S.snaps.map((s, i) => [i, s.kind === 'ir' ? s.size : null]).filter((p) => p[1] !== null);
  if (pts.length < 2) { $('#spark').innerHTML = ''; return; }
  const max = Math.max(...pts.map((p) => p[1]));
  const W = 1000, H = 26, n = S.snaps.length - 1 || 1;
  const x = (i) => (i / n) * (W - 8) + 4;
  const y = (v) => H - 4 - (v / max) * (H - 8);
  const line = pts.map(([i, v]) => `${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ');
  const mark = (i, c) => (i >= 0 ? `<line x1="${x(i)}" x2="${x(i)}" y1="0" y2="${H}" stroke="${c}" stroke-width="2"/>` : '');
  $('#spark').innerHTML = `<svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="none"><title>IR size across the pipeline</title>${mark(S.panes.a.idx, 'var(--accent)')}${mark(S.panes.b.idx, 'var(--blk)')}<polyline points="${line}" fill="none" stroke="var(--mute)" stroke-width="1.5" vector-effect="non-scaling-stroke"/></svg>`;
}

function showSnap(which, i) {
  S.panes[which].idx = Math.max(0, Math.min(S.snaps.length - 1, i));
  renderPane(which);
  renderSpark();
  markRail();
  applySelection();
}

const paneEls = {};

function renderPane(which) {
  const el = $('#pane-' + which);
  const p = S.panes[which];
  const s = S.snaps[p.idx];
  if (!s) {
    el.innerHTML = `<div class="pane-head"><span class="pane-title">${which === 'a' ? 'Stage' : 'Compare'}</span></div><div class="empty">${S.result ? 'Nothing to show: see the problems under the Java source.' : 'Compile a kernel to see its pipeline.'}</div>`;
    paneEls[which] = [];
    return;
  }
  const options = S.snaps.map((x, i) => `<option value="${i}" ${i === p.idx ? 'selected' : ''}>${esc(snapLabel(x, i))}</option>`).join('');
  const canDiff = S.irPrev[p.idx] >= 0 && s.kind === 'ir';
  const about = s.pass ? `<b>${esc(s.pass)}</b>: ${esc(PASSES[s.pass] ?? '')}${s.changed ? '' : ' <i>This run changed nothing.</i>'}` : `<b>${esc(STAGES[s.stage]?.title ?? s.stage)}</b>: ${esc(STAGES[s.stage]?.what ?? '')}`;
  el.innerHTML = `<div class="pane-head"><span class="pane-title">${which === 'a' ? 'Stage' : 'Compare'}</span>
      <select aria-label="Stage">${options}</select>
      <span class="tools"><button data-act="prev" title="Previous stage">‹</button><button data-act="next" title="Next stage">›</button>
      <button data-act="graph" aria-pressed="${p.graph && s.kind === 'ir'}" ${s.kind === 'ir' ? '' : 'disabled'} title="Control-flow graph: blocks and branches">⧉</button>
      <button data-act="diff" aria-pressed="${p.diff && canDiff}" ${canDiff ? '' : 'disabled'} title="What this stage changed">Δ</button></span></div>
      <div class="about">${about}</div><div class="code" tabindex="0"></div>`;
  $('select', el).onchange = (e) => showSnap(which, +e.target.value);
  el.querySelector('[data-act=prev]').onclick = () => showSnap(which, stepVisible(p.idx, -1));
  el.querySelector('[data-act=next]').onclick = () => showSnap(which, stepVisible(p.idx, 1));
  el.querySelector('[data-act=diff]').onclick = () => { p.diff = !p.diff; renderPane(which); applySelection(); };
  el.querySelector('[data-act=graph]').onclick = () => { p.graph = !p.graph; renderPane(which); applySelection(); };
  const code = $('.code', el);
  const rows = p.diff && canDiff ? diffRows(S.snaps[S.irPrev[p.idx]], s) : s.lines.map((l) => ({ l, cls: '' }));
  const hl = forKind(s.kind);
  const file = S.result.fileName;
  const lineHtml = (r, n) => {
    const ids = [...(r.l.d || []), ...(r.l.u || [])];
    let html = hl(r.l.t);
    if (s.kind === 'ir') html = html.replace(/data-k="(\d+)"/g, (_, k) => `data-v="${ids[+k] ?? ''}"`);
    const ext = r.l.f && file && r.l.f !== file ? ' ext' : '';
    return `<div class="ln ${r.cls}${ext}" data-n="${n}"><i>${n + 1}</i><span>${html || ' '}</span></div>`;
  };
  if (p.graph && s.kind === 'ir') {
    code.classList.add('cfg');
    code.innerHTML = cfgHtml(rows, lineHtml);
    requestAnimationFrame(() => drawEdges(code));
  } else {
    code.innerHTML = rows.map(lineHtml).join('');
  }
  const lineEls = code.querySelectorAll('.ln');
  paneEls[which] = rows.map((r, n) => ({ el: lineEls[n], l: r.l }));
  code.onclick = (e) => onCodeClick(e, which);
  code.onmouseover = (e) => onCodeHover(e);
  code.onmouseout = untip;
}

// ------------------------------------------------------------------ control-flow graph

const LABEL = /^\^([\w.$?]+)/;

// Blocks as boxes in printed order; the edges are drawn once the boxes are laid out.
function cfgHtml(rows, lineHtml) {
  let html = '';
  let open = false;
  rows.forEach((r, n) => {
    const m = r.l.t.match(LABEL);
    if (m) {
      if (open) html += '</div>';
      html += `<div class="blk" data-b="${esc(m[1])}">`;
      open = true;
    } else if (!open) {
      html += lineHtml(r, n);
      return;
    }
    if (r.l.t === '}') { html += '</div>' + lineHtml(r, n); open = false; return; }
    html += lineHtml(r, n);
  });
  if (open) html += '</div>';
  return `<svg class="edges" aria-hidden="true"></svg><div class="blks">${html}</div>`;
}

function drawEdges(code) {
  const svg = code.querySelector('.edges');
  const blocks = [...code.querySelectorAll('.blk')];
  if (!svg || !blocks.length) return;
  const index = new Map(blocks.map((b, i) => [b.dataset.b, i]));
  const box = code.querySelector('.blks').getBoundingClientRect();
  const base = code.getBoundingClientRect();
  const ox = box.left - base.left + code.scrollLeft;
  const oy = code.scrollTop - base.top;
  svg.setAttribute('width', code.scrollWidth);
  svg.setAttribute('height', code.scrollHeight);
  let paths = '';
  let laneR = 0, laneL = 0;
  blocks.forEach((b, i) => {
    const lines = b.querySelectorAll('.ln');
    const term = lines[lines.length - 1];
    if (!term) return;
    const text = term.textContent;
    const targets = [...text.matchAll(/\^([\w.$?]+)/g)].map((m) => m[1]);
    const kind = /cond_br/.test(text) ? ['T', 'F'] : [];
    const tr = term.getBoundingClientRect();
    const br = b.getBoundingClientRect();
    targets.forEach((t, k) => {
      const j = index.get(t);
      if (j === undefined) return;
      const target = blocks[j].getBoundingClientRect();
      const y1 = tr.top + tr.height / 2 + oy;
      const y2 = target.top + 9 + oy;
      if (j === i + 1 && targets.length === 1) return; // falls through to the next box: the layout says it
      const back = j <= i;
      const x0 = back ? br.left - base.left + code.scrollLeft : br.right - base.left + code.scrollLeft;
      const lane = back ? x0 - 10 - (laneL++ % 6) * 7 : Math.max(x0, target.right - base.left + code.scrollLeft) + 10 + (laneR++ % 6) * 7;
      const xt = back ? target.left - base.left + code.scrollLeft : target.right - base.left + code.scrollLeft;
      const cls = back ? 'back' : kind[k] === 'F' ? 'f' : 'fw';
      paths += `<path class="${cls}" d="M${x0},${y1} H${lane} V${y2} H${xt + (back ? -2 : 2)}" marker-end="url(#ah-${cls})"/>`;
      if (kind[k]) paths += `<text class="${cls}" x="${back ? lane - 10 : lane + 3}" y="${y1 - 3}">${kind[k]}</text>`;
    });
  });
  const marker = (c) => `<marker id="ah-${c}" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path class="${c}" d="M0,0 L8,4 L0,8 z"/></marker>`;
  svg.innerHTML = `<defs>${marker('fw')}${marker('f')}${marker('back')}</defs>${paths}`;
  void ox;
}

function stepVisible(i, dir) {
  for (let j = i + dir; j >= 0 && j < S.snaps.length; j += dir) {
    const s = S.snaps[j];
    if (s.changed || !s.pass || S.showUnchanged) return j;
  }
  return i;
}

// Rows of a diff: the current snapshot's lines marked add/chg, with removed lines of the previous one interleaved (LCS on line identity).
function diffRows(prev, cur) {
  const key = (l) => (l.i !== undefined ? 'i' + l.i : l.d ? 'd' + l.d.join(',') : 't' + l.t.trim());
  const a = prev.lines, b = cur.lines;
  const ka = a.map(key), kb = b.map(key);
  const n = a.length, m = b.length;
  if (n * m > 4e6) return b.map((l) => ({ l, cls: '' }));
  const dp = Array.from({ length: n + 1 }, () => new Uint16Array(m + 1));
  for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--) dp[i][j] = ka[i] === kb[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
  const rows = [];
  let i = 0, j = 0;
  while (i < n || j < m) {
    if (i < n && j < m && ka[i] === kb[j]) { rows.push({ l: b[j], cls: a[i].t === b[j].t ? '' : 'chg' }); i++; j++; }
    else if (j < m && (i >= n || dp[i][j + 1] >= dp[i + 1][j])) { rows.push({ l: b[j], cls: 'add' }); j++; }
    else { rows.push({ l: a[i], cls: 'del' }); i++; }
  }
  return rows;
}

// ------------------------------------------------------------------ selection

function onCodeClick(e, which) {
  const val = e.target.closest('.val');
  const row = e.target.closest('.ln');
  if (!row) return;
  const l = paneEls[which][+row.dataset.n].l;
  if (val && val.dataset.v) {
    select({ ids: [+val.dataset.v], line: l.f === S.result.fileName ? l.l : null, bci: null });
    return;
  }
  const ids = l.i !== undefined ? [...(l.d || [l.i])] : (l.d && l.d.length ? l.d : l.u || []);
  const kind = S.snaps[S.panes[which].idx].kind;
  select({ ids: kind === 'bytecode' ? [] : ids, line: l.f === S.result.fileName && l.l > 0 ? l.l : null, bci: kind === 'bytecode' && l.b >= 0 ? l.b : (ids.length ? null : l.b >= 0 ? l.b : null) });
}

function select({ ids = [], line = null, bci = null }) {
  // a value is followed together with the values that replaced it later in the pipeline
  const all = new Set();
  for (const id of ids) for (const c of chainOf(id)) all.add(c);
  S.sel = { ids: all, line, bci, primary: ids[0] };
  applySelection();
}

function applySelection() {
  const { ids, line, bci } = S.sel;
  const file = S.result?.fileName;
  for (const which of ['a', 'b']) {
    let first = null;
    for (const { el, l } of paneEls[which] || []) {
      const def = (l.d || []).some((x) => ids.has(x)) || (l.i !== undefined && ids.has(l.i) && !(l.d || []).length);
      const use = !def && (l.u || []).some((x) => ids.has(x));
      const loc = line !== null && l.f === file && l.l === line && !ids.size;
      const atBci = bci !== null && l.b === bci && l.f === file;
      el.classList.toggle('hl-def', def);
      el.classList.toggle('hl-use', use);
      el.classList.toggle('hl-loc', loc && !atBci);
      el.classList.toggle('hl-bci', atBci);
      if (!first && (def || (atBci && !ids.size) || (loc && !ids.size))) first = el;
      for (const v of el.querySelectorAll('.val')) v.classList.toggle('on', ids.has(+v.dataset.v));
    }
    if (first) reveal(first, first.closest('.code'));
  }
  markJava();
  markRail();
  renderJourney();
}

function markJava() {
  const { ids, line } = S.sel;
  const related = new Set();
  if (ids.size) for (const id of ids) { const v = S.result?.values?.[id]; if (v && v.f === S.result.fileName && v.l > 0) related.add(v.l); }
  for (const el of $('#java-hl').children) {
    const n = +el.dataset.l;
    el.classList.toggle('sel', n === line);
    el.classList.toggle('rel', related.has(n) && n !== line);
  }
  for (const el of $('#gutter').children) el.classList.toggle('sel', +el.dataset.l === line || related.has(+el.dataset.l));
}

// Scrolls only the given container (scrollIntoView would also scroll the page).
function reveal(el, box, horizontal = false, center = false) {
  if (!el || !box) return;
  const e = el.getBoundingClientRect(), b = box.getBoundingClientRect();
  if (horizontal) {
    if (e.left < b.left || e.right > b.right) box.scrollLeft += e.left - b.left - (b.width - e.width) / 2;
    return;
  }
  if (center || e.top < b.top + 30 || e.bottom > b.bottom - 10) box.scrollTop += e.top - b.top - (center ? (b.height - e.height) / 2 : 40);
}

// ------------------------------------------------------------------ journey

function renderJourney() {
  const body = $('#j-body');
  const { ids, line, bci } = S.sel;
  const file = S.result?.fileName;
  if (!S.result || (!ids.size && line === null && bci === null)) {
    body.innerHTML = '';
    $('#j-sub').textContent = 'select a Java line, a bytecode, an IR value or a line of target code';
    return;
  }
  const steps = [];
  const step = (i, name, code, badge = '', n = -1) => steps.push(`<div class="step" data-i="${i}" data-n="${n}"><div class="s-name">${esc(name)}${badge}</div><div class="s-code">${esc(code)}</div></div>`);
  if (ids.size) {
    const picked = S.sel.primary ?? [...ids][0];
    const id = chainOf(picked)[0];
    const v = S.result.values[id] || {};
    const chainEnd = chainOf(id).at(-1);
    const life = S.life.get(id);
    const endLife = S.life.get(chainEnd);
    $('#j-sub').textContent = `%${v.name} through the pipeline`;
    const info = `<div class="vinfo"><h3>%${esc(v.name ?? '?')}</h3><dl>
      <dt>type</dt><dd>${esc(v.type ?? '')}</dd><dt>kind</dt><dd>${esc(v.op || v.kind || '')}</dd>
      ${v.f ? `<dt>from</dt><dd>${esc(v.f)}:${v.l} · bci ${v.b}</dd>` : ''}
      ${life ? `<dt>created</dt><dd>${esc(snapName(S.snaps[life.first]))}</dd>` : ''}
      ${chainEnd !== id ? `<dt>becomes</dt><dd>%${esc(S.result.values[chainEnd]?.name ?? '')} (${esc(S.result.values[chainEnd]?.op ?? '')})</dd>` : ''}
      ${endLife ? (endLife.gone ? `<dt>removed by</dt><dd>${esc(snapName(S.snaps[endLife.gone]))}</dd>` : '<dt>kept</dt><dd>to the target code</dd>') : ''}
      </dl></div>`;
    if (v.f === file && v.l > 0) {
      step(-1, 'Java ' + v.l, ($('#java').value.split('\n')[v.l - 1] || '').trim(), '', v.l);
      const bc = S.snaps.findIndex((s) => s.kind === 'bytecode');
      if (bc >= 0 && v.b >= 0) {
        const n = S.snaps[bc].lines.findIndex((l) => l.b === v.b);
        if (n >= 0) step(bc, 'bytecode', S.snaps[bc].lines[n].t.trim(), '', n);
      }
    }
    const chain = chainOf(S.sel.primary ?? id);
    const root = chain[0];
    let prevText = null;
    let present = false;
    let link = 0;
    S.snaps.forEach((s, i) => {
      if (s.kind === 'ir') {
        let cur = chain[link];
        let n = s.lines.findIndex((l) => (l.d || []).includes(cur) || l.i === cur);
        let replaced = false;
        if (n < 0 && present && link + 1 < chain.length) {
          const m = s.lines.findIndex((l) => (l.d || []).includes(chain[link + 1]));
          if (m >= 0) { link++; cur = chain[link]; n = m; replaced = true; }
        }
        if (n >= 0) {
          const t = canon(s.lines[n]);
          const badge = replaced ? '<span class="badge chg">replaced</span>' : !present ? '<span class="badge new">created</span>' : t !== prevText ? '<span class="badge chg">changed</span>' : '';
          if (badge) step(i, snapName(s), s.lines[n].t.trim(), badge, n);
          prevText = t;
          present = true;
        } else if (present) {
          step(i, snapName(s), '', '<span class="badge gone">removed</span>');
          present = false;
        }
      } else if (s.kind === 'structured' || s.kind === 'source') {
        s.lines.forEach((l, n) => { if ((l.u || []).some((u) => chain.includes(u))) step(i, s.kind === 'source' ? S.result.backend.label : 'structured', l.t.trim(), '', n); });
      }
    });
    body.innerHTML = info + steps.join('<span class="arrow">→</span>');
  } else {
    const what = bci !== null ? `bci ${bci}` : `line ${line}`;
    $('#j-sub').textContent = `Java ${what} through the pipeline`;
    if (line !== null) step(-1, 'Java ' + line, ($('#java').value.split('\n')[line - 1] || '').trim(), '', line);
    let lastText = null;
    S.snaps.forEach((s, i) => {
      if (s.kind === 'ir' && s.pass) return; // stage level is enough here
      const hits = s.lines.map((l, n) => [l, n]).filter(([l]) => l.f === file && (bci !== null ? l.b === bci : l.l === line) && (s.kind !== 'bytecode' || l.b >= 0));
      if (!hits.length) return;
      const text = hits.map(([l]) => l.t.trim()).join('\n');
      if (text === lastText) return;
      lastText = text;
      step(i, `${snapName(s)} · ${hits.length}`, text, '', hits[0][1]);
    });
    body.innerHTML = steps.join('<span class="arrow">→</span>');
  }
  for (const el of body.querySelectorAll('.step')) {
    el.onclick = () => {
      const i = +el.dataset.i;
      if (i < 0) { gotoJava(+el.dataset.n); return; }
      const which = S.snaps[i].kind === 'source' ? 'b' : 'a';
      showSnap(which, i);
      const row = paneEls[which].find((r) => r.l === S.snaps[i].lines[+el.dataset.n]);
      if (row) reveal(row.el, row.el.closest('.code'), false, true);
    };
  }
}

function gotoJava(line) {
  const ta = $('#java');
  const lines = ta.value.split('\n');
  const pos = lines.slice(0, line - 1).reduce((a, l) => a + l.length + 1, 0);
  ta.focus();
  ta.setSelectionRange(pos, pos + (lines[line - 1] || '').length);
  const el = $('#java-hl').children[line - 1];
  if (el) reveal(el, $('.editor'), false, true);
}

// ------------------------------------------------------------------ tooltips

let tipTimer = 0;
function tip(e, html) {
  clearTimeout(tipTimer);
  tipTimer = setTimeout(() => {
    const t = $('#tip');
    t.innerHTML = html;
    t.hidden = false;
    const x = Math.min(e.clientX + 14, innerWidth - 440);
    const y = e.clientY + 18 + t.offsetHeight > innerHeight ? e.clientY - t.offsetHeight - 10 : e.clientY + 18;
    t.style.left = x + 'px';
    t.style.top = y + 'px';
  }, 250);
}
function untip() { clearTimeout(tipTimer); $('#tip').hidden = true; }

function onCodeHover(e) {
  const val = e.target.closest('.val');
  if (val && val.dataset.v) {
    const id = +val.dataset.v;
    const v = S.result.values[id] || {};
    const life = S.life.get(id);
    tip(e, `<b>%${esc(v.name ?? '')}</b> : ${esc(v.type ?? '')}<br>${esc(v.op || v.kind || '')}${v.f ? ` · ${esc(v.f)}:${v.l} bci ${v.b}` : ''}${life ? `<br>created in ${esc(snapName(S.snaps[life.first]))}${life.gone ? `, removed by ${esc(snapName(S.snaps[life.gone]))}` : ''}` : ''}`);
    return;
  }
  const op = e.target.closest('.op');
  if (op) {
    const h = opcodeHelp('= ' + op.textContent);
    if (h) tip(e, `<b>${esc(h.op)}</b><br>${esc(h.help)}`);
  }
}

// ------------------------------------------------------------------ wiring

function wire() {
  const ta = $('#java');
  let t = 0;
  ta.addEventListener('input', () => {
    clearTimeout(t);
    t = setTimeout(renderJava, 60);
    if (!S.stale) { S.stale = true; status('edited: Ctrl+Enter to compile'); }
    S.className = null; // an edit compiles the editor's text
  });
  ta.addEventListener('keydown', (e) => {
    if (e.key === 'Tab') {
      e.preventDefault();
      const s = ta.selectionStart;
      ta.setRangeText('    ', s, ta.selectionEnd, 'end');
      ta.dispatchEvent(new Event('input'));
    }
  });
  ta.addEventListener('click', () => select({ line: caretLine() }));
  $('#gutter').onclick = (e) => { const l = e.target.closest('[data-l]'); if (l) select({ line: +l.dataset.l }); };
  $('#compile').onclick = compile;
  $('#open').onclick = openDialog;
  $('#example').onchange = (e) => e.target.value && loadExample(e.target.value);
  $('#kernel').onchange = (e) => { S.method = e.target.value.split(' ')[0]; S.args = {}; S.sel = { ids: new Set(), line: null, bci: null }; compile(); };
  $('#mathopt').onchange = compile;
  $('#show-unchanged').onchange = (e) => { S.showUnchanged = e.target.checked; store.set('showUnchanged', S.showUnchanged); renderRail(); };
  $('#theme').onclick = () => {
    const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.theme = next;
    store.set('theme', next);
  };
  $('#help').onclick = () => $('#help-dlg').showModal();
  $('#j-close').onclick = () => $('#journey').classList.toggle('closed');
  document.addEventListener('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); compile(); return; }
    if (e.target === ta || e.target.tagName === 'SELECT' || e.target.tagName === 'INPUT') return;
    if (e.key === 'Escape') select({});
    else if (e.key === 'ArrowRight') showSnap('a', stepVisible(S.panes.a.idx, 1));
    else if (e.key === 'ArrowLeft') showSnap('a', stepVisible(S.panes.a.idx, -1));
    else if (e.key === '?') $('#help-dlg').showModal();
  });
  addEventListener('resize', () => { for (const c of document.querySelectorAll('.code.cfg')) drawEdges(c); });
  // column splitters
  for (const sp of document.querySelectorAll('.split')) {
    sp.onpointerdown = (e) => {
      const work = $('#work');
      const left = $('#' + sp.dataset.left);
      const start = e.clientX, w0 = left.getBoundingClientRect().width;
      const move = (ev) => {
        const w = Math.max(160, w0 + ev.clientX - start);
        if (sp.dataset.left === 'pane-java') work.style.setProperty('--c1', w + 'px');
        else work.style.setProperty('--c2', w + 'px');
        for (const c of document.querySelectorAll('.code.cfg')) drawEdges(c);
      };
      sp.setPointerCapture(e.pointerId);
      sp.onpointermove = move;
      sp.onpointerup = () => { sp.onpointermove = null; };
    };
  }
  // journey height
  const head = $('.j-head');
  head.onpointerdown = (e) => {
    if (e.target.closest('button')) return;
    const start = e.clientY, h0 = $('#journey').getBoundingClientRect().height;
    head.setPointerCapture(e.pointerId);
    head.onpointermove = (ev) => document.body.style.setProperty('--jh', Math.max(34, Math.min(innerHeight * 0.7, h0 - (ev.clientY - start))) + 'px');
    head.onpointerup = () => { head.onpointermove = null; };
  };
}

boot();
