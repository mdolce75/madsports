// Partido en vivo: el reloj, la carga de las acciones de cada jugador y el informe del partido. Las acciones pasan por
// la cola (cola.js), así se guardan aunque no haya señal; el reloj sí necesita conexión.
import {ARCO, PARES, SIMPLES, accion, clave, golAFavor, golEnContra} from "./acciones.js";
import * as Cola from "./cola.js";
import * as Informe from "./informe.js";
import {corto, detallePartido, dt, el, exportarPdf, getJson, mmss, nombreEquipo, toast} from "./comun.js";

// Partido abierto: lo que devolvió el servidor, cuándo (para el reloj), plantel, pendientes de la cola.
let partido = null, recibido = 0, plantel = [], pend = [], pendientesTotal = 0;
let seleccionado = null, arquero = null;
let jugadorInforme = null;   // jugador elegido en el informe del partido (null: todo el equipo)
let avisado = "";   // tiempo cuyo 00:00 ya se avisó
let wakeLock = null;

export const abierto = () => partido;

export async function abrir(id) {
    $("v-elegir").hidden = true;
    $("v-partido").hidden = false;
    document.body.classList.add("dt-vivo");
    tab("t-cargar");
    try {
        const r = await getJson(`/api/dt/partidos/${id}`);
        partido = r.data;
        recibido = r.at;
        pend = [];
        seleccionado = null;
        jugadorInforme = null;
        delete $("informe-vivo").dataset.firma;
        delete $("jugadores").dataset.firma;
        delete $("log").dataset.firma;
        avisado = "";
        try {
            plantel = (await getJson(`/api/dt/plantel?equipo=${r.data.equipoId}`)).data;
        } catch (e) {
            plantel = [];
        }
        plantel.sort((a, b) => (parseInt(a.numero) || 999) - (parseInt(b.numero) || 999));
        arquero = leer("ar", null) || (plantel.find(j => j.arquero) ? "j" + plantel.find(j => j.arquero).id : null);
        recibir(r.data, r.at);
        await refrescarPend();
        window.scrollTo(0, 0);
    } catch (err) {
        toast(err.status === 404 ? "No existe ese partido." : "No se pudo abrir el partido: " + err.message);
        location.hash = "partidos";
    }
}

/** Sale de la carga (otra vista del menú). */
export function salir() {
    partido = null;
    actualizarWakeLock();
    $("v-partido").hidden = true;
    document.body.classList.remove("dt-vivo");
}

/** Lo que devuelve el servidor (al abrir, al mover el reloj, al mandar acciones). */
function recibir(p, at) {
    if (partido && p.id !== partido.id) return;
    partido = p;
    recibido = at;
    render();
}

async function refrescarPend() {
    if (!partido) return;
    pend = await Cola.pendientes(partido.id);
    render();
}

/**
 * Aviso de la cola: lo que quedó sin enviar y el partido que devolvió el servidor, si hay. Devuelve false si no hay un
 * partido abierto (para que la lista de partidos se actualice).
 */
export async function alCambiarCola({partido: p, pendientes, error}) {
    pendientesTotal = pendientes;
    if (p && partido && p.id === partido.id) recibir(p, Date.now());
    if (error) toast(error);
    if (!partido) return false;
    await refrescarPend();
    return true;
}

// Lo guardado en el teléfono por partido: jugadores agregados a mano y el arquero en cancha.
function leer(k, def) {
    try { const v = JSON.parse(localStorage.getItem(`dt-${k}-${partido.id}`)); return v == null ? def : v; }
    catch (e) { return def; }
}
function guardar(k, v) {
    try { localStorage.setItem(`dt-${k}-${partido.id}`, JSON.stringify(v)); } catch (e) { /* sin almacenamiento */ }
}

/** Segundos jugados del tiempo, contando desde la última respuesta del servidor. */
function jugados() {
    if (!partido) return 0;
    const dur = partido.minutosTiempo * 60, r = partido.reloj;
    const extra = r.corriendo ? Math.floor((Date.now() - recibido) / 1000) : 0;
    return Math.max(0, Math.min(dur, r.jugados + extra));
}

/** Acciones activas: las del servidor sin las borradas en la cola, más las que todavía no se mandaron. */
function acciones() {
    const bajas = new Set(pend.filter(o => o.tipo === "baja").map(o => o.accionId));
    const lista = partido.acciones.filter(a => !bajas.has(a.id));
    const ids = new Set(lista.map(a => a.id));
    for (const o of pend) {
        // Una sin enviar que ya se borró (el borrado sale cuando termine de llegar) no se muestra.
        if (o.tipo === "alta" && !ids.has(o.accion.id) && !bajas.has(o.accion.id)) lista.push(Object.assign({pendiente: true}, o.accion));
    }
    return lista.sort((a, b) => a.tiempo - b.tiempo || a.segundo - b.segundo);
}

function jugadores() {
    const lista = plantel.map(j => ({key: "j" + j.id, id: j.id, numero: j.numero, nombre: j.nombre, arquero: j.arquero}));
    const extras = leer("extras", []);
    for (const x of extras) lista.push({key: "m" + x.nombre, id: 0, numero: x.numero, nombre: x.nombre, arquero: false});
    for (const a of partido.acciones) {
        if (a.jugadorId === 0 && !lista.some(j => j.key === "m" + a.jugadorNombre)) {
            lista.push({key: "m" + a.jugadorNombre, id: 0, numero: "", nombre: a.jugadorNombre, arquero: false});
        }
    }
    return lista;
}

function render() {
    if (!partido) return;
    $("m-propio").textContent = dt.sesion.club.nombre;
    $("m-propio-sub").textContent = nombreEquipo(partido);
    $("m-rival").textContent = partido.rival;
    $("m-rival-sub").textContent = [partido.local ? "Local" : "Visitante", partido.torneo].filter(Boolean).join(" · ");
    const acc = acciones();
    $("m-gf").textContent = acc.filter(golAFavor).length;
    $("m-gc").textContent = acc.filter(golEnContra).length;
    renderReloj();
    renderBotones();
    renderSync();
    renderJugadores();
    renderAcciones(acc);
    renderLog(acc);
    renderResumen(acc);
}

function renderReloj() {
    if (!partido) return;
    const dur = partido.minutosTiempo * 60, j = jugados();
    const e = partido.estado;
    $("m-tiempo").textContent = e === "PENDIENTE" ? "1T" : e === "ENTRETIEMPO" ? "ET"
        : e === "TERMINADO" || e === "CERRADO" ? "FIN" : partido.reloj.tiempo + "T";
    $("m-reloj").textContent = mmss(dur - j);
    $("m-reloj").classList.toggle("on", partido.reloj.corriendo);
    const fin = e === "EN_JUEGO" && j >= dur;
    const k = partido.id + ":" + partido.reloj.tiempo;
    if (fin && avisado !== k) {
        avisado = k;
        if (navigator.vibrate) navigator.vibrate([400, 150, 400]);
        aviso(`Terminó el ${partido.reloj.tiempo}T: tocá «${partido.reloj.tiempo === 1 ? "Fin del 1T" : "Fin del partido"}».`);
        renderBotones();
    } else if (!fin && $("m-aviso").dataset.tipo === "fin") {
        aviso("");
    }
}

function aviso(txt) {
    $("m-aviso").hidden = !txt;
    $("m-aviso").textContent = txt;
    $("m-aviso").dataset.tipo = txt ? "fin" : "";
}

function renderBotones() {
    const box = $("m-botones");
    box.replaceChildren();
    const r = partido.reloj, e = partido.estado;
    const boton = (txt, accionReloj, cls, confirmar) => {
        const b = el("button", "dt-play " + (cls || ""), txt);
        b.type = "button";
        b.addEventListener("click", () => {
            if (confirmar && !confirm(confirmar)) return;
            reloj(accionReloj);
        });
        box.append(b);
    };
    if (e === "PENDIENTE") boton("Iniciar partido", "INICIAR");
    if (e === "EN_JUEGO") {
        const queda = jugados() < partido.minutosTiempo * 60;
        if (r.corriendo && queda) boton("Pausar", "PAUSAR", "on");
        else if (!r.corriendo && queda) boton("Reanudar", "REANUDAR");
        boton(r.tiempo === 1 ? "Fin del 1T" : "Fin del partido", "FIN_TIEMPO", "ghost",
            r.tiempo === 1 ? "¿Terminar el primer tiempo?" : "¿Terminar el partido?");
    }
    if (e === "ENTRETIEMPO") boton("Iniciar 2T", "INICIAR");
    if (e === "TERMINADO") {
        const b = el("button", "dt-play ghost", "Cerrar partido");
        b.type = "button";
        b.addEventListener("click", cerrar);
        box.append(b);
    }
    $("m-ajuste").hidden = e !== "EN_JUEGO";
    actualizarWakeLock();
}

/**
 * POST del partido abierto. "Sin señal" solo si el pedido no llegó al servidor (fetch falla con TypeError); un error
 * del servidor muestra su detalle. Lo que pasa después (pintar la respuesta) queda fuera del try: un error de la
 * pantalla no se confunde con falta de señal.
 */
async function enviarPartido(ruta, body, sinSenal) {
    let p;
    try {
        p = await postJson(`/api/dt/partidos/${partido.id}/${ruta}`, body);
    } catch (err) {
        toast(err instanceof TypeError ? sinSenal : err.message);
        return;
    }
    const antes = partido.estado;
    recibir(p, Date.now());
    // Al terminar el partido (fin del 2T), directo al informe.
    if (antes === "EN_JUEGO" && p.estado === "TERMINADO") {
        tab("t-resumen");
        window.scrollTo(0, 0);
    }
}

function reloj(accionReloj, segundos) {
    return enviarPartido("reloj", {accion: accionReloj, segundos},
        "Sin señal: el reloj necesita conexión. Las acciones se siguen guardando.");
}

async function cerrar() {
    if (!confirm("¿Cerrar el partido? Ya no se podrán cargar acciones (se puede reabrir durante 48 horas).")) return;
    if (pend.length) { toast("Esperá a que se envíe lo cargado antes de cerrar."); Cola.enviar(); return; }
    await enviarPartido("cerrar", {}, "Sin señal: para cerrar el partido hace falta conexión.");
}

function reabrir() {
    return enviarPartido("reabrir", {}, "Sin señal: para reabrir hace falta conexión.");
}

function renderSync() {
    const n = pendientesTotal;
    const s = $("m-sync");
    s.className = "dt-sync" + (n ? " pend" : "") + (navigator.onLine ? "" : " off");
    s.textContent = (navigator.onLine ? "" : "Sin señal · ") + (n ? `Sin enviar: ${n}` : navigator.onLine ? "Todo enviado" : "nada pendiente");
}

// Los botones se arman una sola vez (o cuando cambia el plantel) y después solo se actualizan: si se rearmaran con cada
// respuesta del servidor, un toque justo en ese momento se perdería.
function renderJugadores() {
    const box = $("jugadores");
    const lista = jugadores();
    const firma = lista.map(j => `${j.key}|${j.numero}|${j.arquero}`).join(";");
    if (box.dataset.firma !== firma) {
        box.dataset.firma = firma;
        box.replaceChildren();
        if (!lista.length) {
            const p = el("p", "dt-note");
            const a = el("a", null, "Equipo");
            a.href = "#equipo";
            p.append("Este equipo no tiene jugadores: cargalos en ", a, ", o agregá uno solo para este partido.");
            box.append(p);
        }
        for (const j of lista) {
            const b = el("button", "dt-player" + (j.arquero ? " gk" : ""));
            b.type = "button";
            b.dataset.key = j.key;
            // Lo principal es el nombre (completo, en una o dos líneas); el número va chico, arriba.
            const arriba = el("span", "cab");
            arriba.append(el("span", "n", j.numero ? "#" + j.numero : "s/n"));
            if (j.arquero) arriba.append(el("span", "ar", "Arquero"));
            b.append(arriba, el("span", "s", j.nombre));
            b.addEventListener("click", () => { seleccionado = j.key; renderJugadores(); });
            box.append(b);
        }
    }
    for (const b of box.querySelectorAll(".dt-player")) {
        const j = lista.find(x => x.key === b.dataset.key);
        b.classList.toggle("oncourt", j.key === arquero);
        b.setAttribute("aria-pressed", String(j.key === seleccionado));
        b.setAttribute("aria-label", `${j.numero} ${j.nombre}${j.arquero ? ", arquero" : ""}${j.key === arquero ? ", en cancha" : ""}`);
    }
    const sel = lista.find(j => j.key === seleccionado), ar = lista.find(j => j.key === arquero);
    $("sel").textContent = sel ? `${sel.nombre}${sel.numero ? " (#" + sel.numero + ")" : ""}` : "—";
    $("ar").textContent = ar ? `${ar.nombre}${ar.numero ? " (#" + ar.numero + ")" : ""}` : "elegir";
}

/** Contadores de los botones de acción: [span, función que da el texto con las acciones del partido]. */
let contadores = null;

function renderAcciones(acc) {
    if (!contadores) {
        contadores = [];
        for (const p of PARES.filter(x => x.g === "g-arco")) $(p.g).append(par(p));
        for (const a of SIMPLES) {
            const b = el("button", "dt-act" + (a.oscuro ? " oscuro" : ""));
            b.type = "button";
            b.style.setProperty("--c", a.col);
            const c = el("span", "c");
            b.append(el("span", "t", a.t));
            if (a.d) b.append(el("span", "d", a.d));
            b.append(c);
            b.setAttribute("aria-label", a.t);
            b.addEventListener("click", () => cargar(a.tipo, a.res));
            contadores.push([c, l => `Partido: ${cuenta(l, a.tipo, a.res)}`]);
            $(a.g).append(b);
        }
        for (const p of PARES.filter(x => x.g !== "g-arco")) $(p.g).append(par(p));
    }
    for (const [span, texto] of contadores) span.textContent = texto(acc);
}

const cuenta = (acc, tipo, res) => acc.filter(a => a.tipo === tipo && (res === undefined || a.resultado === res)).length;

function par(p) {
    const card = el("div", "dt-pair");
    const t = el("div", "t");
    const c = el("small");
    t.append(el("span", null, p.t), c);
    contadores.push([c, l => `${cuenta(l, p.tipo, p.si[0])} / ${cuenta(l, p.tipo)}${p.quien ? " · " + p.quien : ""}`]);
    const btns = el("div", "btns");
    for (const [res, label, col] of [p.si, p.no]) {
        const b = el("button", null, label);
        b.type = "button";
        b.style.setProperty("--c", col);
        b.setAttribute("aria-label", `${p.t}: ${label}`);
        b.addEventListener("click", () => cargar(p.tipo, res));
        btns.append(b);
    }
    card.append(t, btns);
    return card;
}

async function cargar(tipo, resultado) {
    if (partido.estado === "CERRADO") { toast("El partido está cerrado: reabrilo para cargar acciones."); return; }
    const lista = jugadores();
    const key = ARCO.has(tipo) && arquero ? arquero : seleccionado;
    const j = lista.find(x => x.key === key);
    if (!j) { toast(ARCO.has(tipo) ? "Elegí el arquero en cancha o tocá un jugador." : "Tocá primero el jugador."); return; }
    const r = partido.reloj;
    const nueva = {id: Cola.uuid(), jugadorId: j.id, jugadorNombre: j.nombre, tipo, resultado,
        tiempo: partido.estado === "PENDIENTE" ? 1 : r.tiempo, segundo: jugados()};
    if (navigator.vibrate) navigator.vibrate(15);
    await Cola.agregar(partido.id, nueva);
    await refrescarPend();
    toast(`${accion(tipo, resultado).nombre} · ${j.numero ? j.numero + " " : ""}${corto(j.nombre)}`,
        () => borrar(nueva.id));
}

async function borrar(id) {
    await Cola.borrar(partido.id, id);
    await refrescarPend();
}

function renderLog(acc) {
    const ul = $("log");
    const ultimas = acc.slice().reverse().slice(0, 10);
    const firma = partido.estado + ":" + ultimas.map(a => a.id + (a.pendiente ? "*" : "")).join(",");
    if (ul.dataset.firma === firma) return;
    ul.dataset.firma = firma;
    ul.replaceChildren();
    if (!ultimas.length) ul.append(el("li", "dt-vacio", "Todavía no hay acciones: tocá un jugador y después la acción."));
    const nums = new Map(jugadores().map(j => [j.key, j.numero]));
    for (const a of ultimas) {
        const li = el("li");
        const m = el("span", "m", `${a.tiempo}T ${mmss(partido.minutosTiempo * 60 - a.segundo)}`);
        const w = el("span", "w");
        const def = accion(a.tipo, a.resultado);
        const tag = el("span", "dt-tag");
        tag.style.background = def.col;
        w.append(tag, def.nombre);
        const n = nums.get(clave(a));
        w.append(el("small", null, `${n ? n + " " : ""}${a.jugadorNombre}${a.pendiente ? " · sin enviar" : ""}`));
        const del = el("button", null, "Borrar");
        del.type = "button";
        del.setAttribute("aria-label", `Borrar ${def.nombre} de ${a.jugadorNombre}`);
        del.disabled = partido.estado === "CERRADO";
        del.addEventListener("click", () => borrar(a.id));
        li.append(m, w, del);
        ul.append(li);
    }
}

/**
 * Pestaña Informe: el informe del partido en vivo, por equipo o por jugador. Se arma en el teléfono porque incluye lo
 * que todavía no se envió (el PDF sale del servidor, con lo ya guardado).
 */
function renderResumen(acc) {
    const box = $("informe-vivo");
    const firma = [partido.id, partido.estado, jugadorInforme, acc.map(a => a.id + (a.pendiente ? "*" : "")).join(",")].join("|");
    if (box.dataset.firma !== firma) {
        box.dataset.firma = firma;
        const p = Object.assign({}, partido, {golesFavor: acc.filter(golAFavor).length, golesContra: acc.filter(golEnContra).length});
        const lista = jugadores();
        const armar = jugador => Informe.armar({titulo: `${dt.sesion.club.nombre} ${p.golesFavor} - ${p.golesContra} ${p.rival}`,
            detalle: detallePartido(p), unPartido: true, partidos: [p], acciones: acc, jugadores: lista, jugador});
        const inf = armar(jugadorInforme);
        Informe.render(box, inf, {
            jugadores: jugadorInforme ? armar(null).jugadores : inf.jugadores,
            onJugador: k => { jugadorInforme = k; renderResumen(acciones()); },
            onPdf: () => {
                if (pend.length) { toast("Esperá a que se envíe lo cargado: el PDF se arma con lo guardado en el servidor."); Cola.enviar(); return; }
                exportarPdf(`/api/dt/partidos/${partido.id}/informe.pdf` + (jugadorInforme ? "?jugador=" + encodeURIComponent(jugadorInforme) : ""));
            },
        });
    }

    const cierre = $("cierre");
    cierre.replaceChildren();
    if (partido.estado === "CERRADO") {
        cierre.append(el("p", "dt-note", "El partido está cerrado. Durante 48 horas lo podés reabrir para corregir acciones."));
        const b = el("button", "dt-play ghost", "Reabrir partido");
        b.type = "button";
        b.addEventListener("click", reabrir);
        cierre.append(b);
    } else if (partido.estado !== "PENDIENTE") {
        const b = el("button", "go", "Cerrar partido");
        b.type = "button";
        b.addEventListener("click", cerrar);
        cierre.append(b);
    }
}

// ---------- Wake Lock: la pantalla no se apaga mientras corre el reloj ----------

async function actualizarWakeLock() {
    const quiere = partido && partido.reloj.corriendo && !$("v-partido").hidden;
    try {
        if (quiere && !wakeLock && "wakeLock" in navigator && document.visibilityState === "visible") {
            wakeLock = await navigator.wakeLock.request("screen");
            wakeLock.addEventListener("release", () => { wakeLock = null; });
        } else if (!quiere && wakeLock) {
            await wakeLock.release();
            wakeLock = null;
        }
    } catch (e) {
        wakeLock = null;   // el teléfono no lo permite: se sigue sin él
    }
}

// ---------- Pestañas ----------

function tab(id) {
    if (id === "t-partidos") { location.hash = "partidos"; return; }
    for (const [t, v] of [["t-cargar", "p-cargar"], ["t-resumen", "p-resumen"]]) {
        $(t).setAttribute("aria-selected", String(t === id));
        $(v).hidden = t !== id;
    }
}

/** Con señal, vuelve a pedir el partido para que el reloj quede igual al del servidor. */
async function refrescar() {
    if (!partido || !navigator.onLine || document.visibilityState !== "visible") return;
    try { const r = await getJson(`/api/dt/partidos/${partido.id}`); recibir(r.data, r.at); } catch (e) { /* sin señal */ }
}

/** Los controles de la carga (se llama una vez, al iniciar la página). */
export function iniciar() {
    for (const t of ["t-cargar", "t-resumen", "t-partidos"]) $(t).addEventListener("click", () => tab(t));
    document.querySelectorAll("#m-ajuste button").forEach(b =>
        b.addEventListener("click", () => reloj("AJUSTAR", Number(b.dataset.aj))));
    $("cambiar-ar").addEventListener("click", () => {
        // Pasa al siguiente arquero del plantel; sin arqueros marcados, el jugador seleccionado.
        const lista = jugadores(), ars = lista.filter(j => j.arquero);
        if (ars.length) {
            const i = ars.findIndex(j => j.key === arquero);
            arquero = ars[(i + 1) % ars.length].key;
        } else if (seleccionado) {
            arquero = seleccionado;
        } else {
            toast("Tocá el jugador que ataja y después «Arquero en cancha».");
            return;
        }
        guardar("ar", arquero);
        renderJugadores();
    });
    $("agregar-j").addEventListener("click", () => { $("nuevo-j").hidden = !$("nuevo-j").hidden; if (!$("nuevo-j").hidden) $("nj-num").focus(); });
    $("nuevo-j").addEventListener("submit", e => {
        e.preventDefault();
        const nombre = $("nj-nombre").value.trim().replace(/\s+/g, " ");
        if (!nombre) { $("nj-nombre").focus(); return; }
        const extras = leer("extras", []);
        if (!extras.some(x => x.nombre === nombre)) extras.push({numero: $("nj-num").value.trim(), nombre});
        guardar("extras", extras);
        seleccionado = "m" + nombre;
        $("nj-num").value = $("nj-nombre").value = "";
        $("nuevo-j").hidden = true;
        renderJugadores();
    });
    window.addEventListener("online", () => { if (partido) renderSync(); refrescar(); });
    window.addEventListener("offline", () => { if (partido) renderSync(); });
    document.addEventListener("visibilitychange", () => {
        if (document.visibilityState === "visible") { refrescar(); actualizarWakeLock(); }
    });
    setInterval(renderReloj, 250);
    setInterval(() => { if (partido && !pend.length) refrescar(); }, 15000);
}
