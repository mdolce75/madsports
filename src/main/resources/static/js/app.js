// Utilidades compartidas por las pantallas: sesión, llamadas a la API con CSRF y barra del usuario.
const $ = id => document.getElementById(id);

/** Token CSRF que Spring deja en la cookie XSRF-TOKEN. */
function csrfToken() {
    const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
    return m ? decodeURIComponent(m[1]) : "";
}

/** fetch con el header CSRF (los POST lo necesitan). Si la sesión venció (401), va al ingreso. */
async function api(url, options = {}) {
    const headers = Object.assign({"X-XSRF-TOKEN": csrfToken()}, options.headers || {});
    const res = await fetch(url, Object.assign({credentials: "same-origin"}, options, {headers}));
    if (res.status === 401 && !url.startsWith("/api/login")) sesionVencida();
    return res;
}

/** Al ingreso con el aviso "Tu sesión venció"; después de ingresar vuelve a esta misma pantalla. */
function sesionVencida(aviso = true) {
    const volver = location.pathname + location.search + location.hash;
    location.replace(`/login.html?${aviso ? "vencida&" : ""}volver=${encodeURIComponent(volver)}`);
}

/** Pantalla a la que volver después de ingresar (solo una de esta web: empieza con una sola "/"). */
function volverA() {
    const v = new URLSearchParams(location.search).get("volver");
    if (!v || !/^\/(?![\/\\])/.test(v)) return null;
    // Si el servidor mandó al ingreso, el "#..." de la pantalla (por ejemplo #partido=5) quedó en esta dirección.
    return v.includes("#") ? v : v + location.hash;
}

async function postJson(url, body) {
    const res = await api(url, {method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify(body)});
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.detail || res.statusText);
    return data;
}

/** Datos del usuario logueado ({autenticado:false} si no hay sesión). También deja la cookie CSRF. */
async function session() {
    const res = await fetch("/api/sesion", {credentials: "same-origin"});
    return res.json();
}

/** Exige sesión con el perfil indicado; si no, va al login o a la pantalla de su perfil. */
/** La página pide uno de estos perfiles (p. ej. ["CLUBFPP", "CLUBAMP"]); si no lo tiene, va a su pantalla. */
async function requireProfile(perfiles) {
    const s = await session();
    if (!s.autenticado) {
        sesionVencida(false);
        return null;
    }
    if (![].concat(perfiles).some(p => s.perfiles.includes(p))) {
        location.replace(s.inicio);
        return null;
    }
    renderUserBar(s);
    return s;
}

function renderUserBar(s) {
    const bar = $("userbar");
    if (!bar) return;
    bar.innerHTML = "";
    const who = document.createElement("span");
    who.innerHTML = "<b></b>";
    who.querySelector("b").textContent = s.nombre ? `${s.nombre} ${s.apellido}` : s.usuario;
    who.append(" · " + s.club.nombre);
    const out = document.createElement("button");
    out.type = "button";
    out.textContent = "Salir";
    out.addEventListener("click", async () => {
        await api("/api/logout", {method: "POST"});
        // Métricas DT guarda copias para abrir sin señal: no quedan para el próximo usuario del teléfono.
        if (window.caches) await caches.delete("rinkmetrics-dt-v1").catch(() => {});
        location.replace("/");
    });
    bar.append(who, out);
}

const ORDER = ["PENDIENTE", "EXTRAYENDO", "ANALIZANDO", "GENERANDO_PDF", "LISTO"];

function status(text, isError) {
    $("status").className = isError ? "err" : "";
    $("status").textContent = text;
}

function showStep(estado) {
    const idx = ORDER.indexOf(estado);
    document.querySelectorAll("#steps li").forEach(li => {
        const i = ORDER.indexOf(li.dataset.s);
        li.className = i < idx ? "done" : i === idx ? "now" : "";
    });
}

/** Consulta el estado del informe hasta que esté listo. */
async function poll(url) {
    while (true) {
        const res = await fetch(url, {credentials: "same-origin"});
        if (res.status === 401) { sesionVencida(); throw new Error("Tu sesión venció."); }
        if (!res.ok) throw new Error("El informe expiró o no existe");
        const job = await res.json();
        showStep(job.estado);
        if (job.estado === "LISTO") return job;
        if (job.estado === "ERROR") throw new Error(job.mensaje);
        status(job.mensaje + "…");
        await new Promise(r => setTimeout(r, 1500));
    }
}

/** Muestra el botón de descarga y baja el PDF. */
function showReady(job) {
    $("status").className = "";
    $("status").innerHTML = `<div class="result"><b>Informe listo</b>
        <a class="btn primary" href="${job.pdfUrl}">Descargar PDF</a></div>`;
    window.location.href = job.pdfUrl;
}

/**
 * Menú de las dos secciones, al lado de la marca en todas las páginas: Informes (perfiles Club y Jugador) y DT. La
 * página marca la suya con data-seccion en el body. Se agrega cuando la página ya puso la marca.
 */
document.addEventListener("DOMContentLoaded", () => {
    const marca = document.querySelector("header.brand");
    if (!marca || document.querySelector("nav.secciones")) return;
    // El logo lleva a la portada.
    if (!marca.querySelector("a.brand-link")) {
        const link = document.createElement("a");
        link.className = "brand-link";
        link.href = "/";
        link.setAttribute("aria-label", "RinkMetrics: ir al inicio");
        link.append(...marca.childNodes);
        marca.append(link);
    }
    const nav = document.createElement("nav");
    nav.className = "secciones";
    nav.setAttribute("aria-label", "Secciones");
    for (const [id, texto, href] of [["informes", "Informes", "/informes.html"], ["dt", "DT", "/dt-inicio.html"]]) {
        const a = document.createElement("a");
        a.href = href;
        a.textContent = texto;
        if (document.body.dataset.seccion === id) a.setAttribute("aria-current", "page");
        nav.append(a);
    }
    marca.after(nav);
});

/** Marca de la barra superior (pista con barras de métricas). */
const BRAND = `<header class="brand">
    <svg viewBox="0 0 64 64" aria-hidden="true"><rect width="64" height="64" rx="14" fill="#22b8f0"/><rect x="8" y="15" width="48" height="34" rx="14" fill="none" stroke="#fff" stroke-width="4"/><rect x="20" y="33" width="6" height="9" rx="1.5" fill="#fff"/><rect x="29" y="27" width="6" height="15" rx="1.5" fill="#fff"/><rect x="38" y="21" width="6" height="21" rx="1.5" fill="#fff"/></svg>
    <div><b>RinkMetrics</b><small>Hockey sobre patines</small></div>
</header>`;
