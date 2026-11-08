// P1 probe page (served from app.<host>/messenger-ui/). Every action logs to #out and the native log.
(function () {
  const $ = (id) => document.getElementById(id);
  const out = (s, cls) => { const line = new Date().toISOString().slice(11, 23) + ' ' + s; $('out').textContent = line + '\n' + $('out').textContent; if (P) P.log({ line: s }).catch(() => {}); };
  const C = window.Capacitor;
  const P = C && C.Plugins && C.Plugins.P1Probe;
  const inFrame = window.top !== window;
  window.__p1 = { t0: performance.timeOrigin, jsReady: Date.now() };

  // ---- P1-b: bridge mode ----
  const ab = window.androidBridge;
  const bridgeMode = !ab ? 'none' : (typeof ab.addEventListener === 'function' ? 'WebMessageListener' : 'JavascriptInterface');
  out('origin=' + location.origin + ' frame=' + (inFrame ? 'iframe' : 'main') + ' capacitor=' + !!C + ' native=' + !!(C && C.isNativePlatform && C.isNativePlatform()) + ' androidBridge=' + bridgeMode);
  out('document.cookie has ory_kratos_session (must be false, HttpOnly): ' + document.cookie.includes('ory_kratos_session'));
  if (P) P.markJsReady({ ts: Date.now() }).catch((e) => out('markJsReady rejected: ' + e.message));

  if (inFrame) { // iframe check (§5): this call must be rejected
    if (P) P.info().then((r) => out('!! plugin call from iframe ACCEPTED ' + JSON.stringify(r))).catch((e) => out('iframe plugin call rejected: ' + e.message));
    else out('iframe: no Capacitor object');
    return;
  }

  // ---- P1-g: service worker ----
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('/messenger-ui/sw.js', { scope: '/messenger-ui/' })
    .then(() => out('SW registered; controller=' + !!navigator.serviceWorker.controller)).catch((e) => out('SW failed: ' + e));
  $('online').textContent = navigator.onLine ? '(online)' : '(OFFLINE — served by SW?)';

  // ---- WS (P1-a, P1-e) ----
  let ws;
  function openWs() {
    ws = new WebSocket('wss://' + location.host + '/p1/ws');
    ws.onopen = () => out('WS open');
    ws.onmessage = (m) => { $('ws').textContent = m.data; };
    ws.onclose = (e) => { out('WS closed code=' + e.code); $('ws').textContent = 'closed ' + e.code; };
  }

  // ---- P1-h: timer continuity (ticks reported to native, which records gaps) ----
  let n = 0, level = 0, analyser, buf;
  setInterval(() => {
    n++;
    if (analyser) { analyser.getByteTimeDomainData(buf); let m = 0; for (const v of buf) m = Math.max(m, Math.abs(v - 128)); level = m; }
    if (P) P.tick({ n, ts: Date.now(), level, ws: ws ? ws.readyState : -1 }).catch(() => {});
  }, 1000);

  async function run() {
    const x = new XMLHttpRequest();
    x.open('GET', '/p1/echo?via=xhr'); x.onload = () => out('XHR ' + x.status + ' ' + x.responseText); x.send();
    const r = await fetch('/p1/echo?via=fetch', { credentials: 'include' });
    out('fetch ' + r.status + ' ' + (await r.text()));
    if (!ws || ws.readyState > 1) openWs();
  }

  const actions = {
    run,
    journal: async () => {
      const j = await (await fetch('/p1/journal')).json();
      const app = j.filter((e) => e.host.startsWith('app.'));
      const noSerial = app.filter((e) => !e.serial);
      const notWv = app.filter((e) => e.agent !== 'webview' && !(e.note || '').startsWith('device/ping'));
      out('journal app.*: ' + app.length + ' req, without serial: ' + noSerial.length + ', non-webview (excl. native ping): ' + notWv.length);
      out(app.slice(-25).map((e) => `${e.t} ${e.method} ${e.path} dest=${e.dest} agent=${e.agent} serial=${e.serial} reused=${e.reused}`).join('\n'));
    },
    cookies: async () => out('native cookies: ' + JSON.stringify(await P.cookies())),
    rotate: async () => { out('rotate: ' + JSON.stringify(await P.rotateCert())); await run(); },
    ping: async () => out('native ping: ' + JSON.stringify(await P.nativePing())),
    callOn: async () => {
      const s = await navigator.mediaDevices.getUserMedia({ audio: true });
      const ac = new AudioContext(); analyser = ac.createAnalyser(); buf = new Uint8Array(analyser.fftSize);
      ac.createMediaStreamSource(s).connect(analyser); window.__p1.stream = s;
      out('mic on; call service: ' + JSON.stringify(await P.callStarted({ media: 'audio' })));
    },
    callOff: async () => {
      if (window.__p1.stream) window.__p1.stream.getTracks().forEach((t) => t.stop()); analyser = null;
      out('call ended: ' + JSON.stringify(await P.callEnded()));
    },
    ticks: async () => out('ticks: ' + JSON.stringify(await P.tickReport())),
    timings: async () => out('timings: ' + JSON.stringify(await P.timings())),
    iframe: () => { const f = document.createElement('iframe'); f.src = '/messenger-ui/frame'; f.style.height = '120px'; document.body.appendChild(f); },
    enroll: () => { location.href = 'https://' + location.host.replace(/^app\./, 'enroll.') + '/messenger-ui/login'; },
    ct: () => { location.href = 'https://no-sct.badssl.com/'; },
    clear: async () => { await fetch('/p1/journal?clear=1'); $('out').textContent = ''; },
  };
  document.querySelectorAll('button[data-a]').forEach((b) => (b.onclick = () => actions[b.dataset.a]().catch((e) => out('ERR ' + b.dataset.a + ': ' + e.message))));
  if (navigator.onLine) run();
})();
