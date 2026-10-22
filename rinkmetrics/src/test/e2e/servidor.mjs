// Servidor estático para los E2E de la demo (y para verla sin levantar la app: npm run demo): sirve las carpetas
// static de los módulos (como la app, que las junta en el classpath) tal cual, y /dt-demo.html con dt.html, como DemoDtController. La demo no necesita backend:
// sus pedidos a /api/ los contesta js/dt/demo-api.js en el navegador.
import {createServer} from "node:http";
import {readFile} from "node:fs/promises";
import {extname, join, normalize} from "node:path";
import {fileURLToPath} from "node:url";

// Las de cada módulo, en el orden en que se busca un archivo: RinkMetrics, la plataforma (app.js, app.css), RinkReports
// y la app (la home).
const RAICES = ["../../main/resources/static/", "../../../../plataforma/src/main/resources/static/",
    "../../../../rinkreports/src/main/resources/static/", "../../../../app/src/main/resources/static/"]
    .map(r => fileURLToPath(new URL(r, import.meta.url)));
const PUERTO = Number(process.env.PUERTO_DEMO || 8099);
const TIPOS = {".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8",
    ".svg": "image/svg+xml", ".png": "image/png", ".jpg": "image/jpeg", ".webp": "image/webp", ".ico": "image/x-icon",
    ".json": "application/json"};

createServer(async (req, res) => {
    const ruta = decodeURIComponent(new URL(req.url, "http://localhost").pathname);
    const archivo = ruta === "/dt-demo.html" ? "dt.html" : ruta === "/" ? "index.html" : ruta.slice(1);
    for (const raiz of RAICES) {
        const destino = normalize(join(raiz, archivo));
        if (!destino.startsWith(normalize(raiz))) { res.writeHead(403).end(); return; }
        try {
            const cuerpo = await readFile(destino);
            res.writeHead(200, {"Content-Type": TIPOS[extname(destino)] || "application/octet-stream", "Cache-Control": "no-cache"});
            res.end(cuerpo);
            return;
        } catch (e) {
            // No está en este módulo: el siguiente.
        }
    }
    res.writeHead(404, {"Content-Type": "text/plain; charset=utf-8"}).end("No existe " + ruta);
}).listen(PUERTO, () => console.log(`Demo de RinkMetrics: http://localhost:${PUERTO}/dt-demo.html`));
