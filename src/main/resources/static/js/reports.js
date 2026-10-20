// RinkReports: barra superior, pie y visor del informe (en pantalla, con exportación a PDF y a Excel).
// Va después de /js/app.js (usa $, session y renderUserBar).

const RR_LOGO = `<svg viewBox="0 0 64 64" aria-hidden="true"><rect width="64" height="64" rx="14" fill="#1f5f4f"/><rect x="13" y="11" width="38" height="42" rx="3" fill="#fff"/><g stroke="#1f5f4f" stroke-width="2.4" stroke-linecap="round"><path d="M19 21 H36 M19 29 H36 M19 37 H36"/><path d="M41 21 H45 M41 29 H45 M41 37 H45"/></g><path d="M19 45 H45" stroke="#1f5f4f" stroke-width="3.2" stroke-linecap="round"/></svg>`;

/** Barra superior y pie de RinkReports; la página marca su sección con data-rr en el body ("inicio", "ejemplos"). */
function rrChrome() {
    const top = document.createElement("header");
    top.className = "rr-top";
    top.innerHTML = `<div class="wrap">
        <a class="rr-brand" href="/rinkreports" aria-label="RinkReports: ir al inicio">${RR_LOGO}
            <span><b>RinkReports</b><small>Informes · Mad Sports</small></span></a>
        <nav class="rr-nav" aria-label="Secciones"></nav>
        <div class="userbar" id="userbar"><a class="rr-btn ghost" href="/login.html?volver=%2Frinkreports">Ingresar</a></div>
    </div>`;
    const nav = top.querySelector("nav");
    for (const [id, texto, href] of [["inicio", "Inicio", "/rinkreports"], ["ejemplos", "Ejemplos", "/rinkreports/ejemplo.html"],
                                     ["madsports", "Mad Sports", "/"]]) {
        const a = document.createElement("a");
        a.href = href;
        a.textContent = texto;
        if (document.body.dataset.rr === id) a.setAttribute("aria-current", "page");
        nav.append(a);
    }
    document.body.prepend(top);
    const foot = document.createElement("footer");
    foot.className = "rr-foot";
    foot.innerHTML = `<div class="wrap"><span>© 2026 RinkReports · Informes de hockey sobre patines</span>
        <span>Un producto de <a href="/">Mad Sports</a></span></div>`;
    document.body.append(foot);
    document.body.dataset.salir = "/rinkreports";
}

/** En las páginas públicas: con sesión, el usuario, su acceso directo ("Mi informe") y salir. */
async function rrSessionBar() {
    const s = await session().catch(() => null);
    if (!s || !s.autenticado || s.inicio.startsWith("/login.html")) return null;
    renderUserBar(s);
    const go = document.createElement("a");
    go.className = "rr-btn";
    go.href = s.inicio;
    go.textContent = s.inicio.startsWith("/rinkreports/") ? "Mi informe" : "Mi cuenta";
    $("userbar").prepend(go);
    return s;
}

/** Ancho mínimo de la hoja del informe en pantalla (px); debajo, se achica. */
const RR_SHEET_WIDTH = 640;

const RR_ICON_DOWN = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 4v11M7 10l5 5 5-5M5 20h14"/></svg>`;

/**
 * Muestra el informe en pantalla, dentro de un marco (el mismo HTML que el PDF), con los botones para exportarlo.
 *
 * @param box  contenedor del visor
 * @param urls {htmlUrl, pdfUrl, xlsxUrl}
 */
async function rrShowReport(box, urls, title) {
    box.hidden = false;
    box.className = "rr-viewer";
    box.innerHTML = `<div class="bar"><h2></h2>
            <div class="export"><span>Exportar</span>
                <a class="rr-btn" data-x="pdf">${RR_ICON_DOWN}PDF</a>
                <a class="rr-btn ghost" data-x="xlsx">${RR_ICON_DOWN}Excel</a></div></div>
        <div class="paper"><p class="loading">Cargando el informe…</p></div>`;
    box.querySelector("h2").textContent = title;
    box.querySelector('[data-x="pdf"]').href = urls.pdfUrl;
    box.querySelector('[data-x="xlsx"]').href = urls.xlsxUrl;
    const paper = box.querySelector(".paper");
    try {
        const res = await fetch(urls.htmlUrl, {credentials: "same-origin"});
        if (!res.ok) throw new Error(res.status === 404 ? "El informe expiró: generalo de nuevo." : res.statusText);
        const html = await res.text();
        const frame = document.createElement("iframe");
        frame.className = "rr-frame";
        frame.title = title;
        // Sin scripts: el informe es solo contenido. Mismo origen, para ajustar el alto a su contenido.
        frame.setAttribute("sandbox", "allow-same-origin");
        // Alto según su contenido; en pantallas angostas, la hoja se achica entera en lugar de desplazarse de costado.
        const fit = () => {
            const doc = frame.contentDocument;
            if (doc && doc.documentElement) frame.style.height = doc.documentElement.scrollHeight + "px";
            const scale = Math.min(1, paper.clientWidth / RR_SHEET_WIDTH);
            frame.style.zoom = scale < 1 ? String(scale) : "";
        };
        frame.addEventListener("load", () => {
            fit();
            // Las imágenes (escudos y gráficos) pueden terminar de cargar después.
            frame.contentDocument.querySelectorAll("img").forEach(img => img.addEventListener("load", fit));
        });
        window.addEventListener("resize", fit);
        frame.srcdoc = html;
        paper.replaceChildren(frame);
        box.scrollIntoView({behavior: "smooth", block: "start"});
    } catch (e) {
        paper.innerHTML = `<p class="loading"></p>`;
        paper.querySelector("p").textContent = "No se pudo mostrar el informe: " + e.message;
    }
}

/** Planes de un perfil ("PLAYERFPP") con su precio; el que no se puede elegir va con el cintillo "Próximamente". */
async function rrPlans(box, perfil, registro) {
    const planes = await fetch("/api/suscripciones?perfil=" + perfil).then(r => r.ok ? r.json() : []).catch(() => []);
    for (const p of planes) {
        const gratis = Number(p.precio) === 0;
        const disponible = p.disponible !== false;
        const card = document.createElement(disponible ? "a" : "div");
        card.className = "plan" + (disponible ? "" : " no-disponible");
        if (disponible) {
            card.href = registro + "&plan=" + encodeURIComponent(p.codigo);
        } else {
            card.setAttribute("aria-disabled", "true");
            const cinta = document.createElement("span");
            cinta.className = "cintillo";
            cinta.textContent = "Próximamente";
            card.append(cinta);
        }
        const nombre = document.createElement("span");
        nombre.className = "plan-name";
        nombre.textContent = "Plan " + p.nombre;
        const precio = document.createElement("span");
        precio.className = "plan-price";
        precio.innerHTML = "<b></b> <small></small>";
        precio.querySelector("b").textContent = gratis ? "Gratis" : "$ " + Number(p.precio).toLocaleString("es-AR");
        precio.querySelector("small").textContent = gratis ? "" : "por mes";
        const cupo = document.createElement("span");
        cupo.className = "plan-quota";
        cupo.textContent = `Hasta ${p.informesPorMes} informes por mes`;
        const nota = document.createElement("span");
        nota.className = "plan-month";
        nota.textContent = p.vigenteHasta ? "Sin costo hasta el " + p.vigenteHasta.split("-").reverse().join("/") + "."
            : "Mensual, sin permanencia.";
        card.append(nombre, precio, cupo, nota);
        if (disponible) {
            const elegir = document.createElement("span");
            elegir.className = "rr-btn plan-cta";
            elegir.textContent = gratis ? "Empezar gratis" : "Elegir este plan";
            card.append(elegir);
        }
        box.append(card);
    }
    box.hidden = !planes.length;
}

rrChrome();
