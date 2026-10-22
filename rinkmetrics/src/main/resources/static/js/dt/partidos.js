// Partidos: crear uno nuevo y la lista de los del DT (abrir o eliminar).
import {ESTADO, abm, diaMes, dt, el, equipoDe, getJson, hoy, llenarEquipos, nombreEquipo, toast, traerEquipos} from "./comun.js";

const rivalElegido = () => $("rival").value.trim();

async function crear() {
    const e = equipoDe($("equipo"));
    if (!e) { status("Primero creá un equipo en Equipo.", true); return; }
    const body = {equipo: e.id, rival: rivalElegido(), fecha: $("fecha").value || null,
        local: $("local").value === "true", torneo: $("torneo").value.trim() || null, minutos: Number($("minutos").value)};
    if (!body.rival) { status("Escribí el rival.", true); return; }
    $("crear").disabled = true;
    status("Creando el partido…");
    try {
        const p = await postJson("/api/dt/partidos", body);
        status("");
        location.hash = "partido=" + p.id;
    } catch (err) {
        status(err instanceof TypeError ? "Sin señal: para empezar un partido hace falta conexión." : err.message, true);
    } finally {
        $("crear").disabled = !rivalElegido() || !dt.equipos.length;
    }
}

export async function cargar() {
    const ul = $("mis");
    try {
        const [lista] = await Promise.all([getJson("/api/dt/partidos").then(r => r.data), traerEquipos()]);
        llenarEquipos($("equipo"), false);
        $("sin-equipos").hidden = dt.equipos.length > 0;
        $("equipo").disabled = !dt.equipos.length;
        $("crear").disabled = !rivalElegido() || !dt.equipos.length;
        // Los campeonatos y rivales ya usados, para elegirlos al crear el próximo partido.
        const opciones = valores => [...new Set(valores.filter(Boolean))].map(t => { const o = el("option"); o.value = t; return o; });
        $("torneos").replaceChildren(...opciones(lista.map(p => p.torneo)));
        $("rivales").replaceChildren(...opciones(lista.map(p => p.rival)));
        ul.replaceChildren();
        $("mis-n").textContent = lista.length ? String(lista.length) : "";
        if (!lista.length) ul.append(el("li", "dt-vacio", "Todavía no cargaste partidos."));
        for (const p of lista) {
            const li = el("li", "con-borrar");
            const b = el("button");
            b.type = "button";
            const t = el("span", null, `${diaMes(p.fecha)} · ${p.rival}`);
            const r = el("b", null, `${p.golesFavor} - ${p.golesContra}`);
            const sub = el("small", null, [nombreEquipo(p), p.local ? "Local" : "Visitante", p.torneo, ESTADO[p.estado]]
                .filter(Boolean).join(" · "));
            b.append(t, r, sub);
            b.addEventListener("click", () => { location.hash = "partido=" + p.id; });
            const del = el("button", "dt-borrar", "Eliminar");
            del.type = "button";
            del.setAttribute("aria-label", `Eliminar el partido contra ${p.rival} del ${diaMes(p.fecha)}`);
            del.addEventListener("click", () => eliminar(p));
            li.append(b, del);
            ul.append(li);
        }
    } catch (err) {
        ul.replaceChildren(el("li", "dt-vacio", "No se pudo cargar la lista: " + err.message));
    }
}

/** Elimina el partido con todas sus acciones (no se puede deshacer). */
async function eliminar(p) {
    if (!confirm(`¿Eliminar el partido contra ${p.rival} del ${diaMes(p.fecha)}? Se borran todas sus acciones y deja `
        + "de contar en las estadísticas y los informes. No se puede deshacer.")) return;
    try {
        await abm("DELETE", `/api/dt/partidos/${p.id}`);
        toast(`Partido contra ${p.rival} eliminado.`);
        cargar();
    } catch (err) {
        toast(err instanceof TypeError ? "Sin señal: para eliminar un partido hace falta conexión." : err.message);
    }
}

export function iniciar() {
    $("rival").addEventListener("input", () => { $("crear").disabled = !rivalElegido() || !dt.equipos.length; });
    $("fecha").value = hoy();
    $("crear").addEventListener("click", crear);
}
