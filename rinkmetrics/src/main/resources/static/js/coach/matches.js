// Partidos: crear uno nuevo y la lista de los del DT (abrir o eliminar).
import {STATUS, crud, dayMonth, coach, el, teamOf, getJson, today, fillTeams, teamName, toast, fetchTeams} from "./common.js";

const chosenOpponent = () => $("rival").value.trim();

async function create() {
    const e = teamOf($("equipo"));
    if (!e) { status("Primero creá un equipo en Equipos.", true); return; }
    const body = {team: e.id, opponent: chosenOpponent(), date: $("fecha").value || null,
        home: $("local").value === "true", tournament: $("torneo").value.trim() || null, minutes: Number($("minutos").value)};
    if (!body.opponent) { status("Escribí el rival.", true); return; }
    $("crear").disabled = true;
    status("Creando el partido…");
    try {
        const p = await postJson("/api/coach/matches", body);
        status("");
        location.hash = "match=" + p.id;
    } catch (err) {
        status(err instanceof TypeError ? "Sin señal: para empezar un partido hace falta conexión." : err.message, true);
    } finally {
        $("crear").disabled = !chosenOpponent() || !coach.teams.length;
    }
}

export async function load() {
    const ul = $("mis");
    try {
        const [list] = await Promise.all([getJson("/api/coach/matches").then(r => r.data), fetchTeams()]);
        fillTeams($("equipo"), false);
        $("sin-equipos").hidden = coach.teams.length > 0;
        $("equipo").disabled = !coach.teams.length;
        $("crear").disabled = !chosenOpponent() || !coach.teams.length;
        // Los campeonatos y rivales ya usados, para elegirlos al crear el próximo partido.
        const options = valueList => [...new Set(valueList.filter(Boolean))].map(t => { const o = el("option"); o.value = t; return o; });
        $("torneos").replaceChildren(...options(list.map(p => p.tournament)));
        $("rivales").replaceChildren(...options(list.map(p => p.opponent)));
        ul.replaceChildren();
        $("mis-n").textContent = list.length ? String(list.length) : "";
        if (!list.length) ul.append(el("li", "coach-vacio", "Todavía no cargaste partidos."));
        for (const p of list) {
            const li = el("li", "con-borrar");
            const b = el("button");
            b.type = "button";
            const t = el("span", null, `${dayMonth(p.date)} · ${p.opponent}`);
            const r = el("b", null, `${p.goalsFor} - ${p.goalsAgainst}`);
            const sub = el("small", null, [teamName(p), p.home ? "Local" : "Visitante", p.tournament, STATUS[p.status]]
                .filter(Boolean).join(" · "));
            b.append(t, r, sub);
            b.addEventListener("click", () => { location.hash = "match=" + p.id; });
            const del = el("button", "coach-borrar", "Eliminar");
            del.type = "button";
            del.setAttribute("aria-label", `Eliminar el partido contra ${p.opponent} del ${dayMonth(p.date)}`);
            del.addEventListener("click", () => remove(p));
            li.append(b, del);
            ul.append(li);
        }
    } catch (err) {
        ul.replaceChildren(el("li", "coach-vacio", "No se pudo cargar la lista: " + err.message));
    }
}

/** Elimina el partido con todas sus acciones (no se puede deshacer). */
async function remove(p) {
    if (!confirm(`¿Eliminar el partido contra ${p.opponent} del ${dayMonth(p.date)}? Se borran todas sus acciones y deja `
        + "de contar en las estadísticas y los informes. No se puede deshacer.")) return;
    try {
        await crud("DELETE", `/api/coach/matches/${p.id}`);
        toast(`Partido contra ${p.opponent} eliminado.`);
        load();
    } catch (err) {
        toast(err instanceof TypeError ? "Sin señal: para eliminar un partido hace falta conexión." : err.message);
    }
}

export function start() {
    $("rival").addEventListener("input", () => { $("crear").disabled = !chosenOpponent() || !coach.teams.length; });
    $("fecha").value = today();
    $("crear").addEventListener("click", create);
}
