// Servidor estático para los E2E de la demo (y para verla sin levantar la app: npm run demo): sirve las carpetas
// static de los módulos (como la app, que las junta en el classpath) tal cual, y /coach-demo.html con coach.html, como CoachDemoController. La demo no necesita backend:
// sus pedidos a /api/ los contesta js/coach/demo-api.js en el navegador.
import {createServer} from "node:http";
import {readFile} from "node:fs/promises";
import {extname, join, normalize} from "node:path";
import {fileURLToPath} from "node:url";

// Las de cada módulo, en el orden en que se busca un archivo: RinkMetrics, la plataforma (app.js, app.css), RinkReports
// y la app (la home).
const ROOTS = ["../../main/resources/static/", "../../../../platform/src/main/resources/static/",
    "../../../../rinkreports/src/main/resources/static/", "../../../../app/src/main/resources/static/"]
    .map(r => fileURLToPath(new URL(r, import.meta.url)));
const PORT = Number(process.env.DEMO_PORT || 8099);
const TYPES = {".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8",
    ".svg": "image/svg+xml", ".png": "image/png", ".jpg": "image/jpeg", ".webp": "image/webp", ".ico": "image/x-icon",
    ".json": "application/json"};

createServer(async (req, res) => {
    const path = decodeURIComponent(new URL(req.url, "http://localhost").pathname);
    const file = path === "/coach-demo.html" ? "coach.html" : path === "/" ? "index.html" : path.slice(1);
    for (const root of ROOTS) {
        const target = normalize(join(root, file));
        if (!target.startsWith(normalize(root))) { res.writeHead(403).end(); return; }
        try {
            const body = await readFile(target);
            res.writeHead(200, {"Content-Type": TYPES[extname(target)] || "application/octet-stream", "Cache-Control": "no-cache"});
            res.end(body);
            return;
        } catch (e) {
            // No está en este módulo: el siguiente.
        }
    }
    res.writeHead(404, {"Content-Type": "text/plain; charset=utf-8"}).end("No existe " + path);
}).listen(PORT, () => console.log(`Demo de RinkMetrics: http://localhost:${PORT}/coach-demo.html`));
