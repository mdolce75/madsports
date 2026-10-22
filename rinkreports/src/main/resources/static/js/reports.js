// RinkReports: marca, barra del usuario y visor del informe (en pantalla, con exportación a PDF y a Excel).
// Va después de /js/app.js (usa $, session, renderUserBar y la marca). Las páginas llevan body data-producto="reports".

$("brand").outerHTML = marca();
// Al salir, a la portada de RinkReports.
document.body.dataset.salir = "/rinkreports";

/** En las páginas públicas: con sesión, el usuario, su acceso directo ("Mi informe") y salir. */
async function rrSessionBar() {
    const s = await session().catch(() => null);
    if (!s || !s.autenticado || s.inicio.startsWith("/login.html")) return null;
    renderUserBar(s);
    const go = document.createElement("a");
    go.className = "btn primary";
    go.href = s.inicio;
    go.textContent = s.inicio.startsWith("/rinkreports/") ? "Mi informe" : "Mi cuenta";
    $("userbar").prepend(go);
    return s;
}

/**
 * El cupo de informes del mes ("3 de 5 informes este mes · se renueva el 1/11"); vacío si no tiene límite.
 *
 * @param m informesMes de la sesión: {usados, limite, renueva}
 */
function rrCupo(m) {
    if (!m || !m.limite) return "";
    const [, mes, dia] = m.renueva.split("-").map(Number);
    return `${Math.min(m.usados, m.limite)} de ${m.limite} informes este mes · se renueva el ${dia}/${mes}`;
}

/** Ancho mínimo de la hoja del informe en pantalla (px); debajo, se achica entera. */
const RR_SHEET_WIDTH = 640;

const RR_ICON_DOWN = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 4v11M7 10l5 5 5-5M5 20h14"/></svg>`;

/**
 * Muestra el informe en pantalla, dentro de un marco (el mismo contenido que el PDF), con los botones para exportarlo.
 *
 * @param box  contenedor del visor
 * @param urls {htmlUrl, pdfUrl, xlsxUrl}
 */
async function rrShowReport(box, urls, title) {
    box.hidden = false;
    box.className = "rr-viewer";
    box.innerHTML = `<div class="bar"><h2></h2>
            <div class="export"><span>Exportar</span>
                <a class="btn primary" data-x="pdf">${RR_ICON_DOWN}PDF</a>
                <a class="btn ghost" data-x="xlsx">${RR_ICON_DOWN}Excel</a></div></div>
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
        if (disponible) {
            card.className = "plan plan-elegir";
            card.href = registro + "&plan=" + encodeURIComponent(p.codigo);
            card.setAttribute("aria-label", `Elegir el plan ${p.nombre} y crear la cuenta`);
        } else {
            card.className = "plan proximamente no-disponible";
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
        precio.querySelector("b").textContent = gratis ? "Gratis"
            : p.precioADefinir ? "Precio a definir" : "$ " + Number(p.precio).toLocaleString("es-AR");
        precio.querySelector("small").textContent = gratis || p.precioADefinir ? "" : "por mes";
        const cupo = document.createElement("span");
        cupo.className = "plan-quota";
        cupo.textContent = p.informesPorMes > 0 ? `Hasta ${p.informesPorMes} informes por mes` : "Informes sin límite";
        const nota = document.createElement("span");
        nota.className = "plan-month";
        nota.textContent = p.vigenteHasta ? "Sin costo hasta el " + p.vigenteHasta.split("-").reverse().join("/") + "."
            : "Mensual, sin permanencia.";
        card.append(nombre, precio, cupo, nota);
        if (disponible) {
            const elegir = document.createElement("span");
            elegir.className = "btn primary plan-cta";
            elegir.textContent = gratis ? "Empezar gratis" : "Elegir este plan";
            card.append(elegir);
        }
        box.append(card);
    }
    box.hidden = !planes.length;
}
