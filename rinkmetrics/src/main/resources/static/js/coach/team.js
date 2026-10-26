// Equipo: los equipos del DT (con su balance) y sus jugadores. Un equipo se arma desde una categoría del club en
// SIDGAD: su plantel viene tildado y el DT destilda a los que no van. Los jugadores no se cargan a mano: salen de
// SIDGAD, y en la lista de cada equipo se tildan (juegan) o destildan (quedan afuera, para sumarlos después).
import {crud, coach, el, getJson, fetchTeams} from "./common.js";

let teamEditing = null;   // id del equipo que se está renombrando (null: alta)
let coachPlayers = [];
let categoryList = null;   // categorías del club en SIDGAD ([{clave, rama, nombre}]); null: todavía no se pidieron
let sidgadSquad = null;   // el plantel de SIDGAD de la categoría elegida (null: no hay o se está leyendo)
let autoName = "";   // el nombre que se completó solo con la categoría (si el DT no lo cambió, se reemplaza)

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

/** Las categorías del club en SIDGAD para el combo (los equipos salen de ahí, con su plantel). */
async function loadCategories() {
    const sel = $("eq-categoria");
    try {
        categoryList = (await getJson("/api/coach/club/categories")).data;
    } catch (err) {
        categoryList = [];
    }
    const choose = el("option", null, categoryList.length ? "Elegí la categoría" : "No se pudieron leer las categorías de SIDGAD");
    choose.value = "";
    sel.replaceChildren(choose, ...categoryList.map(c => { const o = el("option", null, c.name); o.value = c.key + "|" + c.branch; return o; }));
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
export const editing = () => teamEditing != null || !!$("eq-nombre").value.trim();

function paintTeams() {
    const teams = coach.teams;
    const ul = $("eq-lista");
    // Cuántos equipos usa de los que permite su plan (free y mensual, 3).
    const max = coach.session.subscription && coach.session.subscription.maxTeams;
    $("eq-n").textContent = max ? `${teams.length} de ${max} ${max === 1 ? "equipo" : "equipos"}`
        : teams.length ? `${teams.length} ${teams.length === 1 ? "equipo" : "equipos"}` : "";
    const full = max && teams.length >= max && teamEditing == null;
    $("eq-nombre").disabled = $("eq-guardar").disabled = !!full;
    if (full && !$("eq-status").textContent) crudStatus("eq-status", `Tu plan permite ${max === 1 ? "1 equipo" : max + " equipos"}: `
        + "para sumar otro, borrá uno sin partidos"
        + (coach.session.subscription.kind === "COACH_FREE" ? " o pasate al plan mensual." : "."));
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
    // El equipo cuya lista de jugadores se ve (uno por vez: ahí se tildan o destildan).
    const f = $("pl-filtro"), before = f.value;
    f.replaceChildren(...teams.map(e => { const o = el("option", null, e.name); o.value = String(e.id); return o; }));
    f.value = teams.some(e => String(e.id) === before) ? before : teams.length ? String(teams[0].id) : "";
    f.hidden = !teams.length;
}

/** Los jugadores del equipo elegido: tildados los que juegan, destildados los que quedaron afuera. */
function paintPlayers() {
    const ul = $("pl-lista");
    const teamId = Number($("pl-filtro").value);
    ul.replaceChildren();
    if (!coach.teams.length) {
        $("pl-n").textContent = "";
        ul.append(el("li", "coach-vacio", "Creá un equipo desde una categoría de tu club: sus jugadores salen de SIDGAD."));
        return;
    }
    const list = coachPlayers.filter(j => j.teams.includes(teamId) || (j.pending || []).includes(teamId));
    const playing = list.filter(j => j.teams.includes(teamId)).length;
    $("pl-n").textContent = list.length ? `${playing} de ${list.length} juegan` : "";
    if (!list.length) ul.append(el("li", "coach-vacio", "Este equipo no tiene jugadores de SIDGAD."));
    for (const j of list) {
        const li = el("li", "pl-tilde");
        const label = el("label");
        const box = el("input");
        box.type = "checkbox";
        box.checked = j.teams.includes(teamId);
        box.setAttribute("aria-label", (box.checked ? "Sacar a " : "Incluir a ") + j.name);
        box.addEventListener("change", () => playerToggle(j, teamId, box));
        const whoName = el("span", "quien");
        whoName.append(el("b", null, j.name));
        if (j.goalkeeper) whoName.append(el("span", "inf-ar", "Arquero"));
        label.append(box, el("span", "pl-num", j.number ? "#" + j.number : "s/n"), whoName);
        li.classList.toggle("pendiente", !box.checked);
        li.append(label);
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
    if (!id && !cat) {
        crudStatus("eq-status", "Elegí la categoría de tu club: el equipo sale de SIDGAD, con su plantel.", true);
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
            : `Equipo creado: ${data.name}.`);
        await load();
        // El equipo nuevo queda elegido en la lista de jugadores: se ven los que juegan y los que quedaron afuera.
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

/** Tilda (incluye) o destilda (saca) a un jugador del equipo elegido; destildado queda en la lista para volver. */
async function playerToggle(j, teamId, box) {
    const team = (coach.teams.find(e => e.id === teamId) || {}).name || "el equipo";
    box.disabled = true;
    try {
        const data = await crud(box.checked ? "POST" : "DELETE", `/api/coach/players/${j.id}/teams/${teamId}`);
        Object.assign(j, data);
        crudStatus("pl-status", box.checked ? `${j.name} juega en ${team}.` : `${j.name} quedó afuera de ${team}.`);
        paintPlayers();
        fetchTeams(true).then(paintTeams).catch(() => { });
    } catch (err) {
        box.checked = !box.checked;
        box.disabled = false;
        crudStatus("pl-status", offline(err), true);
    }
}

export function start() {
    $("eq-categoria").addEventListener("change", onCategoryChosen);
    $("eq-form").addEventListener("submit", teamSave);
    $("eq-cancelar").addEventListener("click", () => teamForm(null));
    $("pl-filtro").addEventListener("change", paintPlayers);
}
