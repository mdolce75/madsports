// Equipo: ABM de los equipos del DT (con su balance) y de sus jugadores (asignados a uno o varios equipos). Un equipo
// se arma desde una categoría del club en SIDGAD: su plantel viene tildado y el DT destilda a los que no van, que
// quedan en la lista como "no incluidos" para sumarlos después.
import {crud, coach, el, getJson, fetchTeams} from "./common.js";

let teamEditing = null;   // id del equipo que se está renombrando (null: alta)
let playerEditing = null;   // id del jugador que se está editando (null: alta)
let coachPlayers = [];
let categoryList = null;   // categorías del club en SIDGAD ([{clave, rama, nombre}]); null: todavía no se pidieron
let sidgadSquad = null;   // el plantel de SIDGAD de la categoría elegida (null: no hay o se está leyendo)
let autoName = "";   // el nombre que se completó solo con la categoría (si el DT no lo cambió, se reemplaza)
const OTHER = "otro";

export async function load() {
    if (categoryList === null) loadCategories();
    try {
        const [, js] = await Promise.all([fetchTeams(true), getJson("/api/coach/players").then(r => r.data)]);
        coachPlayers = js;
        paintTeams();
        paintPlayers();
    } catch (err) {
        $("eq-lista").replaceChildren(el("li", "coach-vacio", "No se pudieron cargar los equipos: " + err.message));
    }
}

// ---------- Equipo desde una categoría del club ----------

/** Las categorías del club en SIDGAD para el combo; sin señal o sin SIDGAD, solo "Otro" (nombre libre). */
async function loadCategories() {
    const sel = $("eq-categoria");
    try {
        categoryList = (await getJson("/api/coach/club/categories")).data;
    } catch (err) {
        categoryList = [];
    }
    const choose = el("option", null, categoryList.length ? "Elegí la categoría" : "No se pudieron leer las categorías de SIDGAD");
    choose.value = "";
    const other = el("option", null, "Otro: nombre libre, sin jugadores de SIDGAD");
    other.value = OTHER;
    sel.replaceChildren(choose, ...categoryList.map(c => { const o = el("option", null, c.name); o.value = c.key + "|" + c.branch; return o; }), other);
    if (!categoryList.length) sel.value = OTHER;
    paintTeams();
}

const chosenCategory = () => {
    const v = $("eq-categoria").value;
    return categoryList && categoryList.find(c => c.key + "|" + c.branch === v) || null;
};

/** Al elegir la categoría: su nombre (si el DT no escribió otro) y su plantel de SIDGAD, todo tildado. */
async function onCategoryChosen() {
    const c = chosenCategory();
    const name = $("eq-nombre");
    if (!name.value.trim() || name.value === autoName) {
        name.value = c ? c.name : "";
        autoName = name.value;
    }
    sidgadSquad = null;
    const box = $("eq-jugadores");
    $("eq-sidgad").hidden = !c;
    if (!c) { box.replaceChildren(); return; }
    box.replaceChildren(el("p", "coach-vacio", "Leyendo las actas del equipo en SIDGAD… (puede tardar unos segundos)"));
    $("eq-sidgad-n").textContent = "";
    try {
        const list = (await getJson(`/api/coach/club/squad?category=${encodeURIComponent(c.key)}&branch=${c.branch}`)).data;
        if (chosenCategory() !== c) return;   // cambió la categoría mientras se leía
        sidgadSquad = list;
        box.replaceChildren();
        if (!list.length) box.append(el("p", "coach-vacio", "El equipo no tiene actas publicadas todavía: el equipo se crea sin jugadores."));
        for (const j of list) {
            const l = el("label", "eq-jug");
            const cb = el("input");
            cb.type = "checkbox";
            cb.value = String(j.id);
            cb.checked = true;
            cb.addEventListener("change", countSidgad);
            const whoName = el("span", "quien", j.name);
            l.append(cb, el("span", "pl-num", j.number ? "#" + j.number : "s/n"), whoName);
            if (j.goalkeeper) l.append(el("span", "inf-ar", "Arquero"));
            box.append(l);
        }
        countSidgad();
    } catch (err) {
        box.replaceChildren(el("p", "coach-vacio", "No se pudo leer el plantel en SIDGAD: " + err.message));
    }
}

function countSidgad() {
    const all = [...document.querySelectorAll("#eq-jugadores input")];
    $("eq-sidgad-n").textContent = all.length ? `${all.filter(c => c.checked).length} de ${all.length} incluidos` : "";
}

/** ¿Se está escribiendo en un formulario del ABM? (para no pisarlo al refrescar) */
export const editing = () => teamEditing != null || playerEditing != null
    || ["eq-nombre", "pl-nombre", "pl-apellido", "pl-numero"].some(id => $(id).value.trim());

function paintTeams() {
    const teams = coach.teams;
    const ul = $("eq-lista");
    // Cuántos equipos usa de los que permite su plan (free 1, mensual 3).
    const max = coach.session.subscription && coach.session.subscription.maxTeams;
    $("eq-n").textContent = max ? `${teams.length} de ${max} ${max === 1 ? "equipo" : "equipos"}`
        : teams.length ? `${teams.length} ${teams.length === 1 ? "equipo" : "equipos"}` : "";
    const full = max && teams.length >= max && teamEditing == null;
    $("eq-nombre").disabled = $("eq-guardar").disabled = !!full;
    if (full && !$("eq-status").textContent) crudStatus("eq-status", `Tu plan permite ${max === 1 ? "1 equipo" : max + " equipos"}: `
        + "para sumar otro, borrá uno sin partidos" + (max === 1 ? " o pasate al plan mensual." : "."));
    ul.replaceChildren();
    if (!teams.length) ul.append(el("li", "coach-vacio", "Todavía no creaste equipos: agregá el primero."));
    for (const e of teams) {
        const li = el("li", "eq-fila");
        li.classList.toggle("editando", e.id === teamEditing);
        const whoName = el("div", "quien");
        whoName.append(el("b", null, e.name));
        if (e.sidgadCategory) {
            const c = (categoryList || []).find(x => x.key === e.sidgadCategory && x.branch === e.sidgadBranch);
            whoName.append(el("small", "eq-sidgad-cat", "SIDGAD: " + (c ? c.name : e.sidgadCategory)));
        }
        const bal = el("div", "eq-balance");
        for (const [v, n, t] of [[e.played, "PJ", "Jugados"], [e.won, "G", "Ganados"], [e.drawn, "E", "Empatados"],
            [e.lost, "P", "Perdidos"]]) {
            const c = el("span", "eq-dato");
            c.title = t;
            c.append(el("b", null, String(v)), el("small", null, n));
            bal.append(c);
        }
        const acc = el("div", "acc");
        const edit = el("button", "coach-link", "Editar");
        edit.type = "button";
        edit.setAttribute("aria-label", "Cambiar el nombre de " + e.name);
        edit.addEventListener("click", () => teamForm(e));
        const deleteItem = el("button", "coach-link pl-baja", "Borrar");
        deleteItem.type = "button";
        deleteItem.setAttribute("aria-label", "Borrar el equipo " + e.name);
        deleteItem.addEventListener("click", () => teamDelete(e));
        acc.append(edit, deleteItem);
        li.append(whoName, bal, acc);
        ul.append(li);
    }
    // Equipos en el formulario de jugadores y en el filtro de la lista.
    $("pl-sin-equipos").hidden = teams.length > 0;
    for (const id of ["pl-nombre", "pl-apellido", "pl-numero", "pl-arquero", "pl-guardar"]) $(id).disabled = !teams.length;
    const markedIds = new Set([...document.querySelectorAll("#pl-equipos input:checked")].map(i => i.value));
    $("pl-equipos").replaceChildren(...teams.map(e => {
        const l = el("label", "pl-eq");
        const c = el("input");
        c.type = "checkbox";
        c.value = String(e.id);
        c.checked = markedIds.has(c.value) || teams.length === 1;
        l.append(c, " " + e.name);
        return l;
    }));
    const f = $("pl-filtro"), before = f.value;
    const all = el("option", null, "Todos los equipos");
    all.value = "";
    f.replaceChildren(all, ...teams.map(e => { const o = el("option", null, e.name); o.value = String(e.id); return o; }));
    f.value = teams.some(e => String(e.id) === before) ? before : "";
    f.hidden = teams.length < 2;
}

function paintPlayers() {
    const ul = $("pl-lista");
    const filterBy = $("pl-filtro").value;
    const list = coachPlayers.filter(j => !filterBy || j.teams.map(String).includes(filterBy)
        || (j.pending || []).map(String).includes(filterBy));
    $("pl-n").textContent = coachPlayers.length ? `${coachPlayers.length} ${coachPlayers.length === 1 ? "jugador" : "jugadores"}` : "";
    ul.replaceChildren();
    if (!list.length) ul.append(el("li", "coach-vacio", coachPlayers.length ? "No hay jugadores en ese equipo."
        : "Todavía no hay jugadores: agregá el primero."));
    const names = new Map(coach.teams.map(e => [e.id, e.name]));
    for (const j of list) {
        const li = el("li");
        li.classList.toggle("editando", j.id === playerEditing);
        const pending = (j.pending || []).filter(id => names.has(id));
        li.classList.toggle("pendiente", !!filterBy && pending.map(String).includes(filterBy));
        const whoName = el("div", "quien");
        whoName.append(el("b", null, j.name));
        if (j.goalkeeper) whoName.append(el("span", "inf-ar", "Arquero"));
        if (li.classList.contains("pendiente")) whoName.append(el("span", "pl-tag-pend", "No incluido"));
        const de = [...j.teams.map(id => names.get(id)).filter(Boolean),
            ...pending.map(id => `no incluido en ${names.get(id)}`)];
        whoName.append(el("small", "pl-de", de.join(" · ")));
        const edit = el("button", "coach-link", "Editar");
        edit.type = "button";
        edit.setAttribute("aria-label", "Editar a " + j.name);
        edit.addEventListener("click", () => playerForm(j));
        const deactivate = el("button", "coach-link pl-baja", "Dar de baja");
        deactivate.type = "button";
        deactivate.setAttribute("aria-label", "Dar de baja a " + j.name);
        deactivate.addEventListener("click", () => playerDeactivate(j));
        const acc = el("div", "acc");
        // Sumarlo a los equipos donde quedó "no incluido" (con filtro, solo al equipo filtrado).
        for (const id of pending.filter(id => !filterBy || String(id) === filterBy)) {
            const inc = el("button", "coach-link", filterBy ? "Incluir" : "Incluir en " + names.get(id));
            inc.type = "button";
            inc.setAttribute("aria-label", `Incluir a ${j.name} en ${names.get(id)}`);
            inc.addEventListener("click", () => playerInclude(j, id, names.get(id)));
            acc.append(inc);
        }
        acc.append(edit, deactivate);
        li.append(el("span", "pl-num", j.number ? "#" + j.number : "s/n"), whoName, acc);
        ul.append(li);
    }
}

function crudStatus(id, txt, error) {
    $(id).textContent = txt;
    $(id).className = "abm-status" + (txt ? (error ? " error" : " ok") : "");
}

const offline = err => err instanceof TypeError ? "Sin señal: para editar los equipos hace falta conexión." : err.message;

function teamForm(e) {
    teamEditing = e ? e.id : null;
    // Renombrar no cambia el plantel: la categoría y los jugadores de SIDGAD son solo para el alta.
    $("eq-categoria-campo").hidden = !!e;
    $("eq-sidgad").hidden = !!e || !chosenCategory();
    $("eq-titulo").textContent = e ? "Cambiar nombre" : "Nuevo equipo";
    $("eq-guardar").textContent = e ? "Guardar cambios" : "Agregar equipo";
    $("eq-cancelar").hidden = !e;
    $("eq-nombre").value = e ? e.name : "";
    crudStatus("eq-status", "");
    paintTeams();
    if (e) $("eq-nombre").focus();
}

async function teamSave(ev) {
    ev.preventDefault();
    const name = $("eq-nombre").value.trim();
    const id = teamEditing;
    const cat = id ? null : chosenCategory();
    if (!id && !cat && $("eq-categoria").value !== OTHER) {
        crudStatus("eq-status", "Elegí la categoría de tu club (u «Otro», para un equipo sin jugadores de SIDGAD).", true);
        $("eq-categoria").focus();
        return;
    }
    if (cat && sidgadSquad === null) { crudStatus("eq-status", "Esperá a que se lea el plantel en SIDGAD.", true); return; }
    if (!name) { crudStatus("eq-status", "Escribí el nombre del equipo.", true); $("eq-nombre").focus(); return; }
    const excluded = [...document.querySelectorAll("#eq-jugadores input:not(:checked)")].map(c => Number(c.value));
    const body = cat ? {name, category: cat.key, branch: cat.branch, excluded} : {name};
    $("eq-guardar").disabled = true;
    try {
        const data = await crud(id ? "PUT" : "POST", id ? `/api/coach/teams/${id}` : "/api/coach/teams", body);
        const total = cat ? sidgadSquad.length : 0;
        teamForm(null);
        $("eq-categoria").value = "";
        onCategoryChosen();
        crudStatus("eq-status", id ? `Guardado: ${data.name}.`
            : total ? `Equipo creado: ${data.name}, con ${total - excluded.length} de los ${total} jugadores de SIDGAD`
                + (excluded.length === 1 ? " (1 no incluido: está en la lista para sumarlo después)."
                    : excluded.length ? ` (${excluded.length} no incluidos: están en la lista para sumarlos después).` : ".")
            : `Equipo creado: ${data.name}. Ahora cargá sus jugadores.`);
        await load();
        // El equipo nuevo queda elegido en la lista de jugadores: se ven sus jugadores y los no incluidos.
        if (!id) { $("pl-filtro").value = String(data.id); paintPlayers(); }
    } catch (err) {
        crudStatus("eq-status", offline(err), true);
    } finally {
        $("eq-guardar").disabled = $("eq-nombre").disabled;   // con el cupo de equipos lleno, sigue deshabilitado
    }
}

async function teamDelete(e) {
    if (!confirm(`¿Borrar el equipo ${e.name}? Sus jugadores siguen en tus otros equipos.`)) return;
    try {
        await crud("DELETE", `/api/coach/teams/${e.id}`);
        if (teamEditing === e.id) teamForm(null);
        crudStatus("eq-status", `${e.name} se borró.`);
        await load();
    } catch (err) {
        crudStatus("eq-status", offline(err), true);
    }
}

/** El formulario de jugadores en modo alta (j null) o para editar a j. */
function playerForm(j) {
    playerEditing = j ? j.id : null;
    $("pl-titulo").textContent = j ? "Editar jugador" : "Nuevo jugador";
    $("pl-guardar").textContent = j ? "Guardar cambios" : "Agregar jugador";
    $("pl-cancelar").hidden = !j;
    $("pl-nombre").value = j ? j.names : "";
    $("pl-apellido").value = j ? j.lastName : "";
    $("pl-numero").value = j ? j.number : "";
    $("pl-arquero").checked = j ? j.goalkeeper : false;
    document.querySelectorAll("#pl-equipos input").forEach(c => {
        c.checked = j ? j.teams.map(String).includes(c.value) : coach.teams.length === 1;
    });
    crudStatus("pl-status", "");
    paintPlayers();
    if (j) $("pl-nombre").focus();
}

async function playerSave(ev) {
    ev.preventDefault();
    const body = {name: $("pl-nombre").value, lastName: $("pl-apellido").value, number: $("pl-numero").value,
        goalkeeper: $("pl-arquero").checked,
        teams: [...document.querySelectorAll("#pl-equipos input:checked")].map(c => Number(c.value))};
    if (!body.name.trim()) { crudStatus("pl-status", "Escribí el nombre del jugador.", true); $("pl-nombre").focus(); return; }
    if (!body.lastName.trim()) { crudStatus("pl-status", "Escribí el apellido del jugador.", true); $("pl-apellido").focus(); return; }
    if (!body.teams.length) { crudStatus("pl-status", "Asigná el jugador a un equipo.", true); return; }
    $("pl-guardar").disabled = true;
    try {
        const id = playerEditing;
        const data = await crud(id ? "PUT" : "POST", id ? `/api/coach/players/${id}` : "/api/coach/players", body);
        playerForm(null);
        crudStatus("pl-status", id ? `Guardado: ${data.name}.` : `Agregado: ${data.name}.`);
        $("pl-nombre").focus();
        await load();
    } catch (err) {
        crudStatus("pl-status", offline(err), true);
    } finally {
        $("pl-guardar").disabled = !coach.teams.length;
    }
}

/** Suma al equipo a un jugador que había quedado "no incluido". */
async function playerInclude(j, teamId, team) {
    try {
        await crud("POST", `/api/coach/players/${j.id}/teams/${teamId}`);
        crudStatus("pl-status", `${j.name} quedó incluido en ${team}.`);
        await load();
    } catch (err) {
        crudStatus("pl-status", offline(err), true);
    }
}

async function playerDeactivate(j) {
    if (!confirm(`¿Dar de baja a ${j.name}? Sale de sus equipos; sus acciones quedan en los partidos y los informes.`)) return;
    try {
        await crud("DELETE", `/api/coach/players/${j.id}`);
        if (playerEditing === j.id) playerForm(null);
        crudStatus("pl-status", `${j.name} salió del plantel.`);
        await load();
    } catch (err) {
        crudStatus("pl-status", offline(err), true);
    }
}

export function start() {
    $("eq-categoria").addEventListener("change", onCategoryChosen);
    $("pl-form").addEventListener("submit", playerSave);
    $("eq-form").addEventListener("submit", teamSave);
    $("eq-cancelar").addEventListener("click", () => teamForm(null));
    $("pl-filtro").addEventListener("change", paintPlayers);
    $("pl-cancelar").addEventListener("click", () => playerForm(null));
}
