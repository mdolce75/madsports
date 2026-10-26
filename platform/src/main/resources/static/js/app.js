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
    if (res.status === 401 && !url.startsWith("/api/login")) sessionExpired();
    return res;
}

/** Al ingreso con el aviso "Tu sesión venció"; después de ingresar vuelve a esta misma pantalla. */
function sessionExpired(notice = true) {
    const returnTo = location.pathname + location.search + location.hash;
    location.replace(`${login()}?${notice ? "vencida&" : ""}volver=${encodeURIComponent(returnTo)}`);
}

/**
 * Producto de la pantalla: "REPORTS" en las de RinkReports (body data-product="reports", o las compartidas con
 * ?producto=reports); si no, "METRICS".
 */
function product() {
    return document.body.dataset.product === "reports" || location.pathname.startsWith("/rinkreports/")
        ? "REPORTS" : "METRICS";
}

/** Ingreso del producto de la pantalla: RinkReports tiene el suyo; el resto, el de RinkMetrics. */
function login() {
    return product() === "REPORTS" ? "/rinkreports/login.html" : "/rinkmetrics/login.html";
}

/** Pantalla a la que volver después de ingresar (solo una de esta web: empieza con una sola "/"). */
function returnTarget() {
    const v = new URLSearchParams(location.search).get("returnTo");
    if (!v || !/^\/(?![\/\\])/.test(v)) return null;
    // Si el servidor mandó al ingreso, el "#..." de la pantalla (por ejemplo #match=5) quedó en esta dirección.
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
    // La sesión de este producto: la de RinkReports no cuenta en RinkMetrics, ni al revés.
    const res = await fetch("/api/session?product=" + product(), {credentials: "same-origin"});
    return res.json();
}

/** Exige sesión con el perfil indicado; si no, va al login o a la pantalla de su perfil. */
/** La página pide uno de estos perfiles (p. ej. ["CLUBFPP", "CLUBAMP"]); si no lo tiene, va a su pantalla. */
async function requireProfile(profiles) {
    const s = await session();
    if (!s.authenticated) {
        sessionExpired(false);
        return null;
    }
    if (![].concat(profiles).some(p => s.profiles.includes(p))) {
        location.replace(s.home);
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
    who.querySelector("b").textContent = s.name ? `${s.name} ${s.lastName}` : s.username;
    who.append(" · " + s.club.name);
    const out = document.createElement("button");
    out.type = "button";
    out.textContent = "Salir";
    out.addEventListener("click", async () => {
        await api("/api/logout", {method: "POST"});
        // RinkMetrics guarda copias para abrir sin señal: no quedan para el próximo usuario del teléfono.
        if (window.caches) for (const c of ["rinkmetrics-coach-v1", "rinkmetrics-dt-v1"]) await caches.delete(c).catch(() => {});
        // A la portada del producto (RinkReports la indica en data-salir) o a la home.
        location.replace(document.body.dataset.exit || "/");
    });
    bar.append(who, out);
}

const ORDER = ["PENDING", "EXTRACTING", "ANALYZING", "RENDERING", "READY"];

function status(text, isError) {
    $("status").className = isError ? "err" : "";
    $("status").textContent = text;
}

function showStep(status) {
    const idx = ORDER.indexOf(status);
    document.querySelectorAll("#steps li").forEach(li => {
        const i = ORDER.indexOf(li.dataset.s);
        li.className = i < idx ? "done" : i === idx ? "now" : "";
    });
}

/** Consulta el estado del informe hasta que esté listo. */
async function poll(url) {
    while (true) {
        const res = await fetch(url, {credentials: "same-origin"});
        if (res.status === 401) { sessionExpired(); throw new Error("Tu sesión venció."); }
        if (!res.ok) throw new Error("El informe expiró o no existe");
        const job = await res.json();
        showStep(job.status);
        if (job.status === "READY") return job;
        if (job.status === "ERROR") throw new Error(job.message);
        status(job.message + "…");
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
 * Menú superior, al lado de la marca, con las secciones del producto de la página: RinkMetrics (inicio, la demo y
 * "Por qué RinkMetrics") o RinkReports (body data-product="reports": inicio, ejemplos y "Por qué RinkReports"). La página marca la suya con
 * data-section en el body. Se agrega cuando la página ya puso la marca. Las pantallas de RinkMetrics (body.coach-page) no
 * lo llevan: tienen su propio menú en esa barra. Todas llevan el enlace de vuelta a la home de Mad Sports.
 */
const SECTIONS = {
    metrics: {home: "/rinkmetrics", name: "RinkMetrics", items: [["home", "Inicio", "/rinkmetrics"],
        ["demo", "Demo", "/coach-demo.html"], ["why", "Por qué RinkMetrics", "/rinkmetrics/why.html"]]},
    reports: {home: "/rinkreports", name: "RinkReports", items: [["home", "Inicio", "/rinkreports"],
        ["samples", "Ejemplos", "/rinkreports/sample.html"],
        ["why", "Por qué RinkReports", "/rinkreports/why.html"]]}
};

document.addEventListener("DOMContentLoaded", () => {
    const brand = document.querySelector("header.brand");
    if (!brand || document.querySelector("nav.secciones")) return;
    const product = SECTIONS[document.body.dataset.product] || SECTIONS.metrics;
    // El logo lleva a la portada del producto.
    if (!brand.querySelector("a.brand-link")) {
        const link = document.createElement("a");
        link.className = "brand-link";
        link.href = product.home;
        link.setAttribute("aria-label", product.name + ": ir al inicio");
        link.append(...brand.childNodes);
        brand.append(link);
    }
    // Volver a Mad Sports, la home de la compañía: antes de la barra del usuario o, sin ella, después del menú.
    // /madsports y no "/": con un dominio por sitio, la raíz de rinkreports.com.ar es la portada de RinkReports.
    const home = document.createElement("a");
    home.className = "to-madsports";
    home.href = "/madsports";
    home.textContent = "← Mad Sports";
    if (document.body.classList.contains("coach-page")) {
        $("userbar").before(home);
        return;
    }
    const nav = document.createElement("nav");
    nav.className = "secciones";
    nav.setAttribute("aria-label", "Secciones");
    // Con sesión, en la pantalla del club o del jugador: solo lo suyo (su informe y los que generó); los ejemplos y
    // "Por qué RinkReports" son para quien todavía no tiene cuenta.
    const own = ["club", "player"].includes(document.body.dataset.section)
        ? [[document.body.dataset.section, "Mi informe", location.pathname], ["history", "Informes generados", "#historial"]]
        : product.items;
    for (const [id, text, href] of own) {
        const a = document.createElement("a");
        a.href = href;
        a.textContent = text;
        if (document.body.dataset.section === id) a.setAttribute("aria-current", "page");
        nav.append(a);
    }
    brand.after(nav);
    const bar = $("userbar");
    if (bar) bar.before(home);
    else nav.after(home);
});

/** Marca de la barra superior (pista con barras de métricas). */
const BRAND = `<header class="brand">
    <svg viewBox="0 0 64 64" aria-hidden="true"><rect width="64" height="64" rx="14" fill="#22b8f0"/><rect x="8" y="15" width="48" height="34" rx="14" fill="none" stroke="#fff" stroke-width="4"/><rect x="20" y="33" width="6" height="9" rx="1.5" fill="#fff"/><rect x="29" y="27" width="6" height="15" rx="1.5" fill="#fff"/><rect x="38" y="21" width="6" height="21" rx="1.5" fill="#fff"/></svg>
    <div><b>RinkMetrics</b><small>Hockey sobre patines</small></div>
</header>`;

/** Marca de RinkReports: el libro de informes, con renglones y una columna de totales. */
const RR_BRAND = `<header class="brand">
    <svg viewBox="0 0 64 64" aria-hidden="true"><rect width="64" height="64" rx="14" fill="#2fd3a0"/><rect x="13" y="11" width="38" height="42" rx="3" fill="#fff"/><g stroke="#08130f" stroke-width="2.4" stroke-linecap="round"><path d="M19 21 H36 M19 29 H36 M19 37 H36"/><path d="M41 21 H45 M41 29 H45 M41 37 H45"/></g><path d="M19 45 H45" stroke="#08130f" stroke-width="3.2" stroke-linecap="round"/></svg>
    <div><b>RinkReports</b><small>Informes de hockey sobre patines</small></div>
</header>`;

/** La marca del producto de la pantalla. */
function brand() {
    return product() === "REPORTS" ? RR_BRAND : BRAND;
}
