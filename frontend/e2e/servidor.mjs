// Servidor estático para los E2E de la demo (y para verla sin levantar la app: npm run demo): sirve
// src/main/resources/static tal cual, /dt-demo.html con dt.html (como DemoDtController) y /dt-app/ con la versión en
// React ya construida (frontend/dist, con npm run build). La demo no necesita backend: sus pedidos a /api/ los
// contesta la API simulada en el navegador.
import {createServer} from "node:http";
import {readFile} from "node:fs/promises";
import {extname, join, normalize} from "node:path";
import {fileURLToPath} from "node:url";

const ESTATICOS = fileURLToPath(new URL("../../src/main/resources/static/", import.meta.url));
const REACT = fileURLToPath(new URL("../dist/", import.meta.url));
const PUERTO = Number(process.env.PUERTO_DEMO || 8099);
const TIPOS = {".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8",
    ".svg": "image/svg+xml", ".png": "image/png", ".jpg": "image/jpeg", ".webp": "image/webp", ".ico": "image/x-icon",
    ".json": "application/json"};

/** La carpeta y el archivo que corresponden a la ruta pedida. */
function destino(ruta) {
    if (ruta === "/dt-app" || ruta === "/dt-app/") return [REACT, "index.html"];
    if (ruta.startsWith("/dt-app/")) return [REACT, ruta.slice("/dt-app/".length)];
    return [ESTATICOS, ruta === "/dt-demo.html" ? "dt.html" : ruta === "/" ? "index.html" : ruta.slice(1)];
}

createServer(async (req, res) => {
    const ruta = decodeURIComponent(new URL(req.url, "http://localhost").pathname);
    const [raiz, archivo] = destino(ruta);
    const camino = normalize(join(raiz, archivo));
    if (!camino.startsWith(normalize(raiz))) { res.writeHead(403).end(); return; }
    try {
        const cuerpo = await readFile(camino);
        res.writeHead(200, {"Content-Type": TIPOS[extname(camino)] || "application/octet-stream", "Cache-Control": "no-cache"});
        res.end(cuerpo);
    } catch {
        res.writeHead(404, {"Content-Type": "text/plain; charset=utf-8"}).end("No existe " + ruta);
    }
}).listen(PUERTO, () => console.log(`Demo de Métricas DT: http://localhost:${PUERTO}/dt-demo.html y /dt-app/?demo`));
