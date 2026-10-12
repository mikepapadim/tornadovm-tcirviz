// Drives the tcirviz UI in headless Chrome over the DevTools protocol (no npm packages): loads the
// page, runs a script of interactions, records console errors and takes screenshots.
//   node tools/ui-check.mjs http://127.0.0.1:7878/ out-dir
import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';

const url = process.argv[2] ?? 'http://127.0.0.1:7878/';
const out = process.argv[3] ?? 'ui-check';
mkdirSync(out, { recursive: true });
const chrome = process.env.CHROME ?? 'google-chrome';
const port = 9300 + Math.floor(Math.random() * 500);
const proc = spawn(chrome, ['--headless=new', `--remote-debugging-port=${port}`, '--no-first-run', '--no-default-browser-check', `--user-data-dir=/tmp/tcirviz-chrome-${port}`, '--window-size=1600,1000', 'about:blank'], { stdio: 'ignore' });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let ws;
for (let i = 0; i < 50 && !ws; i++) {
  try {
    const targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
    const page = targets.find((t) => t.type === 'page');
    ws = new WebSocket(page.webSocketDebuggerUrl);
  } catch { await sleep(200); }
}
await new Promise((r) => ws.addEventListener('open', r, { once: true }));
let seq = 0;
const pending = new Map();
const errors = [];
ws.addEventListener('message', (e) => {
  const m = JSON.parse(e.data);
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
  if (m.method === 'Runtime.exceptionThrown') errors.push('exception: ' + (m.params.exceptionDetails.exception?.description ?? m.params.exceptionDetails.text));
  if (m.method === 'Runtime.consoleAPICalled' && (m.params.type === 'error' || m.params.type === 'warning')) errors.push(m.params.type + ': ' + m.params.args.map((a) => a.value ?? a.description).join(' '));
  if (m.method === 'Log.entryAdded' && m.params.entry.level === 'error') errors.push('log: ' + m.params.entry.text);
});
const send = (method, params = {}) => new Promise((r) => { const id = ++seq; pending.set(id, r); ws.send(JSON.stringify({ id, method, params })); });
const evaluate = async (expr) => {
  const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
  if (r.result?.exceptionDetails) errors.push('eval: ' + r.result.exceptionDetails.exception?.description);
  return r.result?.result?.value;
};
const shot = async (name) => {
  const r = await send('Page.captureScreenshot', { format: 'png' });
  writeFileSync(`${out}/${name}.png`, Buffer.from(r.result.data, 'base64'));
};
const waitFor = async (expr, ms = 15000) => {
  const end = Date.now() + ms;
  while (Date.now() < end) { if (await evaluate(expr)) return true; await sleep(100); }
  errors.push('timeout waiting for ' + expr);
  return false;
};

await send('Runtime.enable');
await send('Log.enable');
await send('Page.enable');
await send('Emulation.setDeviceMetricsOverride', { width: 1600, height: 1000, deviceScaleFactor: 1, mobile: false });
await send('Page.navigate', { url });
const report = {};
await waitFor(`document.querySelector('#status')?.textContent.includes('→')`);
report.status = await evaluate(`document.querySelector('#status').textContent`);
report.chips = await evaluate(`document.querySelectorAll('#rail .chip').length`);
report.paneA = await evaluate(`document.querySelector('#pane-a .pane-head select')?.selectedOptions[0]?.textContent`);
report.paneB = await evaluate(`document.querySelector('#pane-b .pane-head select')?.selectedOptions[0]?.textContent`);
await shot('01-loaded');
// follow Java line 11 (c.set) through the pipeline
await evaluate(`document.querySelector('#gutter [data-l="11"]').click()`);
await sleep(300);
report.javaLineSteps = await evaluate(`document.querySelectorAll('#j-body .step').length`);
report.locHighlights = await evaluate(`document.querySelectorAll('.code .ln.hl-loc').length`);
await shot('02-java-line');
// click an IR value in pane A
await evaluate(`[...document.querySelectorAll('#pane-a .val')].find(v => v.textContent === '%x')?.click()`);
await sleep(300);
report.valueSteps = await evaluate(`document.querySelectorAll('#j-body .step').length`);
report.valueTitle = await evaluate(`document.querySelector('#j-sub').textContent`);
report.defHighlights = await evaluate(`document.querySelectorAll('.code .ln.hl-def').length`);
report.useHighlights = await evaluate(`document.querySelectorAll('.code .ln.hl-use').length`);
await shot('03-value');
// diff of the ABI stage
await evaluate(`(() => { const s = document.querySelector('#pane-a select'); const o = [...s.options].find(o => o.textContent.includes('ABI lowering')); s.value = o.value; s.dispatchEvent(new Event('change')); })()`);
await sleep(200);
await evaluate(`document.querySelector('#pane-a [data-act=diff]').click()`);
await sleep(300);
report.diffAdded = await evaluate(`document.querySelectorAll('#pane-a .ln.add').length`);
report.diffRemoved = await evaluate(`document.querySelectorAll('#pane-a .ln.del').length`);
await shot('04-abi-diff');
// another example, OpenCL target, dark theme
await evaluate(`(() => { const s = document.querySelector('#example'); s.value = 'Reduction'; s.dispatchEvent(new Event('change')); })()`);
await waitFor(`document.querySelector('#status').textContent.includes('sum →')`);
await evaluate(`[...document.querySelectorAll('#backends button')].find(b => b.textContent === 'OpenCL').click()`);
await waitFor(`document.querySelector('#status').textContent.includes('OpenCL')`);
await evaluate(`document.querySelector('#theme').click()`);
await sleep(300);
report.openclStatus = await evaluate(`document.querySelector('#status').textContent`);
report.openclHasKernel = await evaluate(`[...document.querySelectorAll('#pane-b .ln')].some(l => l.textContent.includes('__kernel'))`);
await shot('05-reduction-opencl-dark');
// control-flow graph of the Branches example after the frontend
await evaluate(`(() => { const s = document.querySelector('#example'); s.value = 'Branches'; s.dispatchEvent(new Event('change')); })()`);
await waitFor(`document.querySelector('#status').textContent.includes('classify →')`);
await evaluate(`document.querySelector('#pane-a [data-act=graph]').click()`);
await sleep(500);
report.cfgBlocks = await evaluate(`document.querySelectorAll('#pane-a .blk').length`);
report.cfgEdges = await evaluate(`document.querySelectorAll('#pane-a .edges path').length`);
report.cfgBackEdges = await evaluate(`document.querySelectorAll('#pane-a .edges path.back').length`);
await evaluate(`[...document.querySelectorAll('#pane-a .val')].find(v => v.textContent === '%v')?.click()`);
await sleep(300);
await shot('06-cfg');
// Metal: compiles to MSL like the other targets
await evaluate(`[...document.querySelectorAll('#backends button')].find(b => b.textContent === 'Metal').click()`);
await waitFor(`document.querySelector('#status').textContent.includes('Metal') && !document.querySelector('#status').textContent.includes('TC-E')`);
report.metalStatus = await evaluate(`document.querySelector('#status').textContent`);
await shot('07-metal');
// a TileContext kernel picked while on Metal: a CUDA-only notice over the panes, Metal marked unavailable
await evaluate(`(() => { const s = document.querySelector('#example'); s.value = 'TileMatmul'; s.dispatchEvent(new Event('change')); })()`);
await waitFor(`!document.querySelector('#cuda-only').hidden`);
report.tileNotice = await evaluate(`document.querySelector('#cuda-only h3')?.textContent ?? ''`);
report.tileMetalMarked = await evaluate(`[...document.querySelectorAll('#backends button')].find(b => b.textContent === 'Metal').classList.contains('na')`);
report.tileContextInput = await evaluate(`!!document.querySelector('#args input[data-name="tc"]')`);
await shot('08-tile-notice');
// "Show on CUDA" compiles it there and the notice goes away
await evaluate(`document.querySelector('#cuda-only-go').click()`);
await waitFor(`document.querySelector('#status').textContent.includes('CUDA') && document.querySelector('#cuda-only').hidden`);
report.tileStatus = await evaluate(`document.querySelector('#status').textContent`);
await shot('09-tile-cuda');
await evaluate(`[...document.querySelectorAll('#backends button')].find(b => b.textContent === 'CUDA').click()`);
await sleep(1500);
report.errors = errors;
writeFileSync(`${out}/report.json`, JSON.stringify(report, null, 2));
console.log(JSON.stringify(report, null, 2));
ws.close();
proc.kill();
process.exit(0);
