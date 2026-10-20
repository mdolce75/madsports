// Equipo: ABM de los equipos del DT (con su balance) y de sus jugadores (asignados a uno o varios equipos). Un equipo
// se arma desde una categoría del club en SIDGAD: su plantel viene tildado y el DT destilda a los que no van, que
// quedan en la lista como "no incluidos" para sumarlos después.
import {abm, dt, el, getJson, traerEquipos} from "./comun.js";

let eqEditando = null;   // id del equipo que se está renombrando (null: alta)
let plEditando = null;   // id del jugador que se está editando (null: alta)
let jugadoresDt = [];
let categorias = null;   // categorías del club en SIDGAD ([{clave, rama, nombre}]); null: todavía no se pidieron
let plantelSidgad = null;   // el plantel de SIDGAD de la categoría elegida (null: no hay o se está leyendo)
let nombreAuto = "";   // el nombre que se completó solo con la categoría (si el DT no lo cambió, se reemplaza)
const OTRO = "otro";

export async function cargar() {
    if (categorias === null) cargarCategorias();
    try {
        const [, js] = await Promise.all([traerEquipos(true), getJson("/api/dt/jugadores").then(r => r.data)]);
        jugadoresDt = js;
        pintarEquipos();
        pintarJugadores();
    } catch (err) {
        $("eq-lista").replaceChildren(el("li", "dt-vacio", "No se pudieron cargar los equipos: " + err.message));
    }
}

// ---------- Equipo desde una categoría del club ----------

/** Las categorías del club en SIDGAD para el combo; sin señal o sin SIDGAD, solo "Otro" (nombre libre). */
async function cargarCategorias() {
    const sel = $("eq-categoria");
    try {
        categorias = (await getJson("/api/dt/club/categorias")).data;
    } catch (err) {
        categorias = [];
    }
    const elegir = el("option", null, categorias.length ? "Elegí la categoría" : "No se pudieron leer las categorías de SIDGAD");
    elegir.value = "";
    const otro = el("option", null, "Otro: nombre libre, sin jugadores de SIDGAD");
    otro.value = OTRO;
    sel.replaceChildren(elegir, ...categorias.map(c => { const o = el("option", null, c.nombre); o.value = c.clave + "|" + c.rama; return o; }), otro);
    if (!categorias.length) sel.value = OTRO;
    pintarEquipos();
}

const categoriaElegida = () => {
    const v = $("eq-categoria").value;
    return categorias && categorias.find(c => c.clave + "|" + c.rama === v) || null;
};

/** Al elegir la categoría: su nombre (si el DT no escribió otro) y su plantel de SIDGAD, todo tildado. */
async function alElegirCategoria() {
    const c = categoriaElegida();
    const nombre = $("eq-nombre");
    if (!nombre.value.trim() || nombre.value === nombreAuto) {
        nombre.value = c ? c.nombre : "";
        nombreAuto = nombre.value;
    }
    plantelSidgad = null;
    const box = $("eq-jugadores");
    $("eq-sidgad").hidden = !c;
    if (!c) { box.replaceChildren(); return; }
    box.replaceChildren(el("p", "dt-vacio", "Leyendo las actas del equipo en SIDGAD… (puede tardar unos segundos)"));
    $("eq-sidgad-n").textContent = "";
    try {
        const lista = (await getJson(`/api/dt/club/plantel?categoria=${encodeURIComponent(c.clave)}&rama=${c.rama}`)).data;
        if (categoriaElegida() !== c) return;   // cambió la categoría mientras se leía
        plantelSidgad = lista;
        box.replaceChildren();
        if (!lista.length) box.append(el("p", "dt-vacio", "El equipo no tiene actas publicadas todavía: el equipo se crea sin jugadores."));
        for (const j of lista) {
            const l = el("label", "eq-jug");
            const cb = el("input");
            cb.type = "checkbox";
            cb.value = String(j.id);
            cb.checked = true;
            cb.addEventListener("change", contarSidgad);
            const quien = el("span", "quien", j.nombre);
            l.append(cb, el("span", "pl-num", j.numero ? "#" + j.numero : "s/n"), quien);
            if (j.arquero) l.append(el("span", "inf-ar", "Arquero"));
            box.append(l);
        }
        contarSidgad();
    } catch (err) {
        box.replaceChildren(el("p", "dt-vacio", "No se pudo leer el plantel en SIDGAD: " + err.message));
    }
}

function contarSidgad() {
    const todos = [...document.querySelectorAll("#eq-jugadores input")];
    $("eq-sidgad-n").textContent = todos.length ? `${todos.filter(c => c.checked).length} de ${todos.length} incluidos` : "";
}

/** ¿Se está escribiendo en un formulario del ABM? (para no pisarlo al refrescar) */
export const editando = () => eqEditando != null || plEditando != null
    || ["eq-nombre", "pl-nombre", "pl-apellido", "pl-numero"].some(id => $(id).value.trim());

function pintarEquipos() {
    const equipos = dt.equipos;
    const ul = $("eq-lista");
    // Cuántos equipos usa de los que permite su plan (free 1, mensual 3).
    const max = dt.sesion.suscripcion && dt.sesion.suscripcion.equiposMax;
    $("eq-n").textContent = max ? `${equipos.length} de ${max} ${max === 1 ? "equipo" : "equipos"}`
        : equipos.length ? `${equipos.length} ${equipos.length === 1 ? "equipo" : "equipos"}` : "";
    const lleno = max && equipos.length >= max && eqEditando == null;
    $("eq-nombre").disabled = $("eq-guardar").disabled = !!lleno;
    if (lleno && !$("eq-status").textContent) abmStatus("eq-status", `Tu plan permite ${max === 1 ? "1 equipo" : max + " equipos"}: `
        + "para sumar otro, borrá uno sin partidos" + (max === 1 ? " o pasate al plan mensual." : "."));
    ul.replaceChildren();
    if (!equipos.length) ul.append(el("li", "dt-vacio", "Todavía no creaste equipos: agregá el primero."));
    for (const e of equipos) {
        const li = el("li", "eq-fila");
        li.classList.toggle("editando", e.id === eqEditando);
        const quien = el("div", "quien");
        quien.append(el("b", null, e.nombre));
        if (e.categoriaSidgad) {
            const c = (categorias || []).find(x => x.clave === e.categoriaSidgad && x.rama === e.ramaSidgad);
            quien.append(el("small", "eq-sidgad-cat", "SIDGAD: " + (c ? c.nombre : e.categoriaSidgad)));
        }
        const bal = el("div", "eq-balance");
        for (const [v, n, t] of [[e.jugados, "PJ", "Jugados"], [e.ganados, "G", "Ganados"], [e.empatados, "E", "Empatados"],
            [e.perdidos, "P", "Perdidos"]]) {
            const c = el("span", "eq-dato");
            c.title = t;
            c.append(el("b", null, String(v)), el("small", null, n));
            bal.append(c);
        }
        const acc = el("div", "acc");
        const editar = el("button", "dt-link", "Editar");
        editar.type = "button";
        editar.setAttribute("aria-label", "Cambiar el nombre de " + e.nombre);
        editar.addEventListener("click", () => eqFormulario(e));
        const borrar = el("button", "dt-link pl-baja", "Borrar");
        borrar.type = "button";
        borrar.setAttribute("aria-label", "Borrar el equipo " + e.nombre);
        borrar.addEventListener("click", () => eqBorrar(e));
        acc.append(editar, borrar);
        li.append(quien, bal, acc);
        ul.append(li);
    }
    // Equipos en el formulario de jugadores y en el filtro de la lista.
    $("pl-sin-equipos").hidden = equipos.length > 0;
    for (const id of ["pl-nombre", "pl-apellido", "pl-numero", "pl-arquero", "pl-guardar"]) $(id).disabled = !equipos.length;
    const marcados = new Set([...document.querySelectorAll("#pl-equipos input:checked")].map(i => i.value));
    $("pl-equipos").replaceChildren(...equipos.map(e => {
        const l = el("label", "pl-eq");
        const c = el("input");
        c.type = "checkbox";
        c.value = String(e.id);
        c.checked = marcados.has(c.value) || equipos.length === 1;
        l.append(c, " " + e.nombre);
        return l;
    }));
    const f = $("pl-filtro"), antes = f.value;
    const todos = el("option", null, "Todos los equipos");
    todos.value = "";
    f.replaceChildren(todos, ...equipos.map(e => { const o = el("option", null, e.nombre); o.value = String(e.id); return o; }));
    f.value = equipos.some(e => String(e.id) === antes) ? antes : "";
    f.hidden = equipos.length < 2;
}

function pintarJugadores() {
    const ul = $("pl-lista");
    const filtro = $("pl-filtro").value;
    const lista = jugadoresDt.filter(j => !filtro || j.equipos.map(String).includes(filtro)
        || (j.pendientes || []).map(String).includes(filtro));
    $("pl-n").textContent = jugadoresDt.length ? `${jugadoresDt.length} ${jugadoresDt.length === 1 ? "jugador" : "jugadores"}` : "";
    ul.replaceChildren();
    if (!lista.length) ul.append(el("li", "dt-vacio", jugadoresDt.length ? "No hay jugadores en ese equipo."
        : "Todavía no hay jugadores: agregá el primero."));
    const nombres = new Map(dt.equipos.map(e => [e.id, e.nombre]));
    for (const j of lista) {
        const li = el("li");
        li.classList.toggle("editando", j.id === plEditando);
        const pendientes = (j.pendientes || []).filter(id => nombres.has(id));
        li.classList.toggle("pendiente", !!filtro && pendientes.map(String).includes(filtro));
        const quien = el("div", "quien");
        quien.append(el("b", null, j.nombre));
        if (j.arquero) quien.append(el("span", "inf-ar", "Arquero"));
        if (li.classList.contains("pendiente")) quien.append(el("span", "pl-tag-pend", "No incluido"));
        const de = [...j.equipos.map(id => nombres.get(id)).filter(Boolean),
            ...pendientes.map(id => `no incluido en ${nombres.get(id)}`)];
        quien.append(el("small", "pl-de", de.join(" · ")));
        const editar = el("button", "dt-link", "Editar");
        editar.type = "button";
        editar.setAttribute("aria-label", "Editar a " + j.nombre);
        editar.addEventListener("click", () => plFormulario(j));
        const baja = el("button", "dt-link pl-baja", "Dar de baja");
        baja.type = "button";
        baja.setAttribute("aria-label", "Dar de baja a " + j.nombre);
        baja.addEventListener("click", () => plBaja(j));
        const acc = el("div", "acc");
        // Sumarlo a los equipos donde quedó "no incluido" (con filtro, solo al equipo filtrado).
        for (const id of pendientes.filter(id => !filtro || String(id) === filtro)) {
            const inc = el("button", "dt-link", filtro ? "Incluir" : "Incluir en " + nombres.get(id));
            inc.type = "button";
            inc.setAttribute("aria-label", `Incluir a ${j.nombre} en ${nombres.get(id)}`);
            inc.addEventListener("click", () => plIncluir(j, id, nombres.get(id)));
            acc.append(inc);
        }
        acc.append(editar, baja);
        li.append(el("span", "pl-num", j.numero ? "#" + j.numero : "s/n"), quien, acc);
        ul.append(li);
    }
}

function abmStatus(id, txt, error) {
    $(id).textContent = txt;
    $(id).className = "abm-status" + (txt ? (error ? " error" : " ok") : "");
}

const sinSenal = err => err instanceof TypeError ? "Sin señal: para editar los equipos hace falta conexión." : err.message;

function eqFormulario(e) {
    eqEditando = e ? e.id : null;
    // Renombrar no cambia el plantel: la categoría y los jugadores de SIDGAD son solo para el alta.
    $("eq-categoria-campo").hidden = !!e;
    $("eq-sidgad").hidden = !!e || !categoriaElegida();
    $("eq-titulo").textContent = e ? "Cambiar nombre" : "Nuevo equipo";
    $("eq-guardar").textContent = e ? "Guardar cambios" : "Agregar equipo";
    $("eq-cancelar").hidden = !e;
    $("eq-nombre").value = e ? e.nombre : "";
    abmStatus("eq-status", "");
    pintarEquipos();
    if (e) $("eq-nombre").focus();
}

async function eqGuardar(ev) {
    ev.preventDefault();
    const nombre = $("eq-nombre").value.trim();
    const id = eqEditando;
    const cat = id ? null : categoriaElegida();
    if (!id && !cat && $("eq-categoria").value !== OTRO) {
        abmStatus("eq-status", "Elegí la categoría de tu club (u «Otro», para un equipo sin jugadores de SIDGAD).", true);
        $("eq-categoria").focus();
        return;
    }
    if (cat && plantelSidgad === null) { abmStatus("eq-status", "Esperá a que se lea el plantel en SIDGAD.", true); return; }
    if (!nombre) { abmStatus("eq-status", "Escribí el nombre del equipo.", true); $("eq-nombre").focus(); return; }
    const excluidos = [...document.querySelectorAll("#eq-jugadores input:not(:checked)")].map(c => Number(c.value));
    const body = cat ? {nombre, categoria: cat.clave, rama: cat.rama, excluidos} : {nombre};
    $("eq-guardar").disabled = true;
    try {
        const data = await abm(id ? "PUT" : "POST", id ? `/api/dt/equipos/${id}` : "/api/dt/equipos", body);
        const total = cat ? plantelSidgad.length : 0;
        eqFormulario(null);
        $("eq-categoria").value = "";
        alElegirCategoria();
        abmStatus("eq-status", id ? `Guardado: ${data.nombre}.`
            : total ? `Equipo creado: ${data.nombre}, con ${total - excluidos.length} de los ${total} jugadores de SIDGAD`
                + (excluidos.length === 1 ? " (1 no incluido: está en la lista para sumarlo después)."
                    : excluidos.length ? ` (${excluidos.length} no incluidos: están en la lista para sumarlos después).` : ".")
            : `Equipo creado: ${data.nombre}. Ahora cargá sus jugadores.`);
        await cargar();
        // El equipo nuevo queda elegido en la lista de jugadores: se ven sus jugadores y los no incluidos.
        if (!id) { $("pl-filtro").value = String(data.id); pintarJugadores(); }
    } catch (err) {
        abmStatus("eq-status", sinSenal(err), true);
    } finally {
        $("eq-guardar").disabled = $("eq-nombre").disabled;   // con el cupo de equipos lleno, sigue deshabilitado
    }
}

async function eqBorrar(e) {
    if (!confirm(`¿Borrar el equipo ${e.nombre}? Sus jugadores siguen en tus otros equipos.`)) return;
    try {
        await abm("DELETE", `/api/dt/equipos/${e.id}`);
        if (eqEditando === e.id) eqFormulario(null);
        abmStatus("eq-status", `${e.nombre} se borró.`);
        await cargar();
    } catch (err) {
        abmStatus("eq-status", sinSenal(err), true);
    }
}

/** El formulario de jugadores en modo alta (j null) o para editar a j. */
function plFormulario(j) {
    plEditando = j ? j.id : null;
    $("pl-titulo").textContent = j ? "Editar jugador" : "Nuevo jugador";
    $("pl-guardar").textContent = j ? "Guardar cambios" : "Agregar jugador";
    $("pl-cancelar").hidden = !j;
    $("pl-nombre").value = j ? j.nombres : "";
    $("pl-apellido").value = j ? j.apellido : "";
    $("pl-numero").value = j ? j.numero : "";
    $("pl-arquero").checked = j ? j.arquero : false;
    document.querySelectorAll("#pl-equipos input").forEach(c => {
        c.checked = j ? j.equipos.map(String).includes(c.value) : dt.equipos.length === 1;
    });
    abmStatus("pl-status", "");
    pintarJugadores();
    if (j) $("pl-nombre").focus();
}

async function plGuardar(ev) {
    ev.preventDefault();
    const body = {nombre: $("pl-nombre").value, apellido: $("pl-apellido").value, numero: $("pl-numero").value,
        arquero: $("pl-arquero").checked,
        equipos: [...document.querySelectorAll("#pl-equipos input:checked")].map(c => Number(c.value))};
    if (!body.nombre.trim()) { abmStatus("pl-status", "Escribí el nombre del jugador.", true); $("pl-nombre").focus(); return; }
    if (!body.apellido.trim()) { abmStatus("pl-status", "Escribí el apellido del jugador.", true); $("pl-apellido").focus(); return; }
    if (!body.equipos.length) { abmStatus("pl-status", "Asigná el jugador a un equipo.", true); return; }
    $("pl-guardar").disabled = true;
    try {
        const id = plEditando;
        const data = await abm(id ? "PUT" : "POST", id ? `/api/dt/jugadores/${id}` : "/api/dt/jugadores", body);
        plFormulario(null);
        abmStatus("pl-status", id ? `Guardado: ${data.nombre}.` : `Agregado: ${data.nombre}.`);
        $("pl-nombre").focus();
        await cargar();
    } catch (err) {
        abmStatus("pl-status", sinSenal(err), true);
    } finally {
        $("pl-guardar").disabled = !dt.equipos.length;
    }
}

/** Suma al equipo a un jugador que había quedado "no incluido". */
async function plIncluir(j, equipoId, equipo) {
    try {
        await abm("POST", `/api/dt/jugadores/${j.id}/equipos/${equipoId}`);
        abmStatus("pl-status", `${j.nombre} quedó incluido en ${equipo}.`);
        await cargar();
    } catch (err) {
        abmStatus("pl-status", sinSenal(err), true);
    }
}

async function plBaja(j) {
    if (!confirm(`¿Dar de baja a ${j.nombre}? Sale de sus equipos; sus acciones quedan en los partidos y los informes.`)) return;
    try {
        await abm("DELETE", `/api/dt/jugadores/${j.id}`);
        if (plEditando === j.id) plFormulario(null);
        abmStatus("pl-status", `${j.nombre} salió del plantel.`);
        await cargar();
    } catch (err) {
        abmStatus("pl-status", sinSenal(err), true);
    }
}

export function iniciar() {
    $("eq-categoria").addEventListener("change", alElegirCategoria);
    $("pl-form").addEventListener("submit", plGuardar);
    $("eq-form").addEventListener("submit", eqGuardar);
    $("eq-cancelar").addEventListener("click", () => eqFormulario(null));
    $("pl-filtro").addEventListener("change", pintarJugadores);
    $("pl-cancelar").addEventListener("click", () => plFormulario(null));
}
