// Informes de RinkMetrics: el de un partido (con sus eventos) y el de los partidos finalizados de un mes, un bimestre,
// un semestre o un campeonato; del equipo o de un jugador. Se arma con las acciones (también las que todavía no se
// enviaron) para verlo en vivo y sin señal. Las métricas y el armado son los mismos que los del servidor
// (CoachReport.java): los dos se prueban contra los mismos casos (el contrato de la API, src/test/resources/coach/api-contract.json).
import {GOAL_ACTIONS, action, playerKey, isGoalFor, isGoalAgainst} from "./actions.js";

const MONTHS = ["enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre",
    "noviembre", "diciembre"];
const finished = p => p.status === "FINISHED" || p.status === "CLOSED";
const mmss = s => String(Math.floor(s / 60)).padStart(2, "0") + ":" + String(s % 60).padStart(2, "0");
const date = f => f ? `${f.slice(8, 10)}/${f.slice(5, 7)}/${f.slice(0, 4)}` : "";
const pct = (a, b) => b ? Math.round(100 * a / b) : null;

/** Las métricas de un grupo de acciones (las mismas claves que el JSON del servidor). */
function metrics(acc) {
    const n = (kind, res) => acc.filter(a => a.kind === kind && (res === undefined || a.outcome === res)).length;
    const m = {
        goals: acc.filter(isGoalFor).length, goalsConceded: acc.filter(isGoalAgainst).length,
        shots: n("SHOT"), shotGoals: n("SHOT", "GOAL"),
        saves: n("SAVE") + n("FREE_HIT_AGAINST", "SAVED") + n("PENALTY_AGAINST", "SAVED"),
        turnoversDefense: n("TURNOVER", "OWN_HALF"), turnoversAttack: n("TURNOVER", "RIVAL_HALF"),
        recoveriesDefense: n("RECOVERY", "OWN_HALF"), recoveriesAttack: n("RECOVERY", "RIVAL_HALF"),
        foulsReceived: n("FOUL", "RECEIVED"), foulsCommitted: n("FOUL", "COMMITTED"),
        blues: n("CARD", "BLUE"), yellows: n("CARD", "YELLOW"), reds: n("CARD", "RED"),
        penalties: n("PENALTY_FOR"), penaltyGoals: n("PENALTY_FOR", "GOAL"),
        freeHits: n("FREE_HIT_FOR"), freeHitGoals: n("FREE_HIT_FOR", "GOAL"),
    };
    m.turnovers = m.turnoversDefense + m.turnoversAttack;
    m.recoveries = m.recoveriesDefense + m.recoveriesAttack;
    m.shotEffectiveness = pct(m.shotGoals, m.shots);
    m.penaltyEffectiveness = pct(m.penaltyGoals, m.penalties);
    m.freeHitEffectiveness = pct(m.freeHitGoals, m.freeHits);
    return m;
}

/** Resultado del partido para el DT: G, E o P. */
const outcome = p => p.goalsFor > p.goalsAgainst ? "G" : p.goalsFor === p.goalsAgainst ? "E" : "P";

/**
 * Arma el informe. partidos: los del informe (uno en el de un partido), con golesFavor y golesContra; acciones: las
 * activas, con partidoId; jugadores: [{key, numero, nombre, arquero}] para el número y los arqueros; jugador: la
 * clave del elegido, o null.
 */
function build({title, detail, singleMatch, matches, actions, players = [], player = null}) {
    const byPlayer = new Map();
    for (const a of actions) {
        if (!byPlayer.has(playerKey(a))) byPlayer.set(playerKey(a), []);
        byPlayer.get(playerKey(a)).push(a);
    }
    const known = new Map(players.map(j => [j.key, j]));
    let rows = [...byPlayer].map(([key, acc]) => {
        const j = known.get(key);
        return {key: key, number: j && j.number || "", name: (j && j.name) || acc[0].playerName || "",
            goalkeeper: j ? !!j.goalkeeper : acc.some(a => GOAL_ACTIONS.has(a.kind)),
            matches: new Set(acc.map(a => a.matchId)).size, metrics: metrics(acc)};
    });
    const num = n => parseInt(n, 10) || 999;
    rows.sort((a, b) => num(a.number) - num(b.number) || a.name.localeCompare(b.name, "es", {sensitivity: "base"}));
    const chosen = player ? rows.find(f => f.key === player) || null : null;
    const thoseOf = chosen ? byPlayer.get(chosen.key) : actions;
    if (chosen) rows = [chosen];
    const nums = new Map(rows.map(f => [f.key, f.number]));
    const names = new Map(rows.map(f => [f.key, f.name]));
    const dur = (matches[0] && matches[0].periodMinutes || 25) * 60;
    const events = !singleMatch ? [] : thoseOf.slice()
        .sort((a, b) => a.period - b.period || a.atSecond - b.atSecond)
        .map(a => {
            const n = action(a.kind, a.outcome);
            return {period: a.period, atSecond: a.atSecond, minute: `${a.period}T ${mmss(Math.min(a.atSecond, dur))}`,
                key: playerKey(a), number: nums.get(playerKey(a)) || "", player: names.get(playerKey(a)) || a.playerName, action: n.name,
                color: n.col, pendingItem: !!a.pendingItem};
        });
    const ps = matches.map(p => ({id: p.id, date: p.date, opponent: p.opponent, home: p.home, tournament: p.tournament,
        goalsFor: p.goalsFor, goalsAgainst: p.goalsAgainst, outcome: outcome(p)}));
    const balance = {played: ps.length, won: ps.filter(p => p.outcome === "G").length,
        drawn: ps.filter(p => p.outcome === "E").length, lost: ps.filter(p => p.outcome === "P").length,
        goalsFor: ps.reduce((s, p) => s + p.goalsFor, 0), goalsAgainst: ps.reduce((s, p) => s + p.goalsAgainst, 0)};
    return {title, detail, singleMatch, matches: ps, balance, totals: metrics(thoseOf), players: rows,
        player: chosen, events};
}

// ---------- Períodos ----------

const TYPES = {MONTH: "Mes", TWO_MONTHS: "Bimestre", SEMESTER: "Semestre", CHAMPIONSHIP: "Campeonato"};

/** Valor del período en el que cae un partido (igual que Periodo.java). */
function value(kind, p) {
    const y = p.date.slice(0, 4), m = Number(p.date.slice(5, 7));
    if (kind === "MONTH") return p.date.slice(0, 7);
    if (kind === "TWO_MONTHS") return `${y}-${Math.floor((m + 1) / 2)}`;
    if (kind === "SEMESTER") return `${y}-${m <= 6 ? 1 : 2}`;
    return p.tournament || "";
}

function name(kind, v) {
    if (kind === "CHAMPIONSHIP") return v ? v : "Sin campeonato";
    const [y, n] = v.split("-").map(Number);
    if (kind === "MONTH") return MONTHS[n - 1].replace(/^./, c => c.toUpperCase()) + " " + y;
    if (kind === "TWO_MONTHS") return `Bimestre ${MONTHS[2 * n - 2]}-${MONTHS[2 * n - 1]} ${y}`;
    return `${n}.º semestre ${y} (${n === 1 ? "enero a junio" : "julio a diciembre"})`;
}

/** Los valores del tipo en los que hay partidos finalizados, del más reciente al más viejo: [{valor, nombre, n}]. */
function options(kind, matches) {
    const account = new Map(), last = new Map();
    for (const p of matches.filter(finished)) {
        const v = value(kind, p);
        account.set(v, (account.get(v) || 0) + 1);
        if (!last.has(v) || last.get(v) < p.date) last.set(v, p.date);
    }
    return [...account].map(([v, n]) => ({value: v, name: name(kind, v), n}))
        .sort((a, b) => kind === "CHAMPIONSHIP" ? last.get(b.value).localeCompare(last.get(a.value))
            : b.value.localeCompare(a.value, undefined, {numeric: true}));
}

// ---------- Pantalla ----------

const el = (tag, cls, txt) => { const e = document.createElement(tag); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; };
const ef = (v, a, b) => v == null ? ["–", "sin intentos"] : [v + "%", `${a}/${b}`];

/** Las 14 métricas del informe, como tarjetas: [valor, nombre, detalle, clase]. */
function cards(m) {
    const [et, etd] = ef(m.shotEffectiveness, m.shotGoals, m.shots);
    const [ep, epd] = ef(m.penaltyEffectiveness, m.penaltyGoals, m.penalties);
    const [el2, eld] = ef(m.freeHitEffectiveness, m.freeHitGoals, m.freeHits);
    return [
        [m.goals, "Goles"], [m.goalsConceded, "Goles recibidos"], [m.shots, "Tiros"],
        [et, "Efectividad tiros", etd], [m.saves, "Atajadas"],
        [m.turnovers, "Pérdidas", `${m.turnoversDefense} def. · ${m.turnoversAttack} ataq.`],
        [m.recoveries, "Recuperaciones", `${m.recoveriesDefense} def. · ${m.recoveriesAttack} ataq.`],
        [m.foulsReceived, "Faltas recibidas"], [m.foulsCommitted, "Faltas cometidas"],
        [m.blues, "Tarjeta azul", null, "azul"], [m.yellows, "Tarjeta amarilla", null, "amarilla"],
        [m.reds, "Tarjeta roja", null, "roja"],
        [ep, "Efectividad penales", epd], [el2, "Efectividad libres", eld],
    ];
}

/**
 * Muestra el informe en box. opts: onPlayer(clave o null) al elegir un jugador, onPdf() para exportar, jugadores
 * para el selector (todos los del informe del equipo) y vacio (texto sin acciones).
 */
function render(box, inf, opts = {}) {
    box.replaceChildren();
    const head = el("div", "inf-head");
    const t = el("div", "inf-titulo");
    t.append(el("h3", null, inf.title), el("small", null, inf.detail));
    const actions = el("div", "inf-acciones");
    const sel = el("select");
    sel.setAttribute("aria-label", "Jugador del informe");
    const all = el("option", null, "Todo el equipo");
    all.value = "";
    sel.append(all);
    for (const j of opts.players || inf.players) {
        const o = el("option", null, `${j.number ? j.number + " · " : ""}${j.name}`);
        o.value = j.key;
        sel.append(o);
    }
    sel.value = inf.player ? inf.player.key : "";
    sel.addEventListener("change", () => opts.onPlayer && opts.onPlayer(sel.value || null));
    actions.append(sel);
    if (opts.onPdf) {
        const b = el("button", "btn primary inf-pdf", "Exportar PDF");
        b.type = "button";
        b.addEventListener("click", opts.onPdf);
        actions.append(b);
    }
    head.append(t, actions);
    box.append(head);

    const m = inf.totals;
    box.append(el("h4", "inf-sub", inf.player ? `Estadísticas de ${inf.player.name}` : "Estadísticas del equipo"));
    const kp = el("div", "inf-kpis");
    for (const [v, n, d, cls] of cards(m)) {
        const k = el("div", "coach-kpi" + (cls ? " t-" + cls : ""));
        k.append(el("b", null, String(v)), el("span", null, n));
        if (d) k.append(el("small", null, d));
        kp.append(k);
    }
    box.append(kp);

    if (!inf.singleMatch) {
        const b = inf.balance;
        box.append(el("h4", "inf-sub", "Partidos"),
            el("p", "hint", `${b.played} jugados · ${b.won} ganados · ${b.drawn} empatados · ${b.lost} perdidos`
                + ` · Goles: ${b.goalsFor} a favor, ${b.goalsAgainst} en contra`));
        const ul = el("ul", "coach-lista inf-partidos");
        for (const p of inf.matches) {
            const li = el("li"), bt = el("button");
            bt.type = "button";
            bt.append(el("span", null, `${date(p.date)} · ${p.opponent}`),
                el("b", "r-" + p.outcome, `${p.goalsFor} - ${p.goalsAgainst}`),
                el("small", null, [p.home ? "Local" : "Visitante", p.tournament].filter(Boolean).join(" · ")));
            if (opts.onMatch) bt.addEventListener("click", () => opts.onMatch(p.id));
            li.append(bt);
            ul.append(li);
        }
        box.append(ul);
    }

    if (!inf.player) {
        box.append(el("h4", "inf-sub", "Estadísticas por jugador"));
        const wrap = el("div", "coach-tablewrap"), tb = el("table", "coach-table inf-tabla");
        const cols = [
            ["G", x => x.goals], ["GR", x => x.goalsConceded], ["Tiros", x => x.shots],
            ["Ef.", x => x.shotEffectiveness == null ? "–" : x.shotEffectiveness + "%"], ["Ataj.", x => x.saves],
            ["Pérd.", x => x.turnovers], ["Rec.", x => x.recoveries], ["FR", x => x.foulsReceived],
            ["FC", x => x.foulsCommitted], ["Az", x => x.blues], ["Am", x => x.yellows], ["Rj", x => x.reds],
            ["Pen.", x => x.penalties ? `${x.penaltyGoals}/${x.penalties}` : "–"],
            ["Lib.", x => x.freeHits ? `${x.freeHitGoals}/${x.freeHits}` : "–"],
        ];
        if (!inf.singleMatch) cols.unshift(["PJ", null]);
        const hr = el("tr");
        ["Jugador", ...cols.map(c => c[0])].forEach(h => { const th = el("th", null, h); th.scope = "col"; hr.append(th); });
        const thead = el("thead");
        thead.append(hr);
        const body = el("tbody");
        for (const j of inf.players) {
            const tr = el("tr", "clic");
            const td = el("td");
            td.append(document.createTextNode(`${j.number ? j.number + " " : ""}${j.name}`));
            if (j.goalkeeper) td.append(el("span", "inf-ar", "Arq."));
            tr.append(td);
            for (const [h, f] of cols) tr.append(el("td", null, String(h === "PJ" ? j.matches : f(j.metrics))));
            tr.title = "Ver solo a " + j.name;
            tr.addEventListener("click", () => opts.onPlayer && opts.onPlayer(j.key));
            body.append(tr);
        }
        if (!inf.players.length) {
            const tr = el("tr"), td = el("td", null, opts.empty || "Sin acciones cargadas.");
            td.colSpan = cols.length + 1;
            tr.append(td);
            body.append(tr);
        }
        const foot = el("tfoot"), fr = el("tr");
        fr.append(el("td", null, "Equipo"));
        for (const [h, f] of cols) fr.append(el("td", null, String(h === "PJ" ? inf.balance.played : f(m))));
        foot.append(fr);
        tb.append(thead, body, foot);
        wrap.append(tb);
        box.append(wrap);
    }

    if (inf.singleMatch) {
        box.append(el("h4", "inf-sub", inf.player ? `Eventos de ${inf.player.name}` : "Eventos del partido"));
        const ul = el("ul", "inf-eventos");
        if (!inf.events.length) ul.append(el("li", "coach-vacio", "Todavía no hay acciones cargadas."));
        for (const e of inf.events) {
            const li = el("li");
            const tag = el("span", "coach-tag");
            tag.style.background = e.color;
            const w = el("span", "w");
            w.append(tag, e.action);
            li.append(el("span", "m", e.minute), w,
                el("span", "j", `${e.number ? e.number + " " : ""}${e.player}${e.pendingItem ? " · sin enviar" : ""}`));
            ul.append(li);
        }
        box.append(ul);
    }
    box.append(el("p", "coach-note", "G: goles (tiros, libres y penales convertidos) · GR: goles recibidos por el arquero"
        + " · Ef.: efectividad de los tiros al arco · Ataj.: atajadas, con libres y penales atajados · Pérd. y Rec.:"
        + " en defensa (campo propio) y en ataque (campo rival) · FR y FC: faltas recibidas y cometidas."
        + (inf.singleMatch ? " Minuto: tiempo jugado del período." : " PJ: partidos con acciones del jugador.")));
}

export {metrics, build, options, name, value, render, cards, finished, date, TYPES};
