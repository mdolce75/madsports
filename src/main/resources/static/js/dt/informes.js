// Informes: la lista de partidos finalizados y los informes de un partido o de un período (mes, bimestre, semestre o
// campeonato), del equipo o de un jugador. Con conexión se muestra el informe del servidor (el mismo JSON del PDF); sin
// señal, el que se arma en el teléfono con las acciones guardadas (mismo cálculo, probado contra los mismos casos).
import * as Informe from "./informe.js";
import {ESTADO, claves, detallePartido, dt, el, equipoDe, exportarPdf, getJson, llenarEquipos, sinEquipos, traerEquipos}
    from "./comun.js";

let tipo = "PARTIDOS", valor = null, jugador = null;

/**
 * El informe del servidor; sin señal (el pedido no llega: TypeError), el armado en el teléfono. Un error del servidor
 * se muestra como tal.
 */
async function delServidor(url, local) {
    if (!navigator.onLine) return local();
    try {
        return (await getJson(url)).data;
    } catch (err) {
        if (err instanceof TypeError) return local();
        throw err;
    }
}

/** Sección Informes: los partidos finalizados o el informe de un período; con id, el informe de ese partido. */
export async function cargar(id) {
    const sel = $("i-equipo");
    const box = $("i-informe"), lista = $("i-lista");
    box.replaceChildren(el("p", "dt-vacio", "Cargando…"));
    try {
        await traerEquipos();
        llenarEquipos(sel);
        if (!dt.equipos.length) { lista.replaceChildren(); sinEquipos(box); return; }
        // El informe de un partido: se pide una vez, para elegir su equipo y armar el informe.
        const pedido = id ? (await getJson(`/api/dt/partidos/${id}`)).data : null;
        if (pedido && String(pedido.equipoId) !== sel.value) sel.value = String(pedido.equipoId);
        const [todos, acc, plantelEq] = await datosDelEquipo();
        const equipo = equipoDe(sel);
        if (pedido) return await dePartido(pedido, acc.filter(a => a.partidoId === pedido.id), plantelEq);
        $("i-volver").hidden = true;
        document.querySelectorAll("#i-tipo button").forEach(b => b.setAttribute("aria-pressed", String(b.dataset.tipo === tipo)));
        const fin = todos.filter(Informe.finalizado);
        if (tipo === "PARTIDOS") {
            $("i-valor").hidden = true;
            box.replaceChildren();
            lista.hidden = false;
            lista.replaceChildren();
            if (!fin.length) lista.append(el("li", "dt-vacio", "Todavía no hay partidos finalizados en este equipo. El informe de cada partido queda acá cuando termina."));
            for (const p of fin) {
                const li = el("li"), b = el("button");
                b.type = "button";
                b.append(el("span", null, `${Informe.fecha(p.fecha)} · ${p.rival}`), el("b", null, `${p.golesFavor} - ${p.golesContra}`),
                    el("small", null, [p.local ? "Local" : "Visitante", p.torneo || "Sin campeonato", ESTADO[p.estado]].join(" · ")));
                b.addEventListener("click", () => { location.hash = "informe=" + p.id; });
                li.append(b);
                lista.append(li);
            }
            return;
        }
        lista.hidden = true;
        const ops = Informe.opciones(tipo, fin);
        const v = $("i-valor");
        v.hidden = false;
        v.replaceChildren(...ops.map(o => { const op = el("option", null, `${o.nombre} (${o.n} ${o.n === 1 ? "partido" : "partidos"})`); op.value = o.valor; return op; }));
        if (!ops.length) { v.hidden = true; box.replaceChildren(el("p", "dt-vacio", "Todavía no hay partidos finalizados en este equipo.")); return; }
        if (!ops.some(o => o.valor === valor)) valor = ops[0].valor;
        v.value = valor;
        const ps = fin.filter(p => Informe.valor(tipo, p) === valor).reverse();
        const ids = new Set(ps.map(p => p.id));
        const nombre = Informe.nombre(tipo, valor);
        const local = j => () => Informe.armar({titulo: tipo === "CAMPEONATO" && valor ? "Campeonato " + nombre : nombre,
            detalle: `${equipo.nombre} · ${ps.length} ${ps.length === 1 ? "partido" : "partidos"}`, unPartido: false, partidos: ps,
            acciones: acc.filter(a => ids.has(a.partidoId)), jugadores: plantelEq, jugador: j});
        const url = j => `/api/dt/informes?equipo=${equipo.id}&periodo=${tipo}&valor=${encodeURIComponent(valor)}`
            + (j ? "&jugador=" + encodeURIComponent(j) : "");
        const delEquipo = await delServidor(url(null), local(null));
        if (jugador && !delEquipo.jugadores.some(f => f.clave === jugador)) jugador = null;
        const inf = jugador ? await delServidor(url(jugador), local(jugador)) : delEquipo;
        Informe.render(box, inf, {
            jugadores: delEquipo.jugadores,
            onJugador: k => { jugador = k; cargar(null); },
            onPartido: pid => { location.hash = "informe=" + pid; },
            onPdf: () => exportarPdf(`/api/dt/informes.pdf?equipo=${equipo.id}`
                + `&periodo=${tipo}&valor=${encodeURIComponent(valor)}` + (jugador ? "&jugador=" + encodeURIComponent(jugador) : "")),
        });
    } catch (err) {
        box.replaceChildren(el("p", "dt-vacio", "No se pudieron cargar los informes: " + err.message));
    }
}

/** Partidos del DT del equipo elegido en Informes, sus acciones y el plantel (con las claves de los jugadores). */
async function datosDelEquipo() {
    const e = equipoDe($("i-equipo"));
    const q = `equipo=${e.id}`;
    const [lista, acc, pl] = await Promise.all([getJson("/api/dt/partidos").then(r => r.data),
        getJson("/api/dt/acciones?" + q).then(r => r.data), getJson("/api/dt/plantel?" + q).then(r => r.data).catch(() => [])]);
    return [lista.filter(p => p.equipoId === e.id), acc, claves(pl)];
}

async function dePartido(p, acc, plantelEq, elegido = null) {
    $("i-lista").hidden = true;
    $("i-valor").hidden = true;
    $("i-volver").hidden = false;
    document.querySelectorAll("#i-tipo button").forEach(b => b.setAttribute("aria-pressed", "false"));
    const local = j => () => Informe.armar({titulo: `${dt.sesion.club.nombre} ${p.golesFavor} - ${p.golesContra} ${p.rival}`,
        detalle: detallePartido(p), unPartido: true, partidos: [p], acciones: acc, jugadores: plantelEq, jugador: j});
    const url = j => `/api/dt/partidos/${p.id}/informe` + (j ? "?jugador=" + encodeURIComponent(j) : "");
    const todos = await delServidor(url(null), local(null));
    const inf = elegido && todos.jugadores.some(f => f.clave === elegido) ? await delServidor(url(elegido), local(elegido)) : todos;
    Informe.render($("i-informe"), inf, {
        jugadores: todos.jugadores,
        onJugador: k => dePartido(p, acc, plantelEq, k),
        onPdf: () => exportarPdf(`/api/dt/partidos/${p.id}/informe.pdf` + (inf.jugador ? "?jugador=" + encodeURIComponent(inf.jugador.clave) : "")),
    });
}

export function iniciar() {
    $("i-equipo").addEventListener("change", () => { valor = null; jugador = null; if (location.hash !== "#informes") location.hash = "informes"; else cargar(null); });
    document.querySelectorAll("#i-tipo button").forEach(b => b.addEventListener("click", () => {
        tipo = b.dataset.tipo;
        valor = null;
        jugador = null;
        if (location.hash !== "#informes") location.hash = "informes"; else cargar(null);
    }));
    $("i-valor").addEventListener("change", () => { valor = $("i-valor").value; jugador = null; cargar(null); });
    $("i-volver-btn").addEventListener("click", () => { tipo = "PARTIDOS"; location.hash = "informes"; });
}
