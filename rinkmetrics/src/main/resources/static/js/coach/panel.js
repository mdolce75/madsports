// Control panel: totales, estado de los partidos, partidos por mes y los próximos.
import * as PlayerCards from "./player-cards.js";
import {dayMonth, coach, el, getJson, today, teamName} from "./common.js";

export async function load() {
    try {
        const list = (await getJson("/api/coach/matches")).data;
        const d = PlayerCards.dashboard(list, today());
        $("d-partidos").textContent = d.matches;
        $("d-mes").textContent = d.thisMonth;
        const quota = coach.session.subscription && coach.session.subscription.matchesPerMonth;
        $("d-mes-label").textContent = quota ? `Este mes (plan: ${quota} por mes)` : "Este mes";
        $("d-estados").innerHTML = PlayerCards.statusBars(d.statuses);
        $("d-meses").innerHTML = PlayerCards.monthsLine(d.months);
        const ul = $("d-proximos");
        ul.replaceChildren();
        if (!d.upcomingMatches.length) ul.append(el("li", "coach-vacio", "No hay partidos programados. Creá uno con fecha futura en Partidos."));
        for (const p of d.upcomingMatches) {
            const li = el("li"), b = el("button");
            b.type = "button";
            b.append(el("span", null, `${dayMonth(p.date)} · vs. ${p.opponent}`), el("b", null, p.home ? "Local" : "Visitante"),
                el("small", null, teamName(p)));
            b.addEventListener("click", () => { location.hash = "match=" + p.id; });
            li.append(b);
            ul.append(li);
        }
    } catch (err) {
        $("d-estados").textContent = "No se pudo cargar el dashboard: " + err.message;
    }
}
