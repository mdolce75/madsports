// Service worker de Métricas DT (solo controla /dt.html): con señal todo va a la red y se guarda una copia; sin
// señal responde la última copia, así la pantalla abre y muestra el partido aunque se recargue la página en la cancha.
// Las copias de la API llevan el header X-Guardado (hora en que se guardaron) para que el reloj no se atrase.
const CACHE = "rinkmetrics-dt-v1";
const API = ["/api/sesion", "/api/dt/"];

self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", e => e.waitUntil(self.clients.claim()));

self.addEventListener("fetch", e => {
    const req = e.request;
    const url = new URL(req.url);
    if (req.method !== "GET" || url.origin !== location.origin) return;
    const esApi = API.some(p => url.pathname.startsWith(p));
    e.respondWith(fetch(req).then(res => {
        if (res.ok) {
            const copia = res.clone();
            e.waitUntil(caches.open(CACHE).then(async c => {
                if (!esApi) return c.put(req, copia);
                const headers = new Headers(copia.headers);
                headers.set("X-Guardado", String(Date.now()));
                return c.put(req, new Response(await copia.blob(), {status: copia.status, headers}));
            }));
        }
        return res;
    }).catch(async () => (await caches.match(req)) || Response.error()));
});
