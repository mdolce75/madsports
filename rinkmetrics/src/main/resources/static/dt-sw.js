// El service worker de antes (/dt.html pasó a /coach.html, con /coach-sw.js): el navegador lo vuelve a pedir para
// actualizarlo, y esta versión se borra sola con su copia, así no queda una pantalla vieja guardada.
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", e => e.waitUntil((async () => {
    await caches.delete("rinkmetrics-dt-v1");
    await self.registration.unregister();
})()));
