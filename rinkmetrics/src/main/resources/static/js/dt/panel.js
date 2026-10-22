// Control panel: totales, estado de los partidos, partidos por mes y los próximos.
import * as Fichas from "./fichas.js";
import {diaMes, dt, el, getJson, hoy, nombreEquipo} from "./comun.js";

export async function cargar() {
    try {
        const lista = (await getJson("/api/dt/partidos")).data;
        const d = Fichas.dashboard(lista, hoy());
        $("d-partidos").textContent = d.partidos;
        $("d-mes").textContent = d.esteMes;
        const cupo = dt.sesion.suscripcion && dt.sesion.suscripcion.partidosPorMes;
        $("d-mes-label").textContent = cupo ? `Este mes (plan: ${cupo} por mes)` : "Este mes";
        $("d-estados").innerHTML = Fichas.barrasEstado(d.estados);
        $("d-meses").innerHTML = Fichas.lineaMeses(d.meses);
        const ul = $("d-proximos");
        ul.replaceChildren();
        if (!d.proximos.length) ul.append(el("li", "dt-vacio", "No hay partidos programados. Creá uno con fecha futura en Partidos."));
        for (const p of d.proximos) {
            const li = el("li"), b = el("button");
            b.type = "button";
            b.append(el("span", null, `${diaMes(p.fecha)} · vs. ${p.rival}`), el("b", null, p.local ? "Local" : "Visitante"),
                el("small", null, nombreEquipo(p)));
            b.addEventListener("click", () => { location.hash = "partido=" + p.id; });
            li.append(b);
            ul.append(li);
        }
    } catch (err) {
        $("d-estados").textContent = "No se pudo cargar el dashboard: " + err.message;
    }
}
