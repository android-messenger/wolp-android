// S1-3 probe (see index.html). Results go to #out and to console (CDP / logcat chromium).
'use strict';
const $ = id => document.getElementById(id);
const log = (...a) => {
  const line = new Date().toISOString().slice(11, 19) + ' ' + a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' ');
  console.log('PROBE ' + line);
  $('out').textContent = line + '\n' + $('out').textContent;
};
const inFrame = window !== window.top;
if (inFrame) {
  // S1-4 checks that plugins answer only to the top frame of app.<host>; here only report what is visible
  document.body.innerHTML = 'iframe: Capacitor=' + typeof window.Capacitor + ', DeviceTrust=' +
    typeof window.Capacitor?.Plugins?.DeviceTrust;
} else {
  const cap = window.Capacitor;
  const css = getComputedStyle(document.body);
  $('info').textContent = [
    'origin ' + location.origin,
    'Capacitor ' + (cap ? 'native=' + cap.isNativePlatform?.() + ' platform=' + cap.getPlatform?.() : 'нет'),
    'UA ' + (navigator.userAgent.match(/Chrome\/[\d.]+/) || ['?'])[0],
    'safe-area (отступы body) ' + ['Top', 'Right', 'Bottom', 'Left'].map(s => css['padding' + s]).join(' '),
  ].join(' · ');
  $('ifr').src = '/messenger-ui/?frame=1';
  const showHist = () => $('hist').textContent = 'history.length=' + history.length + ' hash=' + location.hash;
  addEventListener('popstate', () => { log('popstate ' + location.hash); showHist(); });
  showHist();

  let stream = null;
  const media = async c => {
    try {
      stream?.getTracks().forEach(t => t.stop());
      stream = await navigator.mediaDevices.getUserMedia(c);
      $('v').srcObject = stream;
      log('getUserMedia OK', stream.getTracks().map(t => t.kind + ':' + t.label));
    } catch (e) { log('getUserMedia ' + e.name + ': ' + e.message); }
  };

  const actions = {
    async echo() { // traefik/whoami: /echo is its WebSocket echo
      const r = await fetch('/messenger/headers', {credentials: 'include'});
      const t = await r.text();
      log('echo ' + r.status + ' deny=' + r.headers.get('X-Device-Deny'),
        t.split('\n').filter(l => /^(X-Device|X-User|Cookie:)/i.test(l)).map(l => l.replace(/(ory_kratos_session=)[^;]+/, '$1…')));
    },
    async ping() {
      const r = await fetch('/v1/device/ping');
      log('ping ' + r.status + ' renew=' + r.headers.get('X-Device-Renew') + ' deny=' + r.headers.get('X-Device-Deny'));
    },
    // main-frame request to a session-required path: NO_SESSION / USER_MISMATCH / device codes reach the native side
    doc() { location.href = '/messenger/whoami'; },
    reload() { location.reload(); },
    mic() { return media({audio: true}); },
    cam() { return media({video: true, audio: true}); },
    stop() { stream?.getTracks().forEach(t => t.stop()); stream = null; log('media stopped'); },
    push() { history.pushState({}, '', '#' + (history.length + 1)); showHist(); },
    async seed() {
      for (const n of ['e2ee-signal', 'e2ee-device', 'e2ee-plaintext']) {
        await new Promise((ok, fail) => {
          const q = indexedDB.open(n, 1);
          q.onupgradeneeded = () => q.result.createObjectStore('probe');
          q.onsuccess = () => { const db = q.result; const tx = db.transaction('probe', 'readwrite'); tx.objectStore('probe').put(Date.now(), 'marker'); tx.oncomplete = () => { db.close(); ok(); }; };
          q.onerror = () => fail(q.error);
        });
      }
      log('seeded e2ee-*'); return actions.dbs();
    },
    async dbs() { log('databases', (await indexedDB.databases()).map(d => d.name)); },
  };
  document.addEventListener('click', async e => {
    const a = e.target.dataset?.a;
    if (!a) return;
    try { await actions[a](); } catch (err) { log(a + ' ✗ ' + err); }
  });
  document.addEventListener('change', e => {
    const f = e.target.dataset?.f;
    if (f) log('file ' + f + ':', [...e.target.files].map(x => x.name + ' ' + x.type + ' ' + x.size));
  });
  log('loaded');
}
