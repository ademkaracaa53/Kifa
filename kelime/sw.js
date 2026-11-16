// Kelime 5000 — çevrimdışı önbellek. Uygulamayı ya da kelime listesini güncellediğinde VERSION'ı artır.
const VERSION = 'kelime-v1.0';
const FILES = [
  './', './index.html', './manifest.webmanifest',
  './icon-192.png', './icon-512.png', './apple-touch-icon.png', './favicon-32.png',
  './data/A1.txt', './data/A2.txt', './data/B1.txt', './data/B2.txt', './data/C1.txt',
];
const FONTS = [
  '../bricolage-grotesque-latin-500-normal.woff2', '../bricolage-grotesque-latin-700-normal.woff2',
  '../bricolage-grotesque-latin-ext-500-normal.woff2', '../bricolage-grotesque-latin-ext-700-normal.woff2',
  '../figtree-latin-400-normal.woff2', '../figtree-latin-600-normal.woff2',
  '../figtree-latin-ext-400-normal.woff2', '../figtree-latin-ext-600-normal.woff2',
];
self.addEventListener('install', e => {
  e.waitUntil(caches.open(VERSION).then(async c => {
    await c.addAll(FILES);
    await Promise.all(FONTS.map(f => c.add(f).catch(() => {}))); // yazı tipleri yoksa sistem fontu kullanılır
  }).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k.startsWith('kelime-') && k !== VERSION).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET' || new URL(req.url).origin !== location.origin) return;
  // Sayfa ve kelime listeleri: önce ağ (güncel sürüm), yoksa önbellek. Diğerleri: önce önbellek.
  const fresh = req.mode === 'navigate' || /\/data\/[A-C][12]\.txt$/.test(new URL(req.url).pathname);
  if (fresh) {
    e.respondWith(fetch(req).then(r => { if (r.ok) { const copy = r.clone(); caches.open(VERSION).then(c => c.put(req.mode === 'navigate' ? './index.html' : req, copy)); } return r; })
      .catch(() => caches.match(req.mode === 'navigate' ? './index.html' : req)));
    return;
  }
  e.respondWith(caches.match(req).then(hit => hit || fetch(req)));
});
