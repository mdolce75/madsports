// RinkReports: marca, barra del usuario y visor del informe (en pantalla, con exportación a PDF y a Excel).
// Va después de /js/app.js (usa $, session, renderUserBar y la marca). Las páginas llevan body data-product="reports".

$("brand").outerHTML = brand();
// Al salir, a la portada de RinkReports.
document.body.dataset.exit = "/rinkreports";

/**
 * En las páginas públicas (portada, ejemplos, "Por qué RinkReports"): son para quien todavía no tiene cuenta. Con
 * sesión de RinkReports, directo a su informe; con otra (RinkMetrics), el usuario, su acceso directo y salir.
 */
async function rrSessionBar() {
    const s = await session().catch(() => null);
    if (!s || !s.authenticated || s.home.startsWith("/rinkmetrics/login.html")) return null;
    if (s.home.startsWith("/rinkreports/")) {
        location.replace(s.home);
        return null;
    }
    renderUserBar(s);
    const go = document.createElement("a");
    go.className = "btn primary";
    go.href = s.home;
    go.textContent = s.home.startsWith("/rinkreports/") ? "Mi informe" : "Mi cuenta";
    $("userbar").prepend(go);
    return s;
}

/**
 * El cupo de informes del mes ("3 de 5 informes este mes · se renueva el 1/11"); vacío si no tiene límite.
 *
 * @param m informesMes de la sesión: {usados, limite, renueva}
 */
function rrQuota(m) {
    if (!m || !m.limit) return "";
    const [, month, day] = m.renews.split("-").map(Number);
    return `${Math.min(m.used, m.limit)} de ${m.limit} informes este mes · se renueva el ${day}/${month}`;
}

/** Fecha y hora de un pedido, en la hora argentina: "26/10/2026 14:35". */
function rrWhen(iso) {
    const d = new Date(iso);
    const p = Object.fromEntries(new Intl.DateTimeFormat("es-AR", {timeZone: "America/Argentina/Buenos_Aires",
        day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit", hour12: false})
        .formatToParts(d).map(x => [x.type, x.value]));
    return `${p.day}/${p.month}/${p.year} ${p.hour}:${p.minute}`;
}

/**
 * Los informes que generó el usuario (los últimos 50): fecha y hora, cuál fue y, mientras se conserven, para verlo de
 * nuevo o bajarlo en PDF o Excel tal como salió.
 */
async function rrHistory() {
    const box = $("historial-lista");
    if (!box) return;
    try {
        const res = await fetch("/api/history", {credentials: "same-origin"});
        if (!res.ok) throw new Error(res.statusText);
        const items = await res.json();
        if (!items.length) {
            box.innerHTML = '<p class="hint">Todavía no generaste informes.</p>';
            return;
        }
        const table = document.createElement("table");
        table.className = "rr-history-table";
        table.innerHTML = '<thead><tr><th scope="col">Fecha y hora</th><th scope="col">Informe</th>'
            + '<th scope="col"><span class="sr-only">Archivos</span></th></tr></thead><tbody></tbody>';
        for (const it of items) {
            const tr = document.createElement("tr");
            const when = document.createElement("td");
            when.textContent = rrWhen(it.createdAt);
            const what = document.createElement("td");
            what.textContent = it.detail || "Informe";
            const files = document.createElement("td");
            files.className = "files";
            if (it.available) {
                const urls = {htmlUrl: `/api/history/${it.id}/html`, pdfUrl: `/api/history/${it.id}.pdf`,
                    xlsxUrl: `/api/history/${it.id}.xlsx`};
                const view = document.createElement("button");
                view.type = "button";
                view.className = "btn ghost";
                view.textContent = "Ver";
                view.addEventListener("click", () => rrShowReport($("visor"), urls, `${it.detail || "Informe"} · ${rrWhen(it.createdAt)}`));
                const pdf = document.createElement("a");
                pdf.className = "btn ghost";
                pdf.href = urls.pdfUrl;
                pdf.textContent = "PDF";
                const xlsx = document.createElement("a");
                xlsx.className = "btn ghost";
                xlsx.href = urls.xlsxUrl;
                xlsx.textContent = "Excel";
                files.append(view, pdf, xlsx);
            } else {
                files.textContent = "No disponible";
                files.title = "Los archivos de este informe ya no se conservan.";
            }
            tr.append(when, what, files);
            table.tBodies[0].append(tr);
        }
        box.replaceChildren(table);
    } catch (e) {
        box.innerHTML = '<p class="hint">No se pudo cargar el historial.</p>';
    }
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

