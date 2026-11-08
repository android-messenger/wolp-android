#!/usr/bin/env node
// Evaluate JS in the app's WebView page via Chrome DevTools Protocol (debug builds only).
//   node cdp.mjs '<expression>'            (awaits promises; prints JSON result)
// Needs: adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>  (see cdp.sh)
const expr = process.argv[2];
const port = process.env.CDP_PORT || 9222;
const match = process.env.CDP_URL || 'app.';
const pages = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
const page = pages.find((p) => p.type === 'page' && p.url.includes(match)) || pages.find((p) => p.type === 'page');
if (!page) { console.error('no page', pages); process.exit(2); }
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
ws.onmessage = (m) => {
  const d = JSON.parse(m.data);
  if (d.id !== 1) return;
  const r = d.result?.result;
  if (d.result?.exceptionDetails) console.log('EXCEPTION', JSON.stringify(d.result.exceptionDetails.exception?.description || d.result.exceptionDetails));
  else console.log(typeof r?.value === 'string' ? r.value : JSON.stringify(r?.value ?? r));
  ws.close(); process.exit(0);
};
ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression: expr, awaitPromise: true, returnByValue: true, timeout: 30000 } }));
setTimeout(() => { console.error('timeout'); process.exit(3); }, 35000);
