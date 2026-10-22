// Informes: la lista de partidos finalizados y los informes de un partido o de un período (mes, bimestre, semestre o
// campeonato), del equipo o de un jugador. Con conexión se muestra el informe del servidor (el mismo JSON del PDF); sin
// señal, el que se arma en el teléfono con las acciones guardadas (mismo cálculo, probado contra los mismos casos).
import * as Report from "./report.js";
import {STATUS, keyList, matchDetail, coach, el, teamOf, exportPdf, getJson, fillTeams, withoutTeams, fetchTeams}
    from "./common.js";

let kind = "MATCHES", value = null, player = null;

/**
 * El informe del servidor; sin señal (el pedido no llega: TypeError), el armado en el teléfono. Un error del servidor
 * se muestra como tal.
 */
async function fromServer(url, home) {
    if (!navigator.onLine) return home();
    try {
        return (await getJson(url)).data;
    } catch (err) {
        if (err instanceof TypeError) return home();
        throw err;
    }
}

/** Sección Informes: los partidos finalizados o el informe de un período; con id, el informe de ese partido. */
export async function load(id) {
    const sel = $("i-equipo");
    const box = $("i-informe"), list = $("i-lista");
    box.replaceChildren(el("p", "coach-vacio", "Cargando…"));
    try {
        await fetchTeams();
        fillTeams(sel);
        if (!coach.teams.length) { list.replaceChildren(); withoutTeams(box); return; }
        // El informe de un partido: se pide una vez, para elegir su equipo y armar el informe.
        const request = id ? (await getJson(`/api/coach/matches/${id}`)).data : null;
        if (request && String(request.teamId) !== sel.value) sel.value = String(request.teamId);
        const [all, acc, teamSquad] = await teamData();
        const team = teamOf(sel);
        if (request) return await matchItems(request, acc.filter(a => a.matchId === request.id), teamSquad);
        $("i-volver").hidden = true;
        document.querySelectorAll("#i-tipo button").forEach(b => b.setAttribute("aria-pressed", String(b.dataset.kind === kind)));
        const endAt = all.filter(Report.finished);
        if (kind === "MATCHES") {
            $("i-valor").hidden = true;
            box.replaceChildren();
            list.hidden = false;
            list.replaceChildren();
            if (!endAt.length) list.append(el("li", "coach-vacio", "Todavía no hay partidos finalizados en este equipo. El informe de cada partido queda acá cuando termina."));
            for (const p of endAt) {
                const li = el("li"), b = el("button");
                b.type = "button";
                b.append(el("span", null, `${Report.date(p.date)} · ${p.opponent}`), el("b", null, `${p.goalsFor} - ${p.goalsAgainst}`),
                    el("small", null, [p.home ? "Local" : "Visitante", p.tournament || "Sin campeonato", STATUS[p.status]].join(" · ")));
                b.addEventListener("click", () => { location.hash = "report=" + p.id; });
                li.append(b);
                list.append(li);
            }
            return;
        }
        list.hidden = true;
        const ops = Report.options(kind, endAt);
        const v = $("i-valor");
        v.hidden = false;
        v.replaceChildren(...ops.map(o => { const op = el("option", null, `${o.name} (${o.n} ${o.n === 1 ? "partido" : "matches"})`); op.value = o.value; return op; }));
        if (!ops.length) { v.hidden = true; box.replaceChildren(el("p", "coach-vacio", "Todavía no hay partidos finalizados en este equipo.")); return; }
        if (!ops.some(o => o.value === value)) value = ops[0].value;
        v.value = value;
        const ps = endAt.filter(p => Report.value(kind, p) === value).reverse();
        const ids = new Set(ps.map(p => p.id));
        const name = Report.name(kind, value);
        const home = j => () => Report.build({title: kind === "CHAMPIONSHIP" && value ? "Campeonato " + name : name,
            detail: `${team.name} · ${ps.length} ${ps.length === 1 ? "partido" : "matches"}`, singleMatch: false, matches: ps,
            actions: acc.filter(a => ids.has(a.matchId)), players: teamSquad, player: j});
        const url = j => `/api/coach/reports?team=${team.id}&period=${kind}&value=${encodeURIComponent(value)}`
            + (j ? "&player=" + encodeURIComponent(j) : "");
        const ofTeam = await fromServer(url(null), home(null));
        if (player && !ofTeam.players.some(f => f.key === player)) player = null;
        const inf = player ? await fromServer(url(player), home(player)) : ofTeam;
        Report.render(box, inf, {
            players: ofTeam.players,
            onPlayer: k => { player = k; load(null); },
            onMatch: pid => { location.hash = "report=" + pid; },
            onPdf: () => exportPdf(`/api/coach/reports.pdf?team=${team.id}`
                + `&period=${kind}&value=${encodeURIComponent(value)}` + (player ? "&player=" + encodeURIComponent(player) : "")),
        });
    } catch (err) {
        box.replaceChildren(el("p", "coach-vacio", "No se pudieron cargar los informes: " + err.message));
    }
}

/** Partidos del DT del equipo elegido en Informes, sus acciones y el plantel (con las claves de los jugadores). */
async function teamData() {
    const e = teamOf($("i-equipo"));
    const q = `team=${e.id}`;
    const [list, acc, pl] = await Promise.all([getJson("/api/coach/matches").then(r => r.data),
        getJson("/api/coach/actions?" + q).then(r => r.data), getJson("/api/coach/squad?" + q).then(r => r.data).catch(() => [])]);
    return [list.filter(p => p.teamId === e.id), acc, keyList(pl)];
}

async function matchItems(p, acc, teamSquad, chosen = null) {
    $("i-lista").hidden = true;
    $("i-valor").hidden = true;
    $("i-volver").hidden = false;
    document.querySelectorAll("#i-tipo button").forEach(b => b.setAttribute("aria-pressed", "false"));
    const home = j => () => Report.build({title: `${coach.session.club.name} ${p.goalsFor} - ${p.goalsAgainst} ${p.opponent}`,
        detail: matchDetail(p), singleMatch: true, matches: [p], actions: acc, players: teamSquad, player: j});
    const url = j => `/api/coach/matches/${p.id}/report` + (j ? "?player=" + encodeURIComponent(j) : "");
    const all = await fromServer(url(null), home(null));
    const inf = chosen && all.players.some(f => f.key === chosen) ? await fromServer(url(chosen), home(chosen)) : all;
    Report.render($("i-informe"), inf, {
        players: all.players,
        onPlayer: k => matchItems(p, acc, teamSquad, k),
        onPdf: () => exportPdf(`/api/coach/matches/${p.id}/report.pdf` + (inf.player ? "?player=" + encodeURIComponent(inf.player.key) : "")),
    });
}

export function start() {
    $("i-equipo").addEventListener("change", () => { value = null; player = null; if (location.hash !== "#reports") location.hash = "reports"; else load(null); });
    document.querySelectorAll("#i-tipo button").forEach(b => b.addEventListener("click", () => {
        kind = b.dataset.kind;
        value = null;
        player = null;
        if (location.hash !== "#reports") location.hash = "reports"; else load(null);
    }));
    $("i-valor").addEventListener("change", () => { value = $("i-valor").value; player = null; load(null); });
    $("i-volver-btn").addEventListener("click", () => { kind = "MATCHES"; location.hash = "reports"; });
}
