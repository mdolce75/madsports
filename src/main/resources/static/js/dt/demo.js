// Demo de Métricas DT (/dt-demo.html, la misma página que /dt.html): un equipo de ejemplo con su plantel, partidos ya
// jugados, un partido en juego y dos programados. Los pedidos a /api/ no salen del navegador: los contesta demo-api.js
// como lo haría el servidor. Nada se guarda: al recargar, la demo vuelve a empezar.
import {crearApi} from "./demo-api.js";

const DUR = 25 * 60;
const iso = d => d.toISOString().slice(0, 10);
const hoy = iso(new Date());
const dia = (meses, d) => { const x = new Date(); x.setMonth(x.getMonth() - meses); x.setDate(d); return iso(x); };
const enDias = n => { const x = new Date(); x.setDate(x.getDate() + n); return iso(x); };

const SESION = {
    autenticado: true, usuario: "demo", nombre: "DT", apellido: "Demo", email: null, perfiles: ["DTFPP"],
    club: {id: 0, nombre: "CLUB DEMO", codigo: "DEMO"}, jugador: null, inicio: "/dt-demo.html",
    suscripcion: {codigo: "DT_MENSUAL", nombre: "DT mensual", equiposMax: 3, partidosPorMes: 25},
    informesPedidos: 0, federacion: "FPP",
};
const PLANTEL = [
    [101, "1", "Martín", "Acosta", true], [102, "12", "Lucas", "Benítez", true], [103, "3", "Tomás", "Ferreyra", false],
    [104, "5", "Joaquín", "Ríos", false], [105, "7", "Facundo", "Medina", false], [106, "8", "Nicolás", "Sosa", false],
    [107, "9", "Agustín", "Paz", false], [108, "10", "Santiago", "Herrera", false], [109, "11", "Bruno", "Castro", false],
    [110, "14", "Mateo", "Giménez", false],
].map(([id, numero, nombres, apellido, arquero]) => ({id, numero, nombres, apellido, arquero, equipos: [1]}));
const nombreDe = id => { const j = PLANTEL.find(x => x.id === id); return j ? j.nombres + " " + j.apellido : ""; };

let seq = 0;
const accion = (jugadorId, tipo, resultado, tiempo, segundo) => ({
    id: "00000000-0000-4000-8000-" + String(++seq).padStart(12, "0"), jugadorId, jugadorNombre: nombreDe(jugadorId),
    tipo, resultado, tiempo, segundo});

// Historia de la demo: partidos ya jugados en los últimos meses (con acciones generadas siempre iguales).
let semilla = 7;
const azar = () => (semilla = (semilla * 16807) % 2147483647) / 2147483647;
const elegir = lista => lista[Math.floor(azar() * lista.length)];
const CAMPO = PLANTEL.filter(j => !j.arquero).map(j => j.id);
const TIPOS = [["PERDIDA", "PROPIO"], ["PERDIDA", "RIVAL"], ["RECUPERACION", "PROPIO"], ["RECUPERACION", "RIVAL"],
    ["RECUPERACION", "RIVAL"], ["TIRO", "GOL"], ["TIRO", "NO_GOL"], ["TIRO", "NO_GOL"], ["FALTA", "COMETIDA"],
    ["FALTA", "RECIBIDA"], ["LIBRE_FAVOR", "NO_GOL"], ["LIBRE_FAVOR", "GOL"], ["PENAL_FAVOR", "GOL"],
    ["PENAL_FAVOR", "NO_GOL"], ["TARJETA", "AMARILLA"]];

function jugado(id, rival, fecha, local, torneo) {
    const acciones = [];
    for (let i = 0; i < 26; i++) {
        const [tipo, res] = elegir(TIPOS);
        // Los delanteros (7, 9, 10) tiran más; el resto recupera más.
        const j = ["TIRO", "PENAL_FAVOR", "LIBRE_FAVOR"].includes(tipo) ? elegir([105, 107, 108, 108, 109]) : elegir(CAMPO);
        acciones.push(accion(j, tipo, res, 1 + (i % 2), Math.floor(azar() * DUR)));
    }
    for (let i = 0; i < 4; i++) acciones.push(accion(elegir([101, 101, 102]), elegir(["ATAJADA", "ATAJADA", "GOL_RECIBIDO"]), null, 1 + (i % 2), Math.floor(azar() * DUR)));
    return {id, equipoId: 1, torneo, rival, fecha, local, minutosTiempo: 25, estado: "CERRADO", tiempo: 2, segundos: DUR,
        cerrado: Date.now(), acciones};
}

const PARTIDOS = [
    // El partido de ejemplo ya empezado: 1T, 12' jugados, con algunas acciones cargadas.
    {id: 1, equipoId: 1, torneo: "Clausura", rival: "Deportivo Oeste", fecha: hoy, local: true, minutosTiempo: 25,
        estado: "EN_JUEGO", tiempo: 1, segundos: 12 * 60 + 18, desde: Date.now(), acciones: [
            accion(104, "RECUPERACION", "RIVAL", 1, 95), accion(104, "TIRO", "GOL", 1, 101),
            accion(107, "PERDIDA", "PROPIO", 1, 212), accion(101, "ATAJADA", null, 1, 215),
            accion(108, "FALTA", "COMETIDA", 1, 288), accion(101, "LIBRE_CONTRA", "ATAJADO", 1, 290),
            accion(105, "TIRO", "NO_GOL", 1, 344), accion(106, "RECUPERACION", "PROPIO", 1, 402),
            accion(109, "TARJETA", "AZUL", 1, 455), accion(107, "TIRO", "GOL", 1, 610),
            accion(101, "GOL_RECIBIDO", null, 1, 668),
        ]},
    jugado(2, "Atlético Norte", dia(1, 20), false, "Clausura"), jugado(3, "Unión Patín", dia(1, 6), true, "Clausura"),
    jugado(4, "Sporting Central", dia(2, 24), true, "Clausura"), jugado(5, "Club Social Sur", dia(2, 9), false, "Apertura"),
    jugado(6, "Deportivo Oeste", dia(3, 15), false, "Apertura"), jugado(7, "Atlético Norte", dia(4, 18), true, "Apertura"),
    {id: 8, equipoId: 1, torneo: "Clausura", rival: "Club Social Sur", fecha: enDias(3), local: true, minutosTiempo: 25,
        estado: "PENDIENTE"},
    {id: 9, equipoId: 1, torneo: "Clausura", rival: "Unión Patín", fecha: enDias(10), local: false, minutosTiempo: 25,
        estado: "PENDIENTE"},
];

/** Los datos de ejemplo, nuevos en cada llamada (la API simulada los modifica): sesión, equipos, jugadores y partidos. */
export function datosDemo() {
    return structuredClone({sesion: SESION, equipos: [{id: 1, nombre: "Primera Masculino"}], jugadores: PLANTEL,
        partidos: PARTIDOS});
}

/**
 * Hace que fetch conteste los pedidos a /api/ con la API simulada (con un instante de demora, como una respuesta real);
 * el resto (fuentes, estilos) va a la red. Al salir, vuelve a la presentación de Métricas DT. Lo usan esta página y la
 * versión en React.
 */
export function simularApi(demora = 60) {
    const api = crearApi(datosDemo());
    const red = window.fetch.bind(window);
    window.fetch = async (input, init = {}) => {
        const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
        const u = new URL(url, location.origin);
        if (!u.pathname.startsWith("/api/")) return red(input, init);
        if (u.pathname === "/api/logout") { location.replace("/dt-inicio.html"); return new Response("{}"); }
        if (demora) await new Promise(r => setTimeout(r, demora));
        return api((init.method || "GET").toUpperCase(), u.pathname + u.search, init.body ? JSON.parse(init.body) : null);
    };
}

/** Instala la demo en esta página: la API simulada y los enlaces directos de las capturas (?tab=, ?informe=). */
export function instalar() {
    simularApi();

    // La demo arranca en el Control panel, con el menú completo. Con ?tab= (capturas) abre directamente el partido en
    // esa pestaña; con ?informe=TIPO, ese tipo de informe.
    const q = new URLSearchParams(location.search);
    if (!location.hash) history.replaceState(null, "", location.pathname + location.search + (q.get("tab") ? "#partido=1" : "#dashboard"));
    const tab = q.get("tab"), informe = q.get("informe");
    if (informe) setTimeout(() => document.querySelector(`#i-tipo [data-tipo='${informe}']`)?.click(), 700);
    if (tab) setTimeout(() => {
        document.querySelector("[aria-label^='9 '], [aria-label^='10 ']")?.click();
        $("t-" + tab)?.click();
    }, 700);
    // Un jugador ya elegido en el partido de ejemplo, para que se vea la selección.
    setTimeout(() => {
        if (location.hash === "#partido=1" && !document.querySelector(".dt-player[aria-pressed='true']")) {
            document.querySelector("[aria-label^='10 ']")?.click();
        }
    }, 600);
}
