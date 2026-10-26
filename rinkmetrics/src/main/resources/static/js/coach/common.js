// Lo que comparten las vistas de RinkMetrics: la sesión, los equipos del DT, los pedidos a la API, el aviso de abajo
// (toast) y utilidades de fechas y de elementos. Usa $ y api de app.js (script clásico, cargado antes).

/** Estado compartido: la sesión del DT, sus equipos y si es la demo. */
export const coach = {session: null, teams: [], demo: false};

export const STATUS = {PENDING: "Sin iniciar", IN_PLAY: "En juego", HALF_TIME: "Entretiempo", FINISHED: "Terminado",
    CLOSED: "Cerrado"};

/** Fecha de hoy en el teléfono (AAAA-MM-DD, hora local). */
export const today = () => { const d = new Date(); return new Date(d - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10); };
export const mmss = s => String(Math.floor(s / 60)).padStart(2, "0") + ":" + String(s % 60).padStart(2, "0");
export const dayMonth = f => f ? f.slice(8, 10) + "/" + f.slice(5, 7) : "";
export const shortName = n => { const w = (n || "").trim().split(/\s+/); return w[w.length - 1] || n; };
export const el = (tag, cls, txt) => { const e = document.createElement(tag); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; };

/** GET a la API; {data, at}: at es cuándo se armó la respuesta (sin señal, la copia guardada es más vieja). */
export async function getJson(url) {
    const res = await fetch(url, {credentials: "same-origin"});
    const data = await res.json().catch(() => ({}));
    if (!res.ok) { const e = new Error(data.detail || res.statusText); e.status = res.status; throw e; }
    return {data, at: Number(res.headers.get("X-Saved-At")) || Date.now()};
}

/** Envía un alta (POST), un cambio (PUT) o un borrado (DELETE); devuelve la respuesta o lanza el error. */
export async function crud(method, url, body) {
    const res = await api(url, Object.assign({method}, body ? {headers: {"Content-Type": "application/json"},
        body: JSON.stringify(body)} : {}));
    const data = res.status === 204 ? {} : await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.detail || res.statusText);
    return data;
}

let toastTimer;

/** Aviso de abajo; con deshacer, muestra el botón «Deshacer». */
export function toast(txt, undo) {
    $("toast-txt").textContent = txt;
    $("toast-undo").hidden = !undo;
    $("toast-undo").onclick = undo ? () => { $("toast").hidden = true; undo(); } : null;
    $("toast").hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { $("toast").hidden = true; }, undo ? 4000 : 5000);
}

// ---------- Equipos del DT ----------

let loadedTeams = false;

/**
 * Los equipos del DT ([{id, nombre, jugados, ganados, empatados, perdidos}]). Se piden una vez y quedan en memoria para
 * los combos de todas las vistas; la sección Equipo los vuelve a pedir (forzar) porque muestra el balance y es donde
 * cambian.
 */
export async function fetchTeams(force = false) {
    if (loadedTeams && !force) return coach.teams;
    coach.teams = (await getJson("/api/coach/teams")).data;
    loadedTeams = true;
    return coach.teams;
}

/** Nombres e ids de los equipos, para saber si cambiaron. */
export const teamsSignature = () => coach.teams.map(e => e.id + ":" + e.name).join("|");

/** Llena un combo de equipos conservando el elegido; con un solo equipo, el combo se oculta (si ocultar). */
export function fillTeams(sel, hideEl = true) {
    const before = sel.value;
    sel.replaceChildren(...coach.teams.map(e => { const o = el("option", null, e.name); o.value = String(e.id); return o; }));
    if (coach.teams.some(e => String(e.id) === before)) sel.value = before;
    if (hideEl) sel.hidden = coach.teams.length < 2;
}

/** El equipo elegido en un combo (o el primero). */
export const teamOf = sel => coach.teams.find(e => String(e.id) === sel.value) || coach.teams[0];

export const teamName = p => p.team || "";

/** El plantel como lo usan los informes: clave, número, nombre y si es arquero. */
export const keyList = squad => squad.map(j => ({key: "j" + j.id, number: j.number, name: j.name, goalkeeper: j.goalkeeper}));

/** Aviso para las vistas que necesitan un equipo. */
export function withoutTeams(box) {
    const p = el("p", "coach-aviso-equipo");
    p.append("Todavía no tenés equipos. Crealos en ");
    const a = el("a", null, "Equipos");
    a.href = "#team";
    p.append(a, ", con sus jugadores.");
    box.replaceChildren(p);
}

/** Abre el PDF en otra pestaña (la demo no los genera). */
export function exportPdf(url) {
    if (coach.demo) { toast("La demo no genera PDF: con tu cuenta de RinkMetrics exportás cada informe."); return; }
    window.open(url, "_blank", "noopener");
}

/** "Primera A Masculino · Local · Apertura · 18/10/2026", como en el PDF. */
export function matchDetail(p) {
    const f = p.date ? `${p.date.slice(8, 10)}/${p.date.slice(5, 7)}/${p.date.slice(0, 4)}` : "";
    return [teamName(p), p.home ? "Local" : "Visitante", p.tournament, f].filter(Boolean).join(" · ");
}
