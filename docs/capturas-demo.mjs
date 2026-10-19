// Capturas de la demo de Métricas DT para la página de la sección (src/main/resources/static/img/demo/).
// Uso, con la app levantada (por defecto en http://localhost:8091):
//   node docs/capturas-demo.mjs [url-base]
// Abre Chrome sin ventana, emula cada pantalla (web o celular), espera a que la demo cargue y guarda el PNG.
// Usa solo Node (24+, con WebSocket incluido) y el Chrome instalado.
import {spawn} from "node:child_process";
import {mkdtempSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join, dirname} from "node:path";
import {fileURLToPath} from "node:url";

const BASE = process.argv[2] || "http://localhost:8091";
const SALIDA = join(dirname(fileURLToPath(import.meta.url)), "..", "src", "main", "resources", "static", "img", "demo");
const CHROME = process.env.CHROME || "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
const PUERTO = 9333;

const CAPTURAS = [
    {archivo: "carga-web.png", url: "/dt-demo.html#partido=1", ancho: 1280, alto: 800, escala: 1.5, celular: false},
    {archivo: "carga-celular.png", url: "/dt-demo.html#partido=1", ancho: 390, alto: 844, escala: 2, celular: true},
    {archivo: "resumen.png", url: "/dt-demo.html?tab=resumen", ancho: 1280, alto: 800, escala: 1.5, celular: false},
    {archivo: "dashboard.png", url: "/dt-demo.html#dashboard", ancho: 1280, alto: 800, escala: 1.5, celular: false},
    {archivo: "equipo.png", url: "/dt-demo.html#estadisticas-equipo", ancho: 1280, alto: 960, escala: 1.5, celular: false},
    {archivo: "plantel.png", url: "/dt-demo.html#equipo", ancho: 1280, alto: 800, escala: 1.5, celular: false},
    {archivo: "jugadores.png", url: "/dt-demo.html#jugadores", ancho: 1280, alto: 960, escala: 1.5, celular: false},
    {archivo: "ficha-celular.png", url: "/dt-demo.html#jugadores", ancho: 390, alto: 844, escala: 2, celular: true},
    {archivo: "informes.png", url: "/dt-demo.html?informe=CAMPEONATO#informes", ancho: 1280, alto: 960, escala: 1.5, celular: false},
];

const esperar = ms => new Promise(r => setTimeout(r, ms));

const chrome = spawn(CHROME, ["--headless=new", "--disable-gpu", "--hide-scrollbars", "--no-first-run",
    `--remote-debugging-port=${PUERTO}`, `--user-data-dir=${mkdtempSync(join(tmpdir(), "rm-capturas-"))}`, "about:blank"],
    {stdio: "ignore"});

try {
    let destino;
    for (let i = 0; i < 50 && !destino; i++) {
        await esperar(200);
        try {
            const pestañas = await (await fetch(`http://127.0.0.1:${PUERTO}/json`)).json();
            destino = pestañas.find(p => p.type === "page");
        } catch { /* Chrome todavía arrancando */ }
    }
    if (!destino) throw new Error("Chrome no respondió");

    const ws = new WebSocket(destino.webSocketDebuggerUrl);
    await new Promise((ok, mal) => { ws.onopen = ok; ws.onerror = mal; });
    let id = 0;
    const pendientes = new Map();
    ws.onmessage = ev => {
        const m = JSON.parse(ev.data);
        if (m.id && pendientes.has(m.id)) { pendientes.get(m.id)(m); pendientes.delete(m.id); }
    };
    const cdp = (method, params = {}) => new Promise(ok => { const n = ++id; pendientes.set(n, ok); ws.send(JSON.stringify({id: n, method, params})); });

    await cdp("Page.enable");
    for (const c of CAPTURAS) {
        await cdp("Emulation.setDeviceMetricsOverride", {width: c.ancho, height: c.alto, deviceScaleFactor: c.escala, mobile: c.celular});
        await cdp("Emulation.setTouchEmulationEnabled", {enabled: c.celular});
        await cdp("Page.navigate", {url: BASE + c.url});
        await esperar(4500);   // la demo carga el partido, elige un jugador y (si se pide) abre la pestaña
        const r = await cdp("Page.captureScreenshot", {format: "png", captureBeyondViewport: false});
        writeFileSync(join(SALIDA, c.archivo), Buffer.from(r.result.data, "base64"));
        console.log("ok", c.archivo);
    }
    ws.close();
} finally {
    chrome.kill();
}
