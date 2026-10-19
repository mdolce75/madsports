// Servidor estático para los E2E de la demo (y para verla sin levantar la app: npm run demo): sirve
// src/main/resources/static tal cual, y /dt-demo.html con dt.html, como DemoDtController. La demo no necesita backend:
// sus pedidos a /api/ los contesta js/dt/demo-api.js en el navegador.
import {createServer} from "node:http";
import {readFile} from "node:fs/promises";
import {extname, join, normalize} from "node:path";
import {fileURLToPath} from "node:url";

const RAIZ = fileURLToPath(new URL("../../main/resources/static/", import.meta.url));
const PUERTO = Number(process.env.PUERTO_DEMO || 8099);
const TIPOS = {".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8",
    ".svg": "image/svg+xml", ".png": "image/png", ".jpg": "image/jpeg", ".webp": "image/webp", ".ico": "image/x-icon",
    ".json": "application/json"};

createServer(async (req, res) => {
    const ruta = decodeURIComponent(new URL(req.url, "http://localhost").pathname);
    const archivo = ruta === "/dt-demo.html" ? "dt.html" : ruta === "/" ? "index.html" : ruta.slice(1);
    const destino = normalize(join(RAIZ, archivo));
    if (!destino.startsWith(normalize(RAIZ))) { res.writeHead(403).end(); return; }
    try {
        const cuerpo = await readFile(destino);
        res.writeHead(200, {"Content-Type": TIPOS[extname(destino)] || "application/octet-stream", "Cache-Control": "no-cache"});
        res.end(cuerpo);
    } catch (e) {
        res.writeHead(404, {"Content-Type": "text/plain; charset=utf-8"}).end("No existe " + ruta);
    }
}).listen(PUERTO, () => console.log(`Demo de Métricas DT: http://localhost:${PUERTO}/dt-demo.html`));
