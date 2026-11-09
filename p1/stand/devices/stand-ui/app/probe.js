// S1-3/S1-4 probe (see index.html). Results go to #out and to console (CDP / logcat chromium).
'use strict';
const $ = id => document.getElementById(id);
const log = (...a) => {
  const line = new Date().toISOString().slice(11, 19) + ' ' + a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' ');
  console.log('PROBE ' + line);
  $('out').textContent = line + '\n' + $('out').textContent;
};
const inFrame = window !== window.top;
// Stand: app = :9444, enroll = :9443 (adb reverse); production-like hosts: app.<host> / enroll.<host>.
const isEnroll = location.port === '9443' || location.hostname.startsWith('enroll.');
const enrollOrigin = location.port === '9444' ? location.origin.replace(':9444', ':9443')
  : location.origin.replace('//app.', '//enroll.');

// One DeviceTrust call → 'ok …' / 'reject CODE' / 'timeout' / 'no bridge'. Never hangs (S1-4: refusals must reject).
const dt = (method, args) => {
  const p = window.Capacitor?.Plugins?.DeviceTrust;
  if (!window.androidBridge && !p) return Promise.resolve({r: 'no bridge'});
  if (!p) return Promise.resolve({r: 'no plugin (androidBridge=' + typeof window.androidBridge + ')'});
  return Promise.race([
    p[method](args).then(v => ({r: 'ok', v}), e => ({r: 'reject ' + (e?.code || e?.message || e)})),
    new Promise(ok => setTimeout(() => ok({r: 'timeout'}), 3000)),
  ]);
};
const b64 = u8 => btoa(String.fromCharCode(...u8));
const unb64 = s => Uint8Array.from(atob(s), c => c.charCodeAt(0));

// The whole S1-4 matrix from wherever this page runs; each line: name → expected → actual.
async function dtAll() {
  const where = (isEnroll ? 'enroll' : 'app') + (inFrame ? ' iframe' : ' top');
  const allowed = !isEnroll && !inFrame;
  const lines = [];
  const check = (name, got, expected) => { lines.push((got.startsWith(expected) ? 'OK  ' : 'FAIL') + ' ' + name + ': ' + got + ' (ждали ' + expected + ')'); };
  const st = await dt('getState');
  check('getState', st.r + (st.v ? ' ' + JSON.stringify(st.v) : ''), allowed ? 'ok' : (isEnroll ? 'no bridge' : 'reject PERMISSION_DENIED'));
  const info = await dt('getDeviceInfo');
  check('getDeviceInfo', info.r + (info.v ? ' ' + JSON.stringify(info.v) : ''), allowed ? 'ok' : (isEnroll ? 'no bridge' : 'reject PERMISSION_DENIED'));
  const plain = crypto.getRandomValues(new Uint8Array(32));
  const w = await dt('vaultWrap', {data: b64(plain), purpose: 'e2ee'});
  check('vaultWrap e2ee', w.r, allowed ? 'ok' : (isEnroll ? 'no bridge' : 'reject PERMISSION_DENIED'));
  if (allowed && w.v) {
    const u = await dt('vaultUnwrap', {blob: w.v.blob, purpose: 'e2ee'});
    check('vaultUnwrap e2ee = исходное', u.r + ' ' + (u.v && b64(unb64(u.v.data)) === b64(plain)), 'ok true');
    check('vaultUnwrap как e2ee-plaintext', (await dt('vaultUnwrap', {blob: w.v.blob, purpose: 'e2ee-plaintext'})).r, 'reject VAULT_CORRUPT');
    const w2 = await dt('vaultWrap', {data: b64(plain), purpose: 'e2ee-plaintext'});
    check('vaultWrap e2ee-plaintext', w2.r, 'ok');
    const bad = unb64(w.v.blob); bad[bad.length - 1] ^= 1;
    check('vaultUnwrap испорченный', (await dt('vaultUnwrap', {blob: b64(bad), purpose: 'e2ee'})).r, 'reject VAULT_CORRUPT');
    for (const p of ['names', 'push-key', 'E2EE', undefined]) {
      check('vaultWrap purpose=' + p, (await dt('vaultWrap', {data: b64(plain), purpose: p})).r, 'reject INVALID_ARGUMENT');
    }
    check('vaultUnwrap purpose=names', (await dt('vaultUnwrap', {blob: w.v.blob, purpose: 'names'})).r, 'reject INVALID_ARGUMENT');
    check('vaultWrap data не base64', (await dt('vaultWrap', {data: '%%', purpose: 'e2ee'})).r, 'reject INVALID_ARGUMENT');
  }
  const fails = lines.filter(l => l.startsWith('FAIL')).length;
  return 'DT ' + where + ': ' + (fails ? fails + ' FAIL' : 'all OK') + '\n' + lines.join('\n');
}

if (inFrame) {
  // S1-4: from an iframe (app or enroll origin) every plugin call must fail fast, not hang
  document.body.textContent = 'iframe ' + location.origin + ': Capacitor=' + typeof window.Capacitor + ', androidBridge=' + typeof window.androidBridge + ' …';
  dtAll().then(t => { document.body.innerHTML = '<pre></pre>'; document.body.firstChild.textContent = t; console.log('PROBE ' + t); });
} else {
  const cap = window.Capacitor;
  const css = getComputedStyle(document.body);
  $('info').textContent = [
    'origin ' + location.origin,
    'Capacitor ' + (cap ? 'native=' + cap.isNativePlatform?.() + ' platform=' + cap.getPlatform?.() : 'нет'),
    'UA ' + (navigator.userAgent.match(/Chrome\/[\d.]+/) || ['?'])[0],
    'safe-area (отступы body) ' + ['Top', 'Right', 'Bottom', 'Left'].map(s => css['padding' + s]).join(' '),
  ].join(' · ');
  $('host').textContent = isEnroll ? 'enroll' : 'app';
  $('ifr').src = location.pathname + '?frame=1';
  $('ifr2').src = enrollOrigin + '/messenger-ui/?frame=1';
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
    async dtAll() { log(await dtAll()); },
    async dtState() { log('getState', await dt('getState')); },
    async dtInfo() { log('getDeviceInfo', await dt('getDeviceInfo')); },
    async dtVault() {
      const w = await dt('vaultWrap', {data: btoa('probe'), purpose: 'e2ee'});
      log('vaultWrap', w.r, w.v ? w.v.blob.length + ' b64 chars' : '');
      if (w.v) log('vaultUnwrap', await dt('vaultUnwrap', {blob: w.v.blob, purpose: 'e2ee'}));
    },
    enroll() { location.href = enrollOrigin + '/messenger-ui/'; },
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
