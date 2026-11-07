// KİFA — çevrimdışı önbellek. Uygulamayı güncellediğinde VERSION'ı artır.
const VERSION = 'kifa-v1.0';
const FILES = [
  './',
  './index.html',
  './manifest.webmanifest',
  './icon-192.png',
  './icon-512.png',
  './apple-touch-icon.png',
  './favicon-32.png',
  "./bricolage-grotesque-latin-500-normal.woff2",
  "./bricolage-grotesque-latin-700-normal.woff2",
  "./bricolage-grotesque-latin-ext-500-normal.woff2",
  "./bricolage-grotesque-latin-ext-700-normal.woff2",
  "./figtree-latin-400-normal.woff2",
  "./figtree-latin-500-normal.woff2",
  "./figtree-latin-600-normal.woff2",
  "./figtree-latin-ext-400-normal.woff2",
  "./figtree-latin-ext-500-normal.woff2",
  "./figtree-latin-ext-600-normal.woff2",
];
self.addEventListener('install', e => {
  e.waitUntil(caches.open(VERSION).then(c => c.addAll(FILES)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k !== VERSION).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET' || new URL(req.url).origin !== location.origin) return;
  if (req.mode === 'navigate') {
    // Sayfa: önce ağ (güncel sürüm), yoksa önbellek
    e.respondWith(fetch(req).then(r => { const copy = r.clone(); caches.open(VERSION).then(c => c.put('./index.html', copy)); return r; })
      .catch(() => caches.match('./index.html')));
    return;
  }
  e.respondWith(caches.match(req).then(hit => hit || fetch(req)));
});
