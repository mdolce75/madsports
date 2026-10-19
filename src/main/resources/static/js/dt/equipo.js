// Equipo: ABM de los equipos del DT (con su balance) y de sus jugadores (asignados a uno o varios equipos).
import {abm, dt, el, getJson, traerEquipos} from "./comun.js";

let eqEditando = null;   // id del equipo que se está renombrando (null: alta)
let plEditando = null;   // id del jugador que se está editando (null: alta)
let jugadoresDt = [];

export async function cargar() {
    try {
        const [, js] = await Promise.all([traerEquipos(true), getJson("/api/dt/jugadores").then(r => r.data)]);
        jugadoresDt = js;
        pintarEquipos();
        pintarJugadores();
    } catch (err) {
        $("eq-lista").replaceChildren(el("li", "dt-vacio", "No se pudieron cargar los equipos: " + err.message));
    }
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
    const lista = jugadoresDt.filter(j => !filtro || j.equipos.map(String).includes(filtro));
    $("pl-n").textContent = jugadoresDt.length ? `${jugadoresDt.length} ${jugadoresDt.length === 1 ? "jugador" : "jugadores"}` : "";
    ul.replaceChildren();
    if (!lista.length) ul.append(el("li", "dt-vacio", jugadoresDt.length ? "No hay jugadores en ese equipo."
        : "Todavía no hay jugadores: agregá el primero."));
    const nombres = new Map(dt.equipos.map(e => [e.id, e.nombre]));
    for (const j of lista) {
        const li = el("li");
        li.classList.toggle("editando", j.id === plEditando);
        const quien = el("div", "quien");
        quien.append(el("b", null, j.nombre));
        if (j.arquero) quien.append(el("span", "inf-ar", "Arquero"));
        quien.append(el("small", "pl-de", j.equipos.map(id => nombres.get(id)).filter(Boolean).join(" · ")));
        const editar = el("button", "dt-link", "Editar");
        editar.type = "button";
        editar.setAttribute("aria-label", "Editar a " + j.nombre);
        editar.addEventListener("click", () => plFormulario(j));
        const baja = el("button", "dt-link pl-baja", "Dar de baja");
        baja.type = "button";
        baja.setAttribute("aria-label", "Dar de baja a " + j.nombre);
        baja.addEventListener("click", () => plBaja(j));
        const acc = el("div", "acc");
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
    if (!nombre) { abmStatus("eq-status", "Escribí el nombre del equipo.", true); $("eq-nombre").focus(); return; }
    $("eq-guardar").disabled = true;
    try {
        const id = eqEditando;
        const data = await abm(id ? "PUT" : "POST", id ? `/api/dt/equipos/${id}` : "/api/dt/equipos", {nombre});
        eqFormulario(null);
        abmStatus("eq-status", id ? `Guardado: ${data.nombre}.` : `Equipo creado: ${data.nombre}. Ahora cargá sus jugadores.`);
        await cargar();
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
    $("pl-form").addEventListener("submit", plGuardar);
    $("eq-form").addEventListener("submit", eqGuardar);
    $("eq-cancelar").addEventListener("click", () => eqFormulario(null));
    $("pl-filtro").addEventListener("change", pintarJugadores);
    $("pl-cancelar").addEventListener("click", () => plFormulario(null));
}
