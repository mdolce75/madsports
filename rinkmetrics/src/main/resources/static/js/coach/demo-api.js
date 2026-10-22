// API de RinkMetrics simulada para la demo: contesta en el navegador lo mismo que el servidor (CoachController y sus
// servicios: equipos, jugadores, partidos, acciones, reloj e informes), con las mismas validaciones y los mismos
// mensajes. Se verifica contra los mismos casos que el servidor (src/test/resources/coach/api-contract.json). No usa el
// DOM: corre también en Node para esos tests.
import {isGoalFor, isGoalAgainst} from "./actions.js";
import * as Report from "./report.js";

const UUID = /^[0-9a-fA-F-]{36}$/;
const OUTCOMES = {
    TURNOVER: ["OWN_HALF", "RIVAL_HALF"], RECOVERY: ["OWN_HALF", "RIVAL_HALF"], SHOT: ["GOAL", "NO_GOAL"], SAVE: [],
    GOAL_CONCEDED: [], FREE_HIT_FOR: ["GOAL", "NO_GOAL"], FREE_HIT_AGAINST: ["GOAL", "SAVED"], PENALTY_FOR: ["GOAL", "NO_GOAL"],
    PENALTY_AGAINST: ["GOAL", "SAVED"], FOUL: ["COMMITTED", "RECEIVED"], CARD: ["YELLOW", "BLUE", "RED"],
};
const REOPEN_WINDOW = 48 * 3600 * 1000;
const FIRST_OWN_ID = 900000000;

/** Un error de la API: el status y el detalle que devuelve el servidor (ProblemDetail). */
class Rejection extends Error {
    constructor(status, detail) { super(detail); this.status = status; }
}
const badRequest = d => new Rejection(400, d), notFound = d => new Rejection(404, d), conflict = d => new Rejection(409, d);

const json = (status, body) => new Response(JSON.stringify(body), {status, headers: {"Content-Type": "application/json"}});
const clean = s => (s == null ? "" : String(s)).trim().replace(/\s+/g, " ");
const byText = (a, b) => a.localeCompare(b, "es", {sensitivity: "base"});
const shortDate = f => `${f.slice(8, 10)}/${f.slice(5, 7)}/${f.slice(0, 4)}`;

/**
 * Crea la API. sesion: la de /api/sesion (con club y suscripcion: equiposMax y partidosPorMes); datos iniciales:
 * equipos [{id, nombre}], jugadores [{id, numero, nombres, apellido, arquero, equipos}] y partidos (con sus acciones;
 * desde = cuándo arrancó el reloj, en ms). club: el club en SIDGAD, para armar equipos desde una categoría:
 * {categorias: [{clave, rama, nombre}], planteles: {"CLAVE|RAMA": [{id, numero, nombre, nombres, apellido, arquero}]}}.
 * ahora: el reloj (ms), para los tests.
 */
export function createApi({session, teams = [], players = [], matches = [], club = {categoryList: [], squads: {}},
                          now = () => Date.now()}) {
    const today = () => { const d = new Date(now()); return new Date(d - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10); };
    let nextTeam = Math.max(0, ...teams.map(e => e.id)) + 1;
    let nextMatch = Math.max(0, ...matches.map(p => p.id)) + 1;
    let started = 0;
    players = players.map(j => Object.assign({active: true, pending: []}, j, {number: j.number || null}));
    matches = matches.map(p => Object.assign({idp: null, tournament: null, period: 1, seconds: 0, from: null,
        closedAt: null, actions: []}, p));

    // ---------- Equipos ----------

    const finished = p => p.status === "FINISHED" || p.status === "CLOSED";
    const activeActions = p => p.actions.filter(a => !a.deletedAt);
    const gf = p => activeActions(p).filter(isGoalFor).length, gc = p => activeActions(p).filter(isGoalAgainst).length;

    function teamView(e) {
        const ps = matches.filter(p => p.teamId === e.id && finished(p));
        return {id: e.id, name: e.name, played: ps.length, won: ps.filter(p => gf(p) > gc(p)).length,
            drawn: ps.filter(p => gf(p) === gc(p)).length, lost: ps.filter(p => gf(p) < gc(p)).length,
            sidgadCategory: e.sidgadCategory || null, sidgadBranch: e.sidgadBranch || null};
    }

    /** El equipo pedido; sin id, el primero. */
    function team(id) {
        if (id == null) {
            if (!teams.length) throw badRequest("Primero creá un equipo en la sección Equipo.");
            return teams[0];
        }
        const e = teams.find(x => x.id === id);
        if (!e) throw notFound("No existe ese equipo.");
        return e;
    }

    function teamName(name, current) {
        const n = clean(name);
        if (!n) throw badRequest("Escribí el nombre del equipo.");
        if (n.length > 60) throw badRequest("El nombre del equipo es muy largo (hasta 60 letras).");
        const same = current && current.name.toLowerCase() === n.toLowerCase();
        if (!same && teams.some(e => e.name.toLowerCase() === n.toLowerCase())) throw conflict(`Ya tenés un equipo llamado ${n}.`);
        return n;
    }

    /** Equipo nuevo: con nombre libre, o desde una categoría del club con su plantel de SIDGAD (menos los excluidos). */
    function createTeam(body) {
        if (body.category) return createTeamFromSidgad(body);
        return json(201, teamView(newTeam(body.name, null, null)));
    }

    function newTeam(name, category, branch) {
        const n = teamName(name, null);
        const plan = session.subscription || {};
        const max = plan.maxTeams || 0;
        if (max > 0 && teams.length >= max) {
            throw conflict(`Tu plan ${plan.name} permite ${max === 1 ? "1 equipo" : "hasta " + max + " equipos"}. Para sumar `
                + `otro, borrá uno sin partidos${plan.code === "COACH_FREE" ? " o pasate al plan mensual." : "."}`);
        }
        const e = {id: nextTeam++, name: n, sidgadCategory: category, sidgadBranch: branch};
        teams.push(e);
        return e;
    }

    // ---------- Equipos desde SIDGAD ----------

    function clubCategory(category, branch) {
        const c = club.categoryList.find(x => x.key === String(category || "").trim().toUpperCase()
            && x.branch === String(branch || "").trim().toUpperCase());
        if (!c) throw badRequest("Esa categoría no es de tu club.");
        return c;
    }

    const sidgadSquad = (category, branch) => {
        const c = clubCategory(category, branch);
        return (club.squads[c.key + "|" + c.branch] || []).slice()
            .sort((a, b) => byText(a.lastName, b.lastName) || byText(a.names, b.names));
    };

    function createTeamFromSidgad(body) {
        const c = clubCategory(body.category, body.branch);
        const teamSquad = sidgadSquad(c.key, c.branch);
        const e = newTeam(clean(body.name) ? body.name : c.name, c.key, c.branch);
        const out = new Set(body.excluded || []);
        const used = new Set();
        // Primero los incluidos: si dos tienen el mismo número, se lo queda uno que juega.
        for (const p of teamSquad.slice().sort((a, b) => out.has(a.id) - out.has(b.id))) {
            const included = !out.has(p.id);
            let j = players.find(x => x.id === p.id);
            if (!j) {
                const num = !p.number || used.has(p.number) ? null : p.number;
                if (num) used.add(num);
                j = {id: p.id, names: p.names, lastName: p.lastName, number: num, goalkeeper: p.goalkeeper, teams: [],
                    pending: [], active: true};
                players.push(j);
            } else {
                j.active = true;
                if (j.number) used.add(j.number);
            }
            if (included) includeIn(j, e.id); else if (!j.teams.includes(e.id) && !j.pending.includes(e.id)) j.pending.push(e.id);
        }
        return json(201, teamView(e));
    }

    function includeIn(j, teamId) {
        j.pending = j.pending.filter(x => x !== teamId);
        if (!j.teams.includes(teamId)) j.teams.push(teamId);
    }

    function include(playerId, teamId) {
        const j = ownPlayer(playerId);
        const e = team(teamId);
        checkNumber([e.id], j.number, playerId);
        includeIn(j, e.id);
        return json(200, playerView(j));
    }

    function deleteTeam(id) {
        const e = team(id);
        const n = matches.filter(p => p.teamId === e.id).length;
        if (n) throw conflict(`${e.name} tiene ${n}${n === 1 ? " partido cargado" : " partidos cargados"}: no se puede borrar.`);
        players.forEach(j => {
            j.teams = j.teams.filter(x => x !== e.id);
            j.pending = j.pending.filter(x => x !== e.id);
        });
        teams.splice(teams.indexOf(e), 1);
        return new Response(null, {status: 204});
    }

    // ---------- Jugadores ----------

    const fullName = j => (j.names + " " + j.lastName).trim();
    const playerView = j => ({id: j.id, number: j.number || "", name: fullName(j), names: j.names, lastName: j.lastName,
        goalkeeper: j.goalkeeper, teams: j.teams.slice().sort((a, b) => a - b),
        pending: j.pending.slice().sort((a, b) => a - b)});
    const activePlayers = () => players.filter(j => j.active);

    function ownPlayer(id) {
        const j = activePlayers().find(x => x.id === id);
        if (!j) throw notFound("Ese jugador no está en tus equipos.");
        return j;
    }

    function validTeams(requests) {
        const ids = [...new Set((requests || []).filter(x => x != null))];
        if (!ids.length) throw badRequest("Asigná el jugador a un equipo.");
        ids.forEach(team);
        return ids;
    }

    function number(s) {
        const t = (s == null ? "" : String(s)).trim();
        if (!t) return null;
        if (!/^\d{1,3}$/.test(t)) throw badRequest("El número es de 1 a 3 cifras.");
        return String(Number(t));
    }

    function checkNumber(ownTeams, num, except) {
        if (num == null) return;
        const other = activePlayers().find(j => j.id !== except && j.number === num && j.teams.some(e => ownTeams.includes(e)));
        if (other) throw conflict(`El número ${num} ya es de ${fullName(other)} en ese equipo.`);
    }

    function text(s, field) {
        const t = clean(s);
        if (!t) throw badRequest(`Escribí el ${field} del jugador.`);
        if (t.length > 40) throw badRequest(`El ${field} es muy largo (hasta 40 letras).`);
        return t;
    }

    function createPlayer(body) {
        const ownTeams = validTeams(body.teams);
        const num = number(body.number);
        checkNumber(ownTeams, num, -1);
        const last = Math.max(0, ...players.map(j => j.id));
        const j = {id: last >= FIRST_OWN_ID ? last + 1 : FIRST_OWN_ID, names: text(body.name, "nombre"),
            lastName: text(body.lastName, "apellido"), number: num, goalkeeper: body.goalkeeper === true, teams: ownTeams,
            pending: [], active: true};
        players.push(j);
        return json(201, playerView(j));
    }

    function updatePlayer(id, body) {
        const j = ownPlayer(id);
        const ownTeams = validTeams(body.teams);
        const num = number(body.number);
        checkNumber(ownTeams, num, id);
        Object.assign(j, {names: text(body.name, "nombre"), lastName: text(body.lastName, "apellido"), number: num,
            goalkeeper: body.goalkeeper === true, teams: ownTeams, pending: j.pending.filter(x => !ownTeams.includes(x))});
        return json(200, playerView(j));
    }

    /** Los jugadores del equipo, por número (sin número, al final) y apellido. */
    const squad = e => activePlayers().filter(j => j.teams.includes(e.id))
        .sort((a, b) => (a.number == null ? 9999 : Number(a.number)) - (b.number == null ? 9999 : Number(b.number))
            || byText(a.lastName, b.lastName));

    // ---------- Partidos ----------

    const duration = p => p.periodMinutes * 60;
    const elapsed = p => Math.max(0, Math.min(duration(p), p.seconds + (p.from != null ? Math.floor((now() - p.from) / 1000) : 0)));
    const inOrder = p => activeActions(p).map((a, i) => [a, i]).sort(([a, i], [b, k]) => a.period - b.period || a.atSecond - b.atSecond || i - k)
        .map(([a]) => a);

    function matchView(p, withActions) {
        const j = elapsed(p);
        return {id: p.id, teamId: p.teamId, team: (teams.find(e => e.id === p.teamId) || {}).name || "",
            idp: p.idp, tournament: p.tournament, opponent: p.opponent, date: p.date, home: p.home, periodMinutes: p.periodMinutes,
            status: p.status, goalsFor: gf(p), goalsAgainst: gc(p),
            clock: {period: p.period, elapsed: j, remaining: duration(p) - j, running: p.from != null},
            actions: withActions ? inOrder(p).map(a => ({id: a.id, playerId: a.playerId, playerName: a.playerName,
                kind: a.kind, outcome: a.outcome ?? null, period: a.period, atSecond: a.atSecond})) : []};
    }

    function match(id) {
        const p = matches.find(x => x.id === id);
        if (!p) throw notFound("No existe ese partido.");
        return p;
    }

    function createMatch(n) {
        const e = team(n.team ?? null);
        const opponent = clean(n.opponent);
        if (!opponent) throw badRequest("Escribí el rival.");
        if (opponent.length > 60) throw badRequest("El nombre del rival es muy largo.");
        const tournament = clean(n.tournament);
        if (tournament.length > 60) throw badRequest("El nombre del campeonato es muy largo.");
        const minutes = n.minutes == null ? 25 : n.minutes;
        if (minutes < 10 || minutes > 40) throw badRequest("Cada tiempo dura entre 10 y 40 minutos.");
        const date = n.date || today();
        const existing = matches.find(p => p.teamId === e.id && p.opponent === opponent && p.date === date);
        if (existing) return json(201, matchView(existing, true));
        const quota = (session.subscription || {}).matchesPerMonth || 0;
        if (quota > 0 && started >= quota) throw conflict(`Ya cargaste los ${quota} partidos de este mes.`);
        started++;
        const p = {id: nextMatch++, teamId: e.id, idp: null, tournament: tournament || null, opponent, date,
            home: n.home == null || n.home, periodMinutes: minutes, status: "PENDING", period: 1, seconds: 0,
            from: null, closedAt: null, actions: []};
        matches.push(p);
        return json(201, matchView(p, true));
    }

    function validateAction(a, p) {
        if (!a || a.id == null || !UUID.test(a.id)) throw badRequest("Cada acción lleva su id (uuid).");
        if (!a.kind) throw badRequest("Falta el tipo de la acción.");
        const accepts = OUTCOMES[a.kind];
        if (!accepts) throw badRequest("El pedido no es válido.");
        const res = a.outcome ?? null;
        if (accepts.length ? !accepts.includes(res) : res !== null) throw badRequest(`El resultado ${res} no corresponde a ${a.kind}.`);
        if (!(a.period >= 1 && a.period <= 2)) throw badRequest("El tiempo es 1 o 2.");
        if (!(a.atSecond >= 0 && a.atSecond <= duration(p))) throw badRequest("El minuto está fuera del tiempo.");
    }

    /** Agrega una tanda de acciones; si una no va, no se guarda ninguna (como la transacción del servidor). */
    function enqueue(p, newOnes) {
        if (p.status === "CLOSED") throw conflict("El partido está cerrado: reabrilo para cargar acciones.");
        const ofTeam = new Map(squad(team(p.teamId)).map(j => [j.id, j]));
        const batch = [];
        for (const a of newOnes || []) {
            validateAction(a, p);
            if (p.actions.some(x => x.id === a.id) || batch.some(x => x.id === a.id)) continue;
            let name;
            if (a.playerId > 0) {
                const j = ofTeam.get(a.playerId);
                if (!j) throw badRequest(`El jugador ${a.playerId} no está en el plantel del equipo.`);
                name = fullName(j);
            } else {
                if (!a.playerName || !a.playerName.trim()) throw badRequest("Elegí el jugador de la acción.");
                name = a.playerName.trim();
            }
            batch.push({id: a.id, playerId: Math.max(0, a.playerId), playerName: name, kind: a.kind,
                outcome: a.outcome ?? null, period: a.period, atSecond: a.atSecond});
        }
        p.actions.push(...batch);
        return json(200, matchView(p, true));
    }

    function deleteAction(p, actionId) {
        if (p.status === "CLOSED") throw conflict("El partido está cerrado: reabrilo para borrar acciones.");
        // Borrar una ya borrada no es un error (el teléfono puede reenviar el borrado).
        const a = p.actions.find(x => x.id === actionId);
        if (!a) throw notFound("No existe esa acción en el partido.");
        a.deletedAt = true;
        return json(200, matchView(p, true));
    }

    function inPlay(p) {
        if (p.status !== "IN_PLAY") {
            throw conflict(p.status === "PENDING" ? "Primero iniciá el partido."
                : p.status === "HALF_TIME" ? "Es el entretiempo: iniciá el 2T." : "El partido ya terminó.");
        }
    }

    function clock(p, action, seconds) {
        const t = now();
        switch (action) {
            case "START":
                if (p.status === "PENDING") p.period = 1;
                else if (p.status === "HALF_TIME") p.period = 2;
                else throw conflict("El partido ya está en juego o terminó.");
                p.status = "IN_PLAY"; p.seconds = 0; p.from = t;
                break;
            case "PAUSE":
                inPlay(p);
                if (p.from == null) throw conflict("El reloj ya está en pausa.");
                p.seconds = elapsed(p); p.from = null;
                break;
            case "RESUME":
                inPlay(p);
                if (p.from != null) throw conflict("El reloj ya está corriendo.");
                if (elapsed(p) >= duration(p)) throw conflict("El tiempo ya terminó.");
                p.from = t;
                break;
            case "END_PERIOD":
                inPlay(p);
                p.seconds = duration(p); p.from = null; p.status = p.period === 1 ? "HALF_TIME" : "FINISHED";
                break;
            case "ADJUST": {
                inPlay(p);
                p.seconds = Math.max(0, Math.min(duration(p), elapsed(p) + (seconds || 0)));
                if (p.from != null) p.from = t;
                break;
            }
            default:
                throw badRequest(action == null ? "Indicá la acción del reloj." : "El pedido no es válido.");
        }
        return json(200, matchView(p, true));
    }

    function close(p) {
        if (p.status === "CLOSED") throw conflict("El partido ya está cerrado.");
        if (p.from != null) { p.seconds = elapsed(p); p.from = null; }
        p.status = "CLOSED";
        p.closedAt = now();
        return json(200, matchView(p, true));
    }

    function reopen(p) {
        if (p.status !== "CLOSED") throw conflict("El partido no está cerrado.");
        if (now() - p.closedAt > REOPEN_WINDOW) throw conflict("Pasaron más de 48 horas desde que se cerró: ya no se puede reabrir.");
        p.status = "FINISHED";
        p.closedAt = null;
        return json(200, matchView(p, true));
    }

    // ---------- Informes (el mismo armado que la pantalla sin señal: informe.js) ----------

    function report(title, detail, singleMatch, ps, player) {
        const ids = new Set(ps.map(p => p.id));
        const actions = matches.filter(p => ids.has(p.id)).sort((a, b) => a.id - b.id)
            .flatMap(p => inOrder(p).map(a => Object.assign({matchId: p.id}, a)));
        const known = activePlayers().map(j => ({key: "j" + j.id, number: j.number || "", name: fullName(j), goalkeeper: j.goalkeeper}));
        const views = ps.map(p => Object.assign(matchView(p, false), {goalsFor: gf(p), goalsAgainst: gc(p)}));
        const inf = Report.build({title, detail, singleMatch, matches: views, actions, players: known,
            player: player || null});
        if (player && !inf.player) throw badRequest("Ese jugador no tiene acciones en el informe.");
        return json(200, Object.assign({club: session.club.name, team: team(ps[0].teamId).name, generatedAt: today()}, inf));
    }

    function matchReport(p, player) {
        const e = team(p.teamId);
        const detail = [e.name, p.home ? "Local" : "Visitante", p.tournament, shortDate(p.date)].filter(Boolean).join(" · ");
        return report(`${session.club.name} ${gf(p)} - ${gc(p)} ${p.opponent}`, detail, true, [p], player);
    }

    function periodReport(q) {
        const period = q.get("period");
        if (!period || !Report.TYPES[period]) throw badRequest("El pedido no es válido.");
        const e = team(q.get("team") ? Number(q.get("team")) : null);
        const v = (q.get("value") || "").trim();
        const name = Report.name(period, v);
        const ps = matches.filter(p => p.teamId === e.id && finished(p) && Report.value(period, p) === v)
            .sort((a, b) => a.date.localeCompare(b.date) || a.id - b.id);
        if (!ps.length) throw badRequest(`No hay partidos finalizados en ${name}.`);
        return report(period === "CHAMPIONSHIP" && v ? "Campeonato " + name : name,
            `${e.name} · ${ps.length}${ps.length === 1 ? " partido" : " partidos"}`, false, ps, q.get("player"));
    }

    // ---------- Rutas ----------

    function route(method, url, body) {
        const r = url.pathname, q = url.searchParams;
        const eq = () => team(q.get("team") ? Number(q.get("team")) : null);
        if (r === "/api/session") return json(200, session);
        if (r === "/api/coach/teams" && method === "GET") return json(200, teams.slice().sort((a, b) => a.id - b.id).map(teamView));
        if (r === "/api/coach/teams" && method === "POST") return createTeam(body || {});
        if (r === "/api/coach/club/categories") return json(200, club.categoryList);
        if (r === "/api/coach/club/squad") {
            if (!q.get("category") || !q.get("branch")) throw badRequest("El pedido no es válido.");
            return json(200, sidgadSquad(q.get("category"), q.get("branch")));
        }
        const inc = r.match(/^\/api\/coach\/players\/(\d+)\/teams\/(\d+)$/);
        if (inc && method === "POST") return include(Number(inc[1]), Number(inc[2]));
        let m = r.match(/^\/api\/coach\/teams\/(\d+)$/);
        if (m && method === "PUT") { const e = team(Number(m[1])); e.name = teamName((body || {}).name, e); return json(200, teamView(e)); }
        if (m && method === "DELETE") return deleteTeam(Number(m[1]));
        if (r === "/api/coach/players" && method === "GET") return json(200, activePlayers()
            .sort((a, b) => byText(a.lastName, b.lastName) || byText(a.names, b.names)).map(playerView));
        if (r === "/api/coach/players" && method === "POST") return createPlayer(body || {});
        m = r.match(/^\/api\/coach\/players\/(\d+)$/);
        if (m && method === "PUT") return updatePlayer(Number(m[1]), body || {});
        if (m && method === "DELETE") { const j = ownPlayer(Number(m[1])); j.active = false; j.teams = []; return new Response(null, {status: 204}); }
        if (r === "/api/coach/squad") return json(200, squad(eq()).map(playerView));
        if (r === "/api/coach/actions") {
            const e = eq();
            return json(200, matches.filter(p => p.teamId === e.id).sort((a, b) => a.id - b.id)
                .flatMap(p => matchView(p, true).actions.map(a => Object.assign({matchId: p.id}, a))));
        }
        if (r === "/api/coach/matches" && method === "GET") return json(200, matches.slice()
            .sort((a, b) => b.date.localeCompare(a.date) || b.id - a.id).map(p => matchView(p, false)));
        if (r === "/api/coach/matches" && method === "POST") return createMatch(body || {});
        if (r === "/api/coach/reports" && method === "GET") return periodReport(q);
        m = r.match(/^\/api\/coach\/matches\/(\d+)(?:\/(actions|clock|close|reopen|report))?(?:\/(.+))?$/);
        if (m) {
            const p = match(Number(m[1]));
            if (!m[2] && method === "GET") return json(200, matchView(p, true));
            if (!m[2] && method === "DELETE") { matches.splice(matches.indexOf(p), 1); return new Response(null, {status: 204}); }
            if (m[2] === "actions" && method === "POST" && !m[3]) return enqueue(p, body);
            if (m[2] === "actions" && method === "DELETE" && m[3]) return deleteAction(p, decodeURIComponent(m[3]));
            if (m[2] === "clock" && method === "POST") return clock(p, (body || {}).action ?? null, (body || {}).seconds);
            if (m[2] === "close" && method === "POST") return close(p);
            if (m[2] === "reopen" && method === "POST") return reopen(p);
            if (m[2] === "report" && method === "GET") return matchReport(p, q.get("player"));
        }
        return json(404, {detail: "No existe."});
    }

    /** Contesta un pedido: (método, url relativa o absoluta, cuerpo ya parseado) → Response. */
    return function api(method, url, body) {
        try {
            return route(method.toUpperCase(), new URL(url, "http://demo"), body);
        } catch (e) {
            if (e instanceof Rejection) return json(e.status, {status: e.status, detail: e.message});
            throw e;
        }
    };
}
