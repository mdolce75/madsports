// Estadísticas: las del equipo (balance, métricas de todos sus partidos y rankings) y las fichas de los jugadores.
import * as PlayerCards from "./player-cards.js";
import * as Report from "./report.js";
import {keyList, el, teamOf, getJson, fillTeams, withoutTeams, fetchTeams} from "./common.js";

/** Vista Equipo: balance, las métricas de todos los partidos cargados del equipo y los rankings. */
export async function loadTeam() {
    const sel = $("e-equipo");
    $("e-kpis").replaceChildren(el("p", "coach-vacio", "Cargando…"));
    try {
        await fetchTeams();
        fillTeams(sel);
        const e = teamOf(sel);
        if (!e) {
            // Sin equipos: nada de lo que se mostraba antes queda en pantalla.
            withoutTeams($("e-kpis"));
            for (const id of ["e-balance", "e-goleadores", "e-penales", "e-libres"]) $(id).replaceChildren();
            return;
        }
        const q = `team=${e.id}`;
        const [list, acc, pl] = await Promise.all([getJson("/api/coach/matches").then(r => r.data),
            getJson("/api/coach/actions?" + q).then(r => r.data), getJson("/api/coach/squad?" + q).then(r => r.data).catch(() => [])]);
        const endAt = list.filter(p => p.teamId === e.id && Report.finished(p));
        const inf = Report.build({title: "", detail: "", singleMatch: false, matches: endAt, actions: acc,
            players: keyList(pl)});
        const b = inf.balance;
        $("e-balance").replaceChildren(...[[b.played, "Jugados"], [b.won, "Ganados"], [b.drawn, "Empatados"],
            [b.lost, "Perdidos"], [b.goalsFor, "Goles a favor"], [b.goalsAgainst, "Goles en contra"]].map(([v, n]) => {
            const k = el("div", "coach-kpi"); k.append(el("b", null, String(v)), el("span", null, n)); return k;
        }));
        $("e-kpis").replaceChildren(...Report.cards(inf.totals).map(([v, n, d, cls]) => {
            const k = el("div", "coach-kpi" + (cls ? " t-" + cls : ""));
            k.append(el("b", null, String(v)), el("span", null, n));
            if (d) k.append(el("small", null, d));
            return k;
        }));
        rankings(inf.players);
    } catch (err) {
        $("e-kpis").replaceChildren(el("p", "coach-vacio", "No se pudieron cargar las estadísticas: " + err.message));
    }
}

/**
 * Goleadores del equipo (los 5 con más goles) y los 3 mejores en penales y en libres a favor (por efectividad; a igual
 * efectividad, el que más pateó).
 */
function rankings(js) {
    const paint = (id, rows, empty) => {
        const ol = $(id);
        ol.replaceChildren();
        if (!rows.length) { ol.append(el("li", "coach-vacio", empty)); return; }
        const max = Math.max(...rows.map(f => f.barEl));
        rows.forEach((f, i) => {
            const li = el("li");
            const barEl = el("span", "barra");
            barEl.style.width = (max ? 100 * f.barEl / max : 0) + "%";
            li.append(el("span", "pos", String(i + 1)), el("span", "quien", `${f.j.number ? f.j.number + " " : ""}${f.j.name}`),
                el("b", null, f.value), el("small", null, f.detail || ""), barEl);
            ol.append(li);
        });
    };
    paint("e-goleadores", js.filter(j => j.metrics.goals > 0)
        .sort((a, b) => b.metrics.goals - a.metrics.goals || a.name.localeCompare(b.name, "es")).slice(0, 5)
        .map(j => ({j, value: String(j.metrics.goals), barEl: j.metrics.goals,
            detail: `${j.metrics.goals === 1 ? "gol" : "goles"} · ${j.matches} PJ`})),
        "Todavía no hay goles cargados.");
    const top = (goal, total, empty, id) => paint(id, js.filter(j => j.metrics[total] > 0)
        .map(j => ({j, pct: Math.round(100 * j.metrics[goal] / j.metrics[total]), a: j.metrics[goal], b: j.metrics[total]}))
        .sort((x, y) => y.pct - x.pct || y.b - x.b || x.j.name.localeCompare(y.j.name, "es")).slice(0, 3)
        .map(x => ({j: x.j, value: x.pct + "%", barEl: x.pct, detail: `${x.a}/${x.b} convertidos`})), empty);
    top("penalesGol", "penales", "Todavía no hay penales a favor cargados.", "e-penales");
    top("libresGol", "libres", "Todavía no hay libres a favor cargados.", "e-libres");
}

/** Vista Jugadores: las fichas del plantel del equipo. */
export async function loadPlayerCards() {
    const sel = $("j-equipo");
    const box = $("fichas");
    box.replaceChildren(el("p", "coach-vacio", "Cargando…"));
    try {
        await fetchTeams();
        fillTeams(sel);
        const e = teamOf(sel);
        if (!e) { withoutTeams(box); return; }
        const q = `team=${e.id}`;
        const [teamSquad, actions] = await Promise.all([getJson("/api/coach/squad?" + q).then(r => r.data).catch(() => []),
            getJson("/api/coach/actions?" + q).then(r => r.data)]);
        const playerCards = PlayerCards.compute(teamSquad, actions);
        box.replaceChildren();
        if (!playerCards.length) box.append(el("p", "coach-vacio", "Todavía no hay jugadores ni acciones cargadas en este equipo."));
        for (const f of playerCards) box.append(playerCard(f, e.name));
    } catch (err) {
        box.replaceChildren(el("p", "coach-vacio", "No se pudieron cargar las fichas: " + err.message));
    }
}

/** Ficha del jugador: encabezado con el índice, pentágono de los cinco ejes, datos clave y el detalle por eje. */
function playerCard(f, team) {
    const card = el("article", "ficha" + (f.goalkeeper ? " arquero" : "") + (f.scoreIndex == null ? " sin-datos" : ""));
    const head = el("header", "ficha-head");
    const whoName = el("div");
    whoName.append(el("b", null, f.name),
        el("small", null, `${f.number ? "#" + f.number + " · " : ""}${f.goalkeeper ? "Arquero" : team}`),
        el("small", "ficha-pj", `${f.matches} ${f.matches === 1 ? "partido" : "partidos"}`));
    const idx = el("div", "ficha-indice");
    idx.append(el("b", null, f.scoreIndex == null ? "–" : String(f.scoreIndex)), el("small", null, "Índice"));
    head.append(whoName, idx);
    const chart = el("div", "ficha-pentagono");
    if (f.scoreIndex == null) chart.append(el("p", "coach-vacio", "Sin acciones cargadas todavía."));
    else chart.innerHTML = PlayerCards.pentagon(f.axes);
    const e = Object.fromEntries(f.axes.map(x => [x.key, x]));
    const data = f.goalkeeper
        ? [[`${e.effectivenessPct.a}`, "Atajados"], [`${e.effectivenessPct.b}`, "Recibidos"],
           [`${e.setPieces.a}/${e.setPieces.a + e.setPieces.b}`, "Penal/libre"]]
        : [[`${e.attack.a}/${e.attack.b}`, "Goles/tiros"], [`${e.recovery.a} | ${e.recovery.b}`, "Rec. | Pérd."],
           [`${e.discipline.b}`, "Tarjetas"]];
    const chips = el("div", "ficha-datos");
    for (const [v, n] of data) { const c = el("div"); c.append(el("b", null, v), el("span", null, n)); chips.append(c); }
    const detail = el("details", "ficha-detalle");
    detail.append(el("summary", null, "Detalle por eje"));
    for (const x of f.axes) {
        const row = el("div", "ficha-fila");
        row.append(el("span", "t", x.name), el("span", "v", x.text),
            el("span", "d", x.labels.join(" · ")), el("span", "n", `${x.a} | ${x.b}`));
        detail.append(row);
    }
    card.append(head, chart, chips, detail);
    return card;
}
