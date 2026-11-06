// נשמר רק שלד הממשק. תמונות המסך, הודעות השליטה ומפתחות הזיווג אינם נשמרים במטמון השירות.
const CACHE = 'karov-shell-v4';
const SHELL = [
  '/',
  '/style.css?v=2',
  '/app.mjs',
  '/connection.mjs',
  '/protocol.mjs',
  '/store.mjs',
  '/icon.svg',
  '/app.webmanifest',
];
const SHELL_PATHS = new Set(SHELL.map((path) => new URL(path, self.location.origin).pathname));
self.addEventListener('install', (event) =>
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL))),
);
self.addEventListener('activate', (event) =>
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))),
      ),
  ),
);
self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  if (
    event.request.method !== 'GET' ||
    url.origin !== location.origin ||
    !SHELL_PATHS.has(url.pathname)
  )
    return;
  event.respondWith(
    fetch(event.request).catch(() => caches.match(event.request, { ignoreSearch: true })),
  );
});
