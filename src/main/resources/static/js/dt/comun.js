// Lo que comparten las vistas de Métricas DT: la sesión, los equipos del DT, los pedidos a la API, el aviso de abajo
// (toast) y utilidades de fechas y de elementos. Usa $ y api de app.js (script clásico, cargado antes).

/** Estado compartido: la sesión del DT, sus equipos y si es la demo. */
export const dt = {sesion: null, equipos: [], demo: false};

export const ESTADO = {PENDIENTE: "Sin iniciar", EN_JUEGO: "En juego", ENTRETIEMPO: "Entretiempo", TERMINADO: "Terminado",
    CERRADO: "Cerrado"};

/** Fecha de hoy en el teléfono (AAAA-MM-DD, hora local). */
export const hoy = () => { const d = new Date(); return new Date(d - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10); };
export const mmss = s => String(Math.floor(s / 60)).padStart(2, "0") + ":" + String(s % 60).padStart(2, "0");
export const diaMes = f => f ? f.slice(8, 10) + "/" + f.slice(5, 7) : "";
export const corto = n => { const w = (n || "").trim().split(/\s+/); return w[w.length - 1] || n; };
export const el = (tag, cls, txt) => { const e = document.createElement(tag); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; };

/** GET a la API; {data, at}: at es cuándo se armó la respuesta (sin señal, la copia guardada es más vieja). */
export async function getJson(url) {
    const res = await fetch(url, {credentials: "same-origin"});
    const data = await res.json().catch(() => ({}));
    if (!res.ok) { const e = new Error(data.detail || res.statusText); e.status = res.status; throw e; }
    return {data, at: Number(res.headers.get("X-Guardado")) || Date.now()};
}

/** Envía un alta (POST), un cambio (PUT) o un borrado (DELETE); devuelve la respuesta o lanza el error. */
export async function abm(method, url, body) {
    const res = await api(url, Object.assign({method}, body ? {headers: {"Content-Type": "application/json"},
        body: JSON.stringify(body)} : {}));
    const data = res.status === 204 ? {} : await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.detail || res.statusText);
    return data;
}

let toastTimer;

/** Aviso de abajo; con deshacer, muestra el botón «Deshacer». */
export function toast(txt, deshacer) {
    $("toast-txt").textContent = txt;
    $("toast-undo").hidden = !deshacer;
    $("toast-undo").onclick = deshacer ? () => { $("toast").hidden = true; deshacer(); } : null;
    $("toast").hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { $("toast").hidden = true; }, deshacer ? 4000 : 5000);
}

// ---------- Equipos del DT ----------

let equiposCargados = false;

/**
 * Los equipos del DT ([{id, nombre, jugados, ganados, empatados, perdidos}]). Se piden una vez y quedan en memoria para
 * los combos de todas las vistas; la sección Equipo los vuelve a pedir (forzar) porque muestra el balance y es donde
 * cambian.
 */
export async function traerEquipos(forzar = false) {
    if (equiposCargados && !forzar) return dt.equipos;
    dt.equipos = (await getJson("/api/dt/equipos")).data;
    equiposCargados = true;
    return dt.equipos;
}

/** Nombres e ids de los equipos, para saber si cambiaron. */
export const firmaEquipos = () => dt.equipos.map(e => e.id + ":" + e.nombre).join("|");

/** Llena un combo de equipos conservando el elegido; con un solo equipo, el combo se oculta (si ocultar). */
export function llenarEquipos(sel, ocultar = true) {
    const antes = sel.value;
    sel.replaceChildren(...dt.equipos.map(e => { const o = el("option", null, e.nombre); o.value = String(e.id); return o; }));
    if (dt.equipos.some(e => String(e.id) === antes)) sel.value = antes;
    if (ocultar) sel.hidden = dt.equipos.length < 2;
}

/** El equipo elegido en un combo (o el primero). */
export const equipoDe = sel => dt.equipos.find(e => String(e.id) === sel.value) || dt.equipos[0];

export const nombreEquipo = p => p.equipo || "";

/** El plantel como lo usan los informes: clave, número, nombre y si es arquero. */
export const claves = plantel => plantel.map(j => ({key: "j" + j.id, numero: j.numero, nombre: j.nombre, arquero: j.arquero}));

/** Aviso para las vistas que necesitan un equipo. */
export function sinEquipos(box) {
    const p = el("p", "dt-aviso-equipo");
    p.append("Todavía no tenés equipos. Crealos en ");
    const a = el("a", null, "Equipo");
    a.href = "#equipo";
    p.append(a, ", con sus jugadores.");
    box.replaceChildren(p);
}

/** Abre el PDF en otra pestaña (la demo no los genera). */
export function exportarPdf(url) {
    if (dt.demo) { toast("La demo no genera PDF: con tu cuenta de Métricas DT exportás cada informe."); return; }
    window.open(url, "_blank", "noopener");
}

/** "Primera A Masculino · Local · Apertura · 18/10/2026", como en el PDF. */
export function detallePartido(p) {
    const f = p.fecha ? `${p.fecha.slice(8, 10)}/${p.fecha.slice(5, 7)}/${p.fecha.slice(0, 4)}` : "";
    return [nombreEquipo(p), p.local ? "Local" : "Visitante", p.torneo, f].filter(Boolean).join(" · ");
}
