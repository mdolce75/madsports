// Partido en vivo: el reloj, la carga de las acciones de cada jugador y el informe del partido. Las acciones pasan por
// la cola (cola.js), así se guardan aunque no haya señal; el reloj sí necesita conexión.
import {GOAL_ACTIONS, PAIRS, SIMPLE, action, playerKey, isGoalFor, isGoalAgainst} from "./actions.js";
import * as Queue from "./queue.js";
import * as Report from "./report.js";
import {shortName, matchDetail, coach, el, exportPdf, getJson, mmss, teamName, toast} from "./common.js";

// Partido abierto: lo que devolvió el servidor, cuándo (para el reloj), plantel, pendientes de la cola.
let match = null, received = 0, squad = [], pendingOps = [], pendingTotal = 0;
let selected = null, goalkeeper = null;
let reportPlayer = null;   // jugador elegido en el informe del partido (null: todo el equipo)
let notified = "";   // tiempo cuyo 00:00 ya se avisó
let wakeLock = null;

export const isOpen = () => match;

export async function openItem(id) {
    $("v-elegir").hidden = true;
    $("v-partido").hidden = false;
    document.body.classList.add("coach-vivo");
    tab("t-cargar");
    try {
        const r = await getJson(`/api/coach/matches/${id}`);
        match = r.data;
        received = r.at;
        pendingOps = [];
        selected = null;
        reportPlayer = null;
        delete $("informe-vivo").dataset.signature;
        delete $("jugadores").dataset.signature;
        delete $("log").dataset.signature;
        notified = "";
        try {
            squad = (await getJson(`/api/coach/squad?team=${r.data.teamId}`)).data;
        } catch (e) {
            squad = [];
        }
        squad.sort((a, b) => (parseInt(a.number) || 999) - (parseInt(b.number) || 999));
        goalkeeper = read("ar", null) || (squad.find(j => j.goalkeeper) ? "j" + squad.find(j => j.goalkeeper).id : null);
        receive(r.data, r.at);
        await refreshPending();
        window.scrollTo(0, 0);
    } catch (err) {
        toast(err.status === 404 ? "No existe ese partido." : "No se pudo abrir el partido: " + err.message);
        location.hash = "matches";
    }
}

/** Sale de la carga (otra vista del menú). */
export function exit() {
    match = null;
    updateWakeLock();
    $("v-partido").hidden = true;
    document.body.classList.remove("coach-vivo");
}

/** Lo que devuelve el servidor (al abrir, al mover el reloj, al mandar acciones). */
function receive(p, at) {
    if (match && p.id !== match.id) return;
    match = p;
    received = at;
    render();
}

async function refreshPending() {
    if (!match) return;
    pendingOps = await Queue.pending(match.id);
    render();
}

/**
 * Aviso de la cola: lo que quedó sin enviar y el partido que devolvió el servidor, si hay. Devuelve false si no hay un
 * partido abierto (para que la lista de partidos se actualice).
 */
export async function onQueueChange({match: p, pending, error}) {
    pendingTotal = pending;
    if (p && match && p.id === match.id) receive(p, Date.now());
    if (error) toast(error);
    if (!match) return false;
    await refreshPending();
    return true;
}

// Lo guardado en el teléfono por partido: jugadores agregados a mano y el arquero en cancha.
function read(k, def) {
    try { const v = JSON.parse(localStorage.getItem(`dt-${k}-${match.id}`)); return v == null ? def : v; }
    catch (e) { return def; }
}
function save(k, v) {
    try { localStorage.setItem(`dt-${k}-${match.id}`, JSON.stringify(v)); } catch (e) { /* sin almacenamiento */ }
}

/** Segundos jugados del tiempo, contando desde la última respuesta del servidor. */
function elapsed() {
    if (!match) return 0;
    const dur = match.periodMinutes * 60, r = match.clock;
    const extra = r.running ? Math.floor((Date.now() - received) / 1000) : 0;
    return Math.max(0, Math.min(dur, r.elapsed + extra));
}

/** Acciones activas: las del servidor sin las borradas en la cola, más las que todavía no se mandaron. */
function actions() {
    const removals = new Set(pendingOps.filter(o => o.kind === "baja").map(o => o.actionId));
    const list = match.actions.filter(a => !removals.has(a.id));
    const ids = new Set(list.map(a => a.id));
    for (const o of pendingOps) {
        // Una sin enviar que ya se borró (el borrado sale cuando termine de llegar) no se muestra.
        if (o.kind === "alta" && !ids.has(o.action.id) && !removals.has(o.action.id)) list.push(Object.assign({pendingItem: true}, o.action));
    }
    return list.sort((a, b) => a.period - b.period || a.atSecond - b.atSecond);
}

function players() {
    const list = squad.map(j => ({key: "j" + j.id, id: j.id, number: j.number, name: j.name, goalkeeper: j.goalkeeper}));
    const extras = read("extras", []);
    for (const x of extras) list.push({key: "m" + x.name, id: 0, number: x.number, name: x.name, goalkeeper: false});
    for (const a of match.actions) {
        if (a.playerId === 0 && !list.some(j => j.key === "m" + a.playerName)) {
            list.push({key: "m" + a.playerName, id: 0, number: "", name: a.playerName, goalkeeper: false});
        }
    }
    return list;
}

function render() {
    if (!match) return;
    $("m-propio").textContent = coach.session.club.name;
    $("m-propio-sub").textContent = teamName(match);
    $("m-rival").textContent = match.opponent;
    $("m-rival-sub").textContent = [match.home ? "Local" : "Visitante", match.tournament].filter(Boolean).join(" · ");
    const acc = actions();
    $("m-gf").textContent = acc.filter(isGoalFor).length;
    $("m-gc").textContent = acc.filter(isGoalAgainst).length;
    renderClock();
    renderButtons();
    renderSync();
    renderPlayers();
    renderActions(acc);
    renderLog(acc);
    renderSummary(acc);
}

function renderClock() {
    if (!match) return;
    const dur = match.periodMinutes * 60, j = elapsed();
    const e = match.status;
    $("m-tiempo").textContent = e === "PENDING" ? "1T" : e === "HALF_TIME" ? "ET"
        : e === "FINISHED" || e === "CLOSED" ? "FIN" : match.clock.period + "T";
    $("m-reloj").textContent = mmss(dur - j);
    $("m-reloj").classList.toggle("on", match.clock.running);
    const endAt = e === "IN_PLAY" && j >= dur;
    const k = match.id + ":" + match.clock.period;
    if (endAt && notified !== k) {
        notified = k;
        if (navigator.vibrate) navigator.vibrate([400, 150, 400]);
        notice(`Terminó el ${match.clock.period}T: tocá «${match.clock.period === 1 ? "Fin del 1T" : "Fin del partido"}».`);
        renderButtons();
    } else if (!endAt && $("m-aviso").dataset.kind === "fin") {
        notice("");
    }
}

function notice(txt) {
    $("m-aviso").hidden = !txt;
    $("m-aviso").textContent = txt;
    $("m-aviso").dataset.kind = txt ? "fin" : "";
}

function renderButtons() {
    const box = $("m-botones");
    box.replaceChildren();
    const r = match.clock, e = match.status;
    const button = (txt, clockAction, cls, confirmText) => {
        const b = el("button", "coach-play " + (cls || ""), txt);
        b.type = "button";
        b.addEventListener("click", () => {
            if (confirmText && !confirm(confirmText)) return;
            clock(clockAction);
        });
        box.append(b);
    };
    if (e === "PENDING") button("Iniciar partido", "START");
    if (e === "IN_PLAY") {
        const remainingCount = elapsed() < match.periodMinutes * 60;
        if (r.running && remainingCount) button("Pausar", "PAUSE", "on");
        else if (!r.running && remainingCount) button("Reanudar", "RESUME");
        button(r.period === 1 ? "Fin del 1T" : "Fin del partido", "END_PERIOD", "ghost",
            r.period === 1 ? "¿Terminar el primer tiempo?" : "¿Terminar el partido?");
    }
    if (e === "HALF_TIME") button("Iniciar 2T", "START");
    if (e === "FINISHED") {
        const b = el("button", "coach-play ghost", "Cerrar partido");
        b.type = "button";
        b.addEventListener("click", close);
        box.append(b);
    }
    $("m-ajuste").hidden = e !== "IN_PLAY";
    updateWakeLock();
}

/**
 * POST del partido abierto. "Sin señal" solo si el pedido no llegó al servidor (fetch falla con TypeError); un error
 * del servidor muestra su detalle. Lo que pasa después (pintar la respuesta) queda fuera del try: un error de la
 * pantalla no se confunde con falta de señal.
 */
async function sendMatch(path, body, offline) {
    let p;
    try {
        p = await postJson(`/api/coach/matches/${match.id}/${path}`, body);
    } catch (err) {
        toast(err instanceof TypeError ? offline : err.message);
        return;
    }
    const before = match.status;
    receive(p, Date.now());
    // Al terminar el partido (fin del 2T), directo al informe.
    if (before === "IN_PLAY" && p.status === "FINISHED") {
        tab("t-resumen");
        window.scrollTo(0, 0);
    }
}

function clock(clockAction, seconds) {
    return sendMatch("clock", {action: clockAction, seconds},
        "Sin señal: el reloj necesita conexión. Las acciones se siguen guardando.");
}

async function close() {
    if (!confirm("¿Cerrar el partido? Ya no se podrán cargar acciones (se puede reabrir durante 48 horas).")) return;
    if (pendingOps.length) { toast("Esperá a que se envíe lo cargado antes de cerrar."); Queue.send(); return; }
    await sendMatch("close", {}, "Sin señal: para cerrar el partido hace falta conexión.");
}

function reopen() {
    return sendMatch("reopen", {}, "Sin señal: para reabrir hace falta conexión.");
}

function renderSync() {
    const n = pendingTotal;
    const s = $("m-sync");
    s.className = "coach-sync" + (n ? " pend" : "") + (navigator.onLine ? "" : " off");
    s.textContent = (navigator.onLine ? "" : "Sin señal · ") + (n ? `Sin enviar: ${n}` : navigator.onLine ? "Todo enviado" : "nada pendiente");
}

// Los botones se arman una sola vez (o cuando cambia el plantel) y después solo se actualizan: si se rearmaran con cada
// respuesta del servidor, un toque justo en ese momento se perdería.
function renderPlayers() {
    const box = $("jugadores");
    const list = players();
    const signature = list.map(j => `${j.key}|${j.number}|${j.goalkeeper}`).join(";");
    if (box.dataset.signature !== signature) {
        box.dataset.signature = signature;
        box.replaceChildren();
        if (!list.length) {
            const p = el("p", "coach-note");
            const a = el("a", null, "Equipo");
            a.href = "#team";
            p.append("Este equipo no tiene jugadores: cargalos en ", a, ", o agregá uno solo para este partido.");
            box.append(p);
        }
        for (const j of list) {
            const b = el("button", "coach-player" + (j.goalkeeper ? " gk" : ""));
            b.type = "button";
            b.dataset.key = j.key;
            // Lo principal es el nombre (completo, en una o dos líneas); el número va chico, arriba.
            const above = el("span", "cab");
            above.append(el("span", "n", j.number ? "#" + j.number : "s/n"));
            if (j.goalkeeper) above.append(el("span", "ar", "Arquero"));
            b.append(above, el("span", "s", j.name));
            b.addEventListener("click", () => { selected = j.key; renderPlayers(); });
            box.append(b);
        }
    }
    for (const b of box.querySelectorAll(".coach-player")) {
        const j = list.find(x => x.key === b.dataset.key);
        b.classList.toggle("oncourt", j.key === goalkeeper);
        b.setAttribute("aria-pressed", String(j.key === selected));
        b.setAttribute("aria-label", `${j.number} ${j.name}${j.goalkeeper ? ", arquero" : ""}${j.key === goalkeeper ? ", en cancha" : ""}`);
    }
    const sel = list.find(j => j.key === selected), ar = list.find(j => j.key === goalkeeper);
    $("sel").textContent = sel ? `${sel.name}${sel.number ? " (#" + sel.number + ")" : ""}` : "—";
    $("ar").textContent = ar ? `${ar.name}${ar.number ? " (#" + ar.number + ")" : ""}` : "elegir";
}

/** Contadores de los botones de acción: [span, función que da el texto con las acciones del partido]. */
let counters = null;

function renderActions(acc) {
    if (!counters) {
        counters = [];
        for (const p of PAIRS.filter(x => x.g === "g-arco")) $(p.g).append(par(p));
        for (const a of SIMPLE) {
            const b = el("button", "coach-act" + (a.dark ? " oscuro" : ""));
            b.type = "button";
            b.style.setProperty("--c", a.col);
            const c = el("span", "c");
            b.append(el("span", "t", a.t));
            if (a.d) b.append(el("span", "d", a.d));
            b.append(c);
            b.setAttribute("aria-label", a.t);
            b.addEventListener("click", () => load(a.kind, a.res));
            counters.push([c, l => `Partido: ${account(l, a.kind, a.res)}`]);
            $(a.g).append(b);
        }
        for (const p of PAIRS.filter(x => x.g !== "g-arco")) $(p.g).append(par(p));
    }
    for (const [span, text] of counters) span.textContent = text(acc);
}

const account = (acc, kind, res) => acc.filter(a => a.kind === kind && (res === undefined || a.outcome === res)).length;

function par(p) {
    const card = el("div", "coach-pair");
    const t = el("div", "t");
    const c = el("small");
    t.append(el("span", null, p.t), c);
    counters.push([c, l => `${account(l, p.kind, p.si[0])} / ${account(l, p.kind)}${p.whoName ? " · " + p.whoName : ""}`]);
    const btns = el("div", "btns");
    for (const [res, label, col] of [p.si, p.no]) {
        const b = el("button", null, label);
        b.type = "button";
        b.style.setProperty("--c", col);
        b.setAttribute("aria-label", `${p.t}: ${label}`);
        b.addEventListener("click", () => load(p.kind, res));
        btns.append(b);
    }
    card.append(t, btns);
    return card;
}

async function load(kind, outcome) {
    if (match.status === "CLOSED") { toast("El partido está cerrado: reabrilo para cargar acciones."); return; }
    const list = players();
    const key = GOAL_ACTIONS.has(kind) && goalkeeper ? goalkeeper : selected;
    const j = list.find(x => x.key === key);
    if (!j) { toast(GOAL_ACTIONS.has(kind) ? "Elegí el arquero en cancha o tocá un jugador." : "Tocá primero el jugador."); return; }
    const r = match.clock;
    const fresh = {id: Queue.uuid(), playerId: j.id, playerName: j.name, kind, outcome,
        period: match.status === "PENDING" ? 1 : r.period, atSecond: elapsed()};
    if (navigator.vibrate) navigator.vibrate(15);
    await Queue.enqueue(match.id, fresh);
    await refreshPending();
    toast(`${action(kind, outcome).name} · ${j.number ? j.number + " " : ""}${shortName(j.name)}`,
        () => deleteItem(fresh.id));
}

async function deleteItem(id) {
    await Queue.deleteItem(match.id, id);
    await refreshPending();
}

function renderLog(acc) {
    const ul = $("log");
    const latest = acc.slice().reverse().slice(0, 10);
    const signature = match.status + ":" + latest.map(a => a.id + (a.pendingItem ? "*" : "")).join(",");
    if (ul.dataset.signature === signature) return;
    ul.dataset.signature = signature;
    ul.replaceChildren();
    if (!latest.length) ul.append(el("li", "coach-vacio", "Todavía no hay acciones: tocá un jugador y después la acción."));
    const nums = new Map(players().map(j => [j.key, j.number]));
    for (const a of latest) {
        const li = el("li");
        const m = el("span", "m", `${a.period}T ${mmss(match.periodMinutes * 60 - a.atSecond)}`);
        const w = el("span", "w");
        const def = action(a.kind, a.outcome);
        const tag = el("span", "coach-tag");
        tag.style.background = def.col;
        w.append(tag, def.name);
        const n = nums.get(playerKey(a));
        w.append(el("small", null, `${n ? n + " " : ""}${a.playerName}${a.pendingItem ? " · sin enviar" : ""}`));
        const del = el("button", null, "Borrar");
        del.type = "button";
        del.setAttribute("aria-label", `Borrar ${def.name} de ${a.playerName}`);
        del.disabled = match.status === "CLOSED";
        del.addEventListener("click", () => deleteItem(a.id));
        li.append(m, w, del);
        ul.append(li);
    }
}

/**
 * Pestaña Informe: el informe del partido en vivo, por equipo o por jugador. Se arma en el teléfono porque incluye lo
 * que todavía no se envió (el PDF sale del servidor, con lo ya guardado).
 */
function renderSummary(acc) {
    const box = $("informe-vivo");
    const signature = [match.id, match.status, reportPlayer, acc.map(a => a.id + (a.pendingItem ? "*" : "")).join(",")].join("|");
    if (box.dataset.signature !== signature) {
        box.dataset.signature = signature;
        const p = Object.assign({}, match, {goalsFor: acc.filter(isGoalFor).length, goalsAgainst: acc.filter(isGoalAgainst).length});
        const list = players();
        const build = player => Report.build({title: `${coach.session.club.name} ${p.goalsFor} - ${p.goalsAgainst} ${p.opponent}`,
            detail: matchDetail(p), singleMatch: true, matches: [p], actions: acc, players: list, player});
        const inf = build(reportPlayer);
        Report.render(box, inf, {
            players: reportPlayer ? build(null).players : inf.players,
            onPlayer: k => { reportPlayer = k; renderSummary(actions()); },
            onPdf: () => {
                if (pendingOps.length) { toast("Esperá a que se envíe lo cargado: el PDF se arma con lo guardado en el servidor."); Queue.send(); return; }
                exportPdf(`/api/coach/matches/${match.id}/report.pdf` + (reportPlayer ? "?player=" + encodeURIComponent(reportPlayer) : ""));
            },
        });
    }

    const closing = $("cierre");
    closing.replaceChildren();
    if (match.status === "CLOSED") {
        closing.append(el("p", "coach-note", "El partido está cerrado. Durante 48 horas lo podés reabrir para corregir acciones."));
        const b = el("button", "coach-play ghost", "Reabrir partido");
        b.type = "button";
        b.addEventListener("click", reopen);
        closing.append(b);
    } else if (match.status !== "PENDING") {
        const b = el("button", "go", "Cerrar partido");
        b.type = "button";
        b.addEventListener("click", close);
        closing.append(b);
    }
}

// ---------- Wake Lock: la pantalla no se apaga mientras corre el reloj ----------

async function updateWakeLock() {
    const wants = match && match.clock.running && !$("v-partido").hidden;
    try {
        if (wants && !wakeLock && "wakeLock" in navigator && document.visibilityState === "visible") {
            wakeLock = await navigator.wakeLock.request("screen");
            wakeLock.addEventListener("release", () => { wakeLock = null; });
        } else if (!wants && wakeLock) {
            await wakeLock.release();
            wakeLock = null;
        }
    } catch (e) {
        wakeLock = null;   // el teléfono no lo permite: se sigue sin él
    }
}

// ---------- Pestañas ----------

function tab(id) {
    if (id === "t-partidos") { location.hash = "matches"; return; }
    for (const [t, v] of [["t-cargar", "p-cargar"], ["t-resumen", "p-resumen"]]) {
        $(t).setAttribute("aria-selected", String(t === id));
        $(v).hidden = t !== id;
    }
}

/** Con señal, vuelve a pedir el partido para que el reloj quede igual al del servidor. */
async function refresh() {
    if (!match || !navigator.onLine || document.visibilityState !== "visible") return;
    try { const r = await getJson(`/api/coach/matches/${match.id}`); receive(r.data, r.at); } catch (e) { /* sin señal */ }
}

/** Los controles de la carga (se llama una vez, al iniciar la página). */
export function start() {
    for (const t of ["t-cargar", "t-resumen", "t-partidos"]) $(t).addEventListener("click", () => tab(t));
    document.querySelectorAll("#m-ajuste button").forEach(b =>
        b.addEventListener("click", () => clock("ADJUST", Number(b.dataset.aj))));
    $("cambiar-ar").addEventListener("click", () => {
        // Pasa al siguiente arquero del plantel; sin arqueros marcados, el jugador seleccionado.
        const list = players(), ars = list.filter(j => j.goalkeeper);
        if (ars.length) {
            const i = ars.findIndex(j => j.key === goalkeeper);
            goalkeeper = ars[(i + 1) % ars.length].key;
        } else if (selected) {
            goalkeeper = selected;
        } else {
            toast("Tocá el jugador que ataja y después «Arquero en cancha».");
            return;
        }
        save("ar", goalkeeper);
        renderPlayers();
    });
    $("agregar-j").addEventListener("click", () => { $("nuevo-j").hidden = !$("nuevo-j").hidden; if (!$("nuevo-j").hidden) $("nj-num").focus(); });
    $("nuevo-j").addEventListener("submit", e => {
        e.preventDefault();
        const name = $("nj-nombre").value.trim().replace(/\s+/g, " ");
        if (!name) { $("nj-nombre").focus(); return; }
        const extras = read("extras", []);
        if (!extras.some(x => x.name === name)) extras.push({number: $("nj-num").value.trim(), name});
        save("extras", extras);
        selected = "m" + name;
        $("nj-num").value = $("nj-nombre").value = "";
        $("nuevo-j").hidden = true;
        renderPlayers();
    });
    window.addEventListener("online", () => { if (match) renderSync(); refresh(); });
    window.addEventListener("offline", () => { if (match) renderSync(); });
    document.addEventListener("visibilitychange", () => {
        if (document.visibilityState === "visible") { refresh(); updateWakeLock(); }
    });
    setInterval(renderClock, 250);
    setInterval(() => { if (match && !pendingOps.length) refresh(); }, 15000);
}
