// Utilidades compartidas por las pantallas: sesión, llamadas a la API con CSRF y barra del usuario.
const $ = id => document.getElementById(id);

/** Token CSRF que Spring deja en la cookie XSRF-TOKEN. */
function csrfToken() {
    const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
    return m ? decodeURIComponent(m[1]) : "";
}

/** fetch con el header CSRF (los POST lo necesitan). */
function api(url, options = {}) {
    const headers = Object.assign({"X-XSRF-TOKEN": csrfToken()}, options.headers || {});
    return fetch(url, Object.assign({credentials: "same-origin"}, options, {headers}));
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
async function requireProfile(perfil) {
    const s = await session();
    if (!s.autenticado) {
        location.replace("/login.html");
        return null;
    }
    if (!s.perfiles.includes(perfil)) {
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
    who.querySelector("b").textContent = `${s.nombre} ${s.apellido}`;
    who.append(" · " + s.club.nombre);
    const out = document.createElement("button");
    out.type = "button";
    out.textContent = "Salir";
    out.addEventListener("click", async () => {
        await api("/api/logout", {method: "POST"});
        location.replace("/login.html");
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

/** Marca de la barra superior (palos cruzados y bocha). */
const BRAND = `<header class="brand">
    <svg viewBox="0 0 64 64" aria-hidden="true">
        <rect width="64" height="64" rx="14" fill="#ff5a1f"/>
        <path d="M16 14l20 30h10" stroke="#fff" stroke-width="6" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
        <path d="M48 14L28 44H18" stroke="#fff" stroke-width="6" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
        <circle cx="32" cy="52" r="5" fill="#fff"/>
    </svg>
    <div><b>Mad Sports</b><small>Hockey sobre patines · FPP</small></div>
</header>`;
