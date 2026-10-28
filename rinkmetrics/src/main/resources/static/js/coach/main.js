// RinkMetrics (/coach.html y la demo, /coach-demo.html): el menú, las vistas y el arranque. Cada vista es un módulo:
// panel.js (Control panel), matches.js y match.js (la carga en vivo), reports.js (informes, con las estadísticas y las
// fichas) y team.js.
import * as Queue from "./queue.js";
import * as Team from "./team.js";
import * as Reports from "./reports.js";
import * as Panel from "./panel.js";
import * as Match from "./match.js";
import * as Matches from "./matches.js";
import {coach, teamsSignature, fetchTeams} from "./common.js";

const VIEWS = {"dashboard": "v-dashboard", "matches": "v-elegir", "reports": "v-informes", "team": "v-plantel"};
let view = null;   // la vista del menú que se muestra (null: la carga de un partido)
// Los enlaces de antes, en castellano (#jugadores es el de antes a las fichas), siguen andando.
// Las estadísticas (del equipo y las fichas) pasaron a Informes.
const OLD_HASHES = {"partidos": "matches", "equipo": "team", "estadisticas-equipo": "reports",
    "estadisticas-jugadores": "reports", "jugadores": "reports", "informes": "reports", "team-stats": "reports",
    "player-stats": "reports"};

/** La dirección nueva de un enlace de antes, o null. */
function renamedHash(h) {
    const m = h.match(/^#(partido|informe)=(\d+)$/);
    if (m) return `#${m[1] === "partido" ? "match" : "report"}=${m[2]}`;
    return Object.hasOwn(OLD_HASHES, h.slice(1)) ? "#" + OLD_HASHES[h.slice(1)] : null;
}


/**
 * #match=ID abre la carga; #dashboard (sin nada), #matches, #reports, #report=ID y #team (el plantel) muestran esa
 * vista.
 */
function path() {
    const renamed = renamedHash(location.hash);
    if (renamed) history.replaceState(null, "", location.pathname + location.search + renamed);
    const m = location.hash.match(/^#match=(\d+)$/);
    if (m) {
        view = null;
        $("coach-menu").hidden = true;
        for (const id of Object.values(VIEWS)) if (id !== "v-elegir") $(id).hidden = true;
        Match.openItem(Number(m[1]));
        return;
    }
    Match.exit();
    const inf = location.hash.match(/^#report=(\d+)$/);
    const requested = inf ? "reports" : (location.hash.slice(1) || "dashboard");
    const v = VIEWS[requested] ? requested : "dashboard";
    view = v;
    $("coach-menu").hidden = false;
    document.querySelectorAll("#coach-menu a").forEach(a =>
        a.dataset.view === v ? a.setAttribute("aria-current", "page") : a.removeAttribute("aria-current"));
    for (const [k, id] of Object.entries(VIEWS)) $(id).hidden = k !== v;
    if (v === "reports") Reports.load(inf ? Number(inf[1]) : null);
    if (v === "dashboard") Panel.load();
    if (v === "matches") Matches.load();
    if (v === "team") Team.load();
}

/**
 * Al volver a la pestaña, los equipos se piden de nuevo: si cambiaron en otro dispositivo, la vista se vuelve a armar
 * (la de Equipo, siempre que no se esté escribiendo en sus formularios, porque también muestra el balance).
 */
async function onReturn() {
    if (document.visibilityState !== "visible" || !view || !navigator.onLine || !coach.session) return;
    const before = teamsSignature();
    try { await fetchTeams(true); } catch (e) { return; }
    if (view === "team") { if (!Team.editing()) Team.load(); return; }
    if (teamsSignature() !== before) path();
}

// ---------- Inicio ----------

// Lo que no espera a la sesión va primero: la marca y el menú quedan armados antes de DOMContentLoaded (app.js le
// agrega el enlace a la portada).
$("brand").outerHTML = BRAND;
document.querySelector(".topbar header.brand").after($("coach-menu"));   // un solo menú arriba, al lado de la marca
Match.start();
Matches.start();
Reports.start();
Team.start();
window.addEventListener("hashchange", path);
document.addEventListener("visibilitychange", onReturn);

// La demo es esta misma página: sus pedidos a /api/ los contesta demo.js en el navegador.
if (location.pathname === "/coach-demo.html") {
    coach.demo = true;
    (await import("./demo.js")).instalar();
} else if ("serviceWorker" in navigator) {
    navigator.serviceWorker.register("/coach-sw.js", {scope: "/coach.html"}).catch(() => { /* sin carga sin señal */ });
}

const s = await requireProfile(["COACHFPP", "COACHAMP", "COACHOTR"]);
if (s) {
    coach.session = s;
    if (s.subscription && s.subscription.matchesPerMonth) $("cupo").textContent = `${s.subscription.matchesPerMonth} partidos por mes`;
    await Queue.start(async ev => {
        if (!(await Match.onQueueChange(ev)) && view === "matches" && ev.match) Matches.load();
    });
    // Un DT sin equipos empieza por la sección Equipos: sin equipo ni jugadores no hay partido que cargar.
    try {
        if (!location.hash && !(await fetchTeams()).length) history.replaceState(null, "", location.pathname + location.search + "#team");
    } catch (e) { /* sin señal: el panel avisa */ }
    path();
}
