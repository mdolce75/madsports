// Service worker de RinkMetrics (solo controla /coach.html): con señal todo va a la red y se guarda una copia; sin
// señal responde la última copia, así la pantalla abre y muestra el partido aunque se recargue la página en la cancha.
// Las copias de la API llevan el header X-Saved-At (hora en que se guardaron) para que el reloj no se atrase.
const CACHE = "rinkmetrics-coach-v1";
const API = ["/api/session", "/api/coach/"];

self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", e => e.waitUntil(self.clients.claim()));

self.addEventListener("fetch", e => {
    const req = e.request;
    const url = new URL(req.url);
    if (req.method !== "GET" || url.origin !== location.origin) return;
    const isApi = API.some(p => url.pathname.startsWith(p));
    e.respondWith(fetch(req).then(res => {
        if (res.ok) {
            const copy = res.clone();
            e.waitUntil(caches.open(CACHE).then(async c => {
                if (!isApi) return c.put(req, copy);
                const headers = new Headers(copy.headers);
                headers.set("X-Saved-At", String(Date.now()));
                return c.put(req, new Response(await copy.blob(), {status: copy.status, headers}));
            }));
        }
        return res;
    }).catch(async () => (await caches.match(req)) || Response.error()));
});
