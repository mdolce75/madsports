// Métricas DT (/dt.html y la demo, /dt-demo.html): el menú, las vistas y el arranque. Cada vista es un módulo:
// panel.js (Control panel), partidos.js y partido.js (la carga en vivo), estadisticas.js, informes.js y equipo.js.
import * as Cola from "./cola.js";
import * as Equipo from "./equipo.js";
import * as Estadisticas from "./estadisticas.js";
import * as Informes from "./informes.js";
import * as Panel from "./panel.js";
import * as Partido from "./partido.js";
import * as Partidos from "./partidos.js";
import {dt, firmaEquipos, traerEquipos} from "./comun.js";

const VISTAS = {"dashboard": "v-dashboard", "partidos": "v-elegir", "estadisticas-equipo": "v-equipo",
    "estadisticas-jugadores": "v-jugadores", "informes": "v-informes", "equipo": "v-plantel"};
let vista = null;   // la vista del menú que se muestra (null: la carga de un partido)

/** Submenú Estadísticas (Equipo y Jugadores): se abre con su botón y se cierra al elegir o al tocar afuera. */
function submenu(abrir) {
    $("dt-sub-est-menu").hidden = !abrir;
    document.querySelector("#dt-sub-est .dt-sub-btn").setAttribute("aria-expanded", String(abrir));
}

/**
 * #partido=ID abre la carga; #dashboard (sin nada), #partidos, #estadisticas-equipo, #estadisticas-jugadores,
 * #informes, #informe=ID y #equipo (el plantel) muestran esa vista.
 */
function ruta() {
    const m = location.hash.match(/^#partido=(\d+)$/);
    if (m) {
        vista = null;
        $("dt-menu").hidden = true;
        for (const id of Object.values(VISTAS)) if (id !== "v-elegir") $(id).hidden = true;
        Partido.abrir(Number(m[1]));
        return;
    }
    Partido.salir();
    const inf = location.hash.match(/^#informe=(\d+)$/);
    // #jugadores es el enlace de antes a las fichas.
    const pedida = inf ? "informes" : location.hash === "#jugadores" ? "estadisticas-jugadores" : (location.hash.slice(1) || "dashboard");
    const v = VISTAS[pedida] ? pedida : "dashboard";
    vista = v;
    $("dt-menu").hidden = false;
    document.querySelectorAll("#dt-menu a").forEach(a =>
        a.dataset.vista === v ? a.setAttribute("aria-current", "page") : a.removeAttribute("aria-current"));
    $("dt-sub-est").classList.toggle("activo", v.startsWith("estadisticas-"));
    submenu(false);
    for (const [k, id] of Object.entries(VISTAS)) $(id).hidden = k !== v;
    if (v === "informes") Informes.cargar(inf ? Number(inf[1]) : null);
    if (v === "dashboard") Panel.cargar();
    if (v === "partidos") Partidos.cargar();
    if (v === "estadisticas-jugadores") Estadisticas.cargarFichas();
    if (v === "estadisticas-equipo") Estadisticas.cargarEquipo();
    if (v === "equipo") Equipo.cargar();
}

/**
 * Al volver a la pestaña, los equipos se piden de nuevo: si cambiaron en otro dispositivo, la vista se vuelve a armar
 * (la de Equipo, siempre que no se esté escribiendo en sus formularios, porque también muestra el balance).
 */
async function alVolver() {
    if (document.visibilityState !== "visible" || !vista || !navigator.onLine || !dt.sesion) return;
    const antes = firmaEquipos();
    try { await traerEquipos(true); } catch (e) { return; }
    if (vista === "equipo") { if (!Equipo.editando()) Equipo.cargar(); return; }
    if (firmaEquipos() !== antes) ruta();
}

// ---------- Inicio ----------

// Lo que no espera a la sesión va primero: la marca y el menú quedan armados antes de DOMContentLoaded (app.js le
// agrega el enlace a la portada).
$("brand").outerHTML = BRAND;
document.querySelector(".topbar header.brand").after($("dt-menu"));   // un solo menú arriba, al lado de la marca
document.querySelector("#dt-sub-est .dt-sub-btn").addEventListener("click", ev => {
    ev.stopPropagation();
    submenu($("dt-sub-est-menu").hidden);
});
document.addEventListener("click", ev => { if (!ev.target.closest("#dt-sub-est")) submenu(false); });
document.addEventListener("keydown", ev => { if (ev.key === "Escape") submenu(false); });
for (const id of ["e-equipo", "j-equipo"]) $(id).addEventListener("change", () => ruta());
Partido.iniciar();
Partidos.iniciar();
Informes.iniciar();
Equipo.iniciar();
window.addEventListener("hashchange", ruta);
document.addEventListener("visibilitychange", alVolver);

// La demo es esta misma página: sus pedidos a /api/ los contesta demo.js en el navegador.
if (location.pathname === "/dt-demo.html") {
    dt.demo = true;
    (await import("./demo.js")).instalar();
} else if ("serviceWorker" in navigator) {
    navigator.serviceWorker.register("/dt-sw.js", {scope: "/dt.html"}).catch(() => { /* sin carga sin señal */ });
}

const s = await requireProfile(["DTFPP", "DTAMP"]);
if (s) {
    dt.sesion = s;
    if (s.suscripcion && s.suscripcion.partidosPorMes) $("cupo").textContent = `${s.suscripcion.partidosPorMes} partidos por mes`;
    await Cola.iniciar(async ev => {
        if (!(await Partido.alCambiarCola(ev)) && vista === "partidos" && ev.partido) Partidos.cargar();
    });
    // Un DT sin equipos empieza por la sección Equipo: sin equipo ni jugadores no hay partido que cargar.
    try {
        if (!location.hash && !(await traerEquipos()).length) history.replaceState(null, "", location.pathname + location.search + "#equipo");
    } catch (e) { /* sin señal: el panel avisa */ }
    ruta();
}
