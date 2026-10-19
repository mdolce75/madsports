// Estadísticas: las del equipo (balance, métricas de todos sus partidos y rankings) y las fichas de los jugadores.
import * as Fichas from "./fichas.js";
import * as Informe from "./informe.js";
import {claves, el, equipoDe, getJson, llenarEquipos, sinEquipos, traerEquipos} from "./comun.js";

/** Vista Equipo: balance, las métricas de todos los partidos cargados del equipo y los rankings. */
export async function cargarEquipo() {
    const sel = $("e-equipo");
    $("e-kpis").replaceChildren(el("p", "dt-vacio", "Cargando…"));
    try {
        await traerEquipos();
        llenarEquipos(sel);
        const e = equipoDe(sel);
        if (!e) {
            // Sin equipos: nada de lo que se mostraba antes queda en pantalla.
            sinEquipos($("e-kpis"));
            for (const id of ["e-balance", "e-goleadores", "e-penales", "e-libres"]) $(id).replaceChildren();
            return;
        }
        const q = `equipo=${e.id}`;
        const [lista, acc, pl] = await Promise.all([getJson("/api/dt/partidos").then(r => r.data),
            getJson("/api/dt/acciones?" + q).then(r => r.data), getJson("/api/dt/plantel?" + q).then(r => r.data).catch(() => [])]);
        const fin = lista.filter(p => p.equipoId === e.id && Informe.finalizado(p));
        const inf = Informe.armar({titulo: "", detalle: "", unPartido: false, partidos: fin, acciones: acc,
            jugadores: claves(pl)});
        const b = inf.balance;
        $("e-balance").replaceChildren(...[[b.jugados, "Jugados"], [b.ganados, "Ganados"], [b.empatados, "Empatados"],
            [b.perdidos, "Perdidos"], [b.golesFavor, "Goles a favor"], [b.golesContra, "Goles en contra"]].map(([v, n]) => {
            const k = el("div", "dt-kpi"); k.append(el("b", null, String(v)), el("span", null, n)); return k;
        }));
        $("e-kpis").replaceChildren(...Informe.tarjetas(inf.totales).map(([v, n, d, cls]) => {
            const k = el("div", "dt-kpi" + (cls ? " t-" + cls : ""));
            k.append(el("b", null, String(v)), el("span", null, n));
            if (d) k.append(el("small", null, d));
            return k;
        }));
        rankings(inf.jugadores);
    } catch (err) {
        $("e-kpis").replaceChildren(el("p", "dt-vacio", "No se pudieron cargar las estadísticas: " + err.message));
    }
}

/**
 * Goleadores del equipo (los 5 con más goles) y los 3 mejores en penales y en libres a favor (por efectividad; a igual
 * efectividad, el que más pateó).
 */
function rankings(js) {
    const pintar = (id, filas, vacio) => {
        const ol = $(id);
        ol.replaceChildren();
        if (!filas.length) { ol.append(el("li", "dt-vacio", vacio)); return; }
        const max = Math.max(...filas.map(f => f.barra));
        filas.forEach((f, i) => {
            const li = el("li");
            const barra = el("span", "barra");
            barra.style.width = (max ? 100 * f.barra / max : 0) + "%";
            li.append(el("span", "pos", String(i + 1)), el("span", "quien", `${f.j.numero ? f.j.numero + " " : ""}${f.j.nombre}`),
                el("b", null, f.valor), el("small", null, f.detalle || ""), barra);
            ol.append(li);
        });
    };
    pintar("e-goleadores", js.filter(j => j.metricas.goles > 0)
        .sort((a, b) => b.metricas.goles - a.metricas.goles || a.nombre.localeCompare(b.nombre, "es")).slice(0, 5)
        .map(j => ({j, valor: String(j.metricas.goles), barra: j.metricas.goles,
            detalle: `${j.metricas.goles === 1 ? "gol" : "goles"} · ${j.partidos} PJ`})),
        "Todavía no hay goles cargados.");
    const top = (gol, total, vacio, id) => pintar(id, js.filter(j => j.metricas[total] > 0)
        .map(j => ({j, pct: Math.round(100 * j.metricas[gol] / j.metricas[total]), a: j.metricas[gol], b: j.metricas[total]}))
        .sort((x, y) => y.pct - x.pct || y.b - x.b || x.j.nombre.localeCompare(y.j.nombre, "es")).slice(0, 3)
        .map(x => ({j: x.j, valor: x.pct + "%", barra: x.pct, detalle: `${x.a}/${x.b} convertidos`})), vacio);
    top("penalesGol", "penales", "Todavía no hay penales a favor cargados.", "e-penales");
    top("libresGol", "libres", "Todavía no hay libres a favor cargados.", "e-libres");
}

/** Vista Jugadores: las fichas del plantel del equipo. */
export async function cargarFichas() {
    const sel = $("j-equipo");
    const box = $("fichas");
    box.replaceChildren(el("p", "dt-vacio", "Cargando…"));
    try {
        await traerEquipos();
        llenarEquipos(sel);
        const e = equipoDe(sel);
        if (!e) { sinEquipos(box); return; }
        const q = `equipo=${e.id}`;
        const [plantelEq, acciones] = await Promise.all([getJson("/api/dt/plantel?" + q).then(r => r.data).catch(() => []),
            getJson("/api/dt/acciones?" + q).then(r => r.data)]);
        const fichas = Fichas.calcular(plantelEq, acciones);
        box.replaceChildren();
        if (!fichas.length) box.append(el("p", "dt-vacio", "Todavía no hay jugadores ni acciones cargadas en este equipo."));
        for (const f of fichas) box.append(ficha(f, e.nombre));
    } catch (err) {
        box.replaceChildren(el("p", "dt-vacio", "No se pudieron cargar las fichas: " + err.message));
    }
}

/** Ficha del jugador: encabezado con el índice, pentágono de los cinco ejes, datos clave y el detalle por eje. */
function ficha(f, equipo) {
    const card = el("article", "ficha" + (f.arquero ? " arquero" : "") + (f.indice == null ? " sin-datos" : ""));
    const head = el("header", "ficha-head");
    const quien = el("div");
    quien.append(el("b", null, f.nombre),
        el("small", null, `${f.numero ? "#" + f.numero + " · " : ""}${f.arquero ? "Arquero" : equipo}`),
        el("small", "ficha-pj", `${f.partidos} ${f.partidos === 1 ? "partido" : "partidos"}`));
    const idx = el("div", "ficha-indice");
    idx.append(el("b", null, f.indice == null ? "–" : String(f.indice)), el("small", null, "Índice"));
    head.append(quien, idx);
    const graf = el("div", "ficha-pentagono");
    if (f.indice == null) graf.append(el("p", "dt-vacio", "Sin acciones cargadas todavía."));
    else graf.innerHTML = Fichas.pentagono(f.ejes);
    const e = Object.fromEntries(f.ejes.map(x => [x.clave, x]));
    const datos = f.arquero
        ? [[`${e.efectividad.a}`, "Atajados"], [`${e.efectividad.b}`, "Recibidos"],
           [`${e.pelotaParada.a}/${e.pelotaParada.a + e.pelotaParada.b}`, "Penal/libre"]]
        : [[`${e.ataque.a}/${e.ataque.b}`, "Goles/tiros"], [`${e.recuperacion.a} | ${e.recuperacion.b}`, "Rec. | Pérd."],
           [`${e.disciplina.b}`, "Tarjetas"]];
    const chips = el("div", "ficha-datos");
    for (const [v, n] of datos) { const c = el("div"); c.append(el("b", null, v), el("span", null, n)); chips.append(c); }
    const detalle = el("details", "ficha-detalle");
    detalle.append(el("summary", null, "Detalle por eje"));
    for (const x of f.ejes) {
        const fila = el("div", "ficha-fila");
        fila.append(el("span", "t", x.nombre), el("span", "v", x.texto),
            el("span", "d", x.etiquetas.join(" · ")), el("span", "n", `${x.a} | ${x.b}`));
        detalle.append(fila);
    }
    card.append(head, graf, chips, detalle);
    return card;
}
