// P1-g: minimal app-shell service worker (stand-in for vite-plugin-pwa generateSW precache + navigation fallback).
const CACHE = 'p1-shell-v1';
const SHELL = ['/messenger-ui/', '/messenger-ui/probe.js'];
self.addEventListener('install', (e) => { e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL))); self.skipWaiting(); });
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));
self.addEventListener('fetch', (e) => {
  const u = new URL(e.request.url);
  if (e.request.mode === 'navigate' && u.pathname.startsWith('/messenger-ui/')) {
    e.respondWith(fetch(e.request).catch(() => caches.match('/messenger-ui/')));
  } else if (SHELL.includes(u.pathname)) {
    e.respondWith(fetch(e.request).catch(() => caches.match(e.request)));
  }
});
