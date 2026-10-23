// Fichas de los jugadores y dashboard de RinkMetrics: todo se calcula en el navegador con las acciones que cargó el
// DT (GET /api/coach/actions), el plantel y la lista de partidos. También dibuja los gráficos (SVG propio, sin
// librerías, para que funcione sin señal).
import {playerKey} from "./actions.js";

const FOR_US = ["SHOT", "FREE_HIT_FOR", "PENALTY_FOR"];
const MONTHS = ["Ene", "Feb", "Mar", "Abr", "May", "Jun", "Jul", "Ago", "Sep", "Oct", "Nov", "Dic"];
const num = (v, d = 1) => v.toLocaleString("es-AR", {minimumFractionDigits: 0, maximumFractionDigits: d});
const pct = v => Math.round(v * 100) + "%";

// Ejes de cada ficha: los del jugador de campo y los del arquero. alto = más es mejor; bajo = menos es mejor.
const FIELD_AXES = [
    {key: "attack", name: "Ataque", shortName: "Ataque", color: "#22b8f0", heightPx: true},
    {key: "precision", name: "Precisión", shortName: "Precisión", color: "#f97316", heightPx: true},
    {key: "imbalance", name: "Desequilibrio", shortName: "Deseq.", color: "#a78bfa", heightPx: true},
    {key: "recovery", name: "Recuperación", shortName: "Recup.", color: "#22c55e", heightPx: true},
    {key: "discipline", name: "Disciplina", shortName: "Disciplina", color: "#ff6b6b", heightPx: false},
];
const GOALKEEPER_AXES = [
    {key: "saves", name: "Atajadas", shortName: "Atajadas", color: "#22b8f0", heightPx: true},
    {key: "effectivenessPct", name: "Efectividad", shortName: "Efectiv.", color: "#22c55e", heightPx: true},
    {key: "setPieces", name: "Pelota parada", shortName: "P. parada", color: "#f97316", heightPx: true},
    {key: "net", name: "Valla", shortName: "Valla", color: "#a78bfa", heightPx: false},
    {key: "discipline", name: "Disciplina", shortName: "Disciplina", color: "#ff6b6b", heightPx: false},
];

/** Cuenta lo de cada jugador: goles, tiros, recuperaciones, faltas, tarjetas, atajadas... */
function tally(list) {
    const c = {matches: new Set(), goals: 0, intentos: 0, kicks: 0, foulsRec: 0, rec: 0, turn: 0, foulsCom: 0,
        yellows: 0, blues: 0, reds: 0, saves: 0, receivedCount: 0, spSaves: 0, spTotal: 0};
    for (const a of list) {
        c.matches.add(a.matchId);
        if (FOR_US.includes(a.kind)) {
            c.intentos++;
            if (a.outcome === "GOAL") c.goals++;
            if (a.kind !== "SHOT") c.kicks++;
        }
        if (a.kind === "FOUL") a.outcome === "RECEIVED" ? c.foulsRec++ : c.foulsCom++;
        if (a.kind === "RECOVERY") c.rec++;
        if (a.kind === "TURNOVER") c.turn++;
        if (a.kind === "CARD") a.outcome === "YELLOW" ? c.yellows++ : a.outcome === "BLUE" ? c.blues++ : c.reds++;
        if (a.kind === "SAVE") c.saves++;
        if (a.kind === "GOAL_CONCEDED") c.receivedCount++;
        if (a.kind === "FREE_HIT_AGAINST" || a.kind === "PENALTY_AGAINST") {
            c.spTotal++;
            a.outcome === "GOAL" ? c.receivedCount++ : c.spSaves++;
        }
    }
    c.pj = c.matches.size;
    return c;
}

const discipline = c => (c.foulsCom + c.yellows * 2 + c.blues * 3 + c.reds * 5) / (c.pj || 1);
const rating = v => v === 0 ? "Impecable" : v < 1 ? "Buena" : v < 2 ? "Regular" : "Para mejorar";

/** Valor de cada eje (crudo, antes de compararlo con el plantel) y lo que se muestra en la fila. */
function axes(c, goalkeeper) {
    const pj = c.pj || 1;
    if (!goalkeeper) {
        const shots = c.intentos;
        return {
            attack: {v: c.goals / pj, text: `${num(c.goals / pj)} ${c.goals / pj === 1 ? "gol" : "goles"} por partido`, a: c.goals, b: shots, labels: ["Goles", "Tiros"]},
            precision: {v: shots ? c.goals / shots : 0, text: shots ? `${pct(c.goals / shots)} convertidos` : "Sin tiros",
                a: c.goals, b: shots - c.goals, labels: ["Convertidos", "Errados"]},
            imbalance: {v: c.foulsRec / pj, text: `${num(c.foulsRec / pj)} por partido`,
                a: c.foulsRec, b: c.kicks, labels: ["Faltas recibidas", "Libres y penales"]},
            recovery: {v: c.rec + c.turn ? c.rec / (c.rec + c.turn) : 0,
                text: c.rec + c.turn ? `${pct(c.rec / (c.rec + c.turn))} de balance` : "Sin datos",
                a: c.rec, b: c.turn, labels: ["Recuperaciones", "Pérdidas"]},
            discipline: {v: discipline(c), text: rating(discipline(c)), a: c.foulsCom,
                b: c.yellows + c.blues + c.reds, labels: ["Faltas", "Tarjetas"]},
        };
    }
    const savedCount = c.saves + c.spSaves;
    return {
        saves: {v: savedCount / pj, text: `${num(savedCount / pj)} por partido`, a: c.saves, b: c.spSaves,
            labels: ["Atajadas", "Libres y penales"]},
        effectivenessPct: {v: savedCount + c.receivedCount ? savedCount / (savedCount + c.receivedCount) : 0,
            text: savedCount + c.receivedCount ? `${pct(savedCount / (savedCount + c.receivedCount))} atajados` : "Sin datos",
            a: savedCount, b: c.receivedCount, labels: ["Atajados", "Recibidos"]},
        setPieces: {v: c.spTotal ? c.spSaves / c.spTotal : 0, text: c.spTotal ? `${pct(c.spSaves / c.spTotal)} atajados` : "Sin libres ni penales",
            a: c.spSaves, b: c.spTotal - c.spSaves, labels: ["Atajados", "Convertidos"]},
        net: {v: c.receivedCount / pj, text: `${num(c.receivedCount / pj)} por partido`, a: c.receivedCount, b: c.pj,
            labels: ["Recibidos", "Partidos"]},
        discipline: {v: discipline(c), text: rating(discipline(c)), a: c.foulsCom,
            b: c.yellows + c.blues + c.reds, labels: ["Faltas", "Tarjetas"]},
    };
}

/**
 * Fichas del plantel: cada eje va de 0 a 100 comparado con el resto del plantel (100 = el mejor del equipo en ese
 * eje), los jugadores de campo entre sí y los arqueros entre sí. El índice es el promedio de los cinco ejes.
 */
function compute(squad, actions) {
    const byPlayer = new Map();
    for (const a of actions) {
        const k = playerKey(a);
        if (!byPlayer.has(k)) byPlayer.set(k, []);
        byPlayer.get(k).push(a);
    }
    const players = squad.map(j => ({key: "j" + j.id, number: j.number, name: j.name, goalkeeper: j.goalkeeper}));
    for (const [k, list] of byPlayer) {
        if (players.some(j => j.key === k)) continue;
        const goalArea = list.filter(a => ["SAVE", "GOAL_CONCEDED", "FREE_HIT_AGAINST", "PENALTY_AGAINST"].includes(a.kind)).length;
        players.push({key: k, number: "", name: list[0].playerName, goalkeeper: goalArea * 2 > list.length});
    }
    const playerCards = players.map(j => {
        const c = tally(byPlayer.get(j.key) || []);
        return Object.assign({}, j, {matches: c.pj, valueList: axes(c, j.goalkeeper)});
    });
    for (const goalkeeper of [false, true]) {
        const group = playerCards.filter(f => f.goalkeeper === goalkeeper && f.matches > 0);
        for (const e of goalkeeper ? GOALKEEPER_AXES : FIELD_AXES) {
            const vs = group.map(f => f.valueList[e.key].v);
            const max = Math.max(0, ...vs), min = Math.min(...vs);
            for (const f of group) {
                const v = f.valueList[e.key].v;
                f.valueList[e.key].scoreValue = e.heightPx ? (max > 0 ? Math.round(100 * v / max) : 0)
                    : (max > min ? Math.round(100 * (max - v) / (max - min)) : 100);
            }
        }
    }
    for (const f of playerCards) {
        f.axes = (f.goalkeeper ? GOALKEEPER_AXES : FIELD_AXES).map(e => Object.assign({}, e, f.valueList[e.key],
            {scoreValue: f.matches ? f.valueList[e.key].scoreValue : 0}));
        f.scoreIndex = f.matches ? Math.round(f.axes.reduce((s, e) => s + e.scoreValue, 0) / f.axes.length) : null;
        delete f.valueList;
    }
    return playerCards.sort((a, b) => (b.scoreIndex ?? -1) - (a.scoreIndex ?? -1) || (parseInt(a.number) || 999) - (parseInt(b.number) || 999));
}

/** Dashboard: totales, estado de los partidos, partidos por mes (los últimos 6) y los próximos. */
function dashboard(matches, today) {
    const currentMonth = today.slice(0, 7);
    const elapsed = p => p.status !== "PENDING";
    const months = [];
    const [y, m] = currentMonth.split("-").map(Number);
    for (let i = 5; i >= 0; i--) {
        const d = new Date(y, m - 1 - i, 1);
        const k = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
        months.push({month: k, label: MONTHS[d.getMonth()], count: matches.filter(p => elapsed(p) && p.date.startsWith(k)).length});
    }
    return {
        matches: matches.length,
        thisMonth: matches.filter(p => p.date.startsWith(currentMonth)).length,
        statuses: {
            scheduled: matches.filter(p => p.status === "PENDING").length,
            inPlay: matches.filter(p => p.status === "IN_PLAY" || p.status === "HALF_TIME").length,
            finishedMatches: matches.filter(p => p.status === "FINISHED" || p.status === "CLOSED").length,
        },
        months,
        upcomingMatches: matches.filter(p => p.status === "PENDING" && p.date >= today)
            .sort((a, b) => a.date.localeCompare(b.date)).slice(0, 5),
    };
}

// ---------- Gráficos (SVG) ----------

const esc = s => String(s).replace(/[&<>"]/g, c => ({"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;"}[c]));

/** Pentágono de la ficha: anillos al 33, 66 y 100 % y el área del jugador. */
function pentagon(cardAxes) {
    const cx = 160, cy = 104, R = 68;
    const point = (i, r) => {
        const ang = -Math.PI / 2 + i * 2 * Math.PI / 5;
        return [cx + r * Math.cos(ang), cy + r * Math.sin(ang)];
    };
    const poly = r => [0, 1, 2, 3, 4].map(i => point(i, r).map(n => n.toFixed(1)).join(",")).join(" ");
    const area = cardAxes.map((e, i) => point(i, R * Math.max(0.06, e.scoreValue / 100)).map(n => n.toFixed(1)).join(",")).join(" ");
    const labels = cardAxes.map((e, i) => {
        const [x, y] = point(i, R + 16);
        const anchor = Math.abs(x - cx) < 4 ? "middle" : x > cx ? "start" : "end";
        return `<text x="${x.toFixed(1)}" y="${(y + 3).toFixed(1)}" text-anchor="${anchor}">${esc(e.name)}</text>`;
    }).join("");
    const title = cardAxes.map(e => `${e.name} ${e.scoreValue}`).join(", ");
    return `<svg class="pentagono" viewBox="0 0 320 200" role="img" aria-label="${esc(title)}"><title>${esc(title)}</title>
        <g class="anillos">${[1, 2 / 3, 1 / 3].map(f => `<polygon points="${poly(R * f)}"/>`).join("")}
        ${[0, 1, 2, 3, 4].map(i => { const [x, y] = point(i, R); return `<line x1="${cx}" y1="${cy}" x2="${x.toFixed(1)}" y2="${y.toFixed(1)}"/>`; }).join("")}</g>
        <polygon class="area" points="${area}"/>
        <g class="etiquetas">${labels}</g></svg>`;
}

/** Estado de los partidos: una barra por estado. */
function statusBars(est) {
    const rows = [["Finalizados", est.finishedMatches, "var(--accent)"], ["En juego", est.inPlay, "var(--lime)"],
        ["Programados", est.scheduled, "var(--muted)"]];
    const max = Math.max(1, ...rows.map(f => f[1]));
    return `<svg class="grafico" viewBox="0 0 300 110" role="img" aria-label="Estado de los partidos: ${rows.map(f => f[0] + " " + f[1]).join(", ")}">
        ${rows.map(([n, v, c], i) => `<text x="0" y="${22 + i * 34}" class="eje">${n}</text>
            <rect x="96" y="${9 + i * 34}" width="${Math.max(3, 170 * v / max).toFixed(1)}" height="18" rx="4" fill="${c}"><title>${n}: ${v}</title></rect>
            <text x="${(102 + 170 * v / max).toFixed(1)}" y="${22 + i * 34}" class="valor">${v}</text>`).join("")}</svg>`;
}

/** Partidos jugados por mes: eje X los meses, eje Y la cantidad. */
function monthsLine(months) {
    const W = 320, H = 112, x0 = 30, y0 = 84, widthPx = W - x0 - 14, heightPx = 70;
    const max = Math.max(2, ...months.map(m => m.count));
    const step = widthPx / (months.length - 1);
    const pts = months.map((m, i) => [x0 + i * step, y0 - heightPx * m.count / max]);
    const ticks = [0, Math.round(max / 2), max];
    return `<svg class="grafico" viewBox="0 0 ${W} ${H}" role="img" aria-label="Partidos por mes: ${months.map(m => m.label + " " + m.count).join(", ")}">
        ${ticks.map(t => { const y = (y0 - heightPx * t / max).toFixed(1); return `<line class="grilla" x1="${x0}" y1="${y}" x2="${W - 10}" y2="${y}"/><text class="eje" x="${x0 - 8}" y="${+y + 3}" text-anchor="end">${t}</text>`; }).join("")}
        <polygon class="area-linea" points="${x0},${y0} ${pts.map(p => p.map(n => n.toFixed(1)).join(",")).join(" ")} ${(x0 + widthPx).toFixed(1)},${y0}"/>
        <polyline class="linea" points="${pts.map(p => p.map(n => n.toFixed(1)).join(",")).join(" ")}"/>
        ${pts.map(([x, y], i) => `<circle class="punto" cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="4"><title>${months[i].label}: ${months[i].count} partidos</title></circle>
            <text class="eje" x="${x.toFixed(1)}" y="${H - 8}" text-anchor="middle">${months[i].label}</text>`).join("")}</svg>`;
}

export {compute, dashboard, pentagon, statusBars, monthsLine};
