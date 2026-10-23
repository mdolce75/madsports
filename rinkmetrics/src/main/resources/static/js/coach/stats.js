// La ficha de un jugador (índice, pentágono de los cinco ejes, datos clave y detalle por eje): la usan los informes.
import * as PlayerCards from "./player-cards.js";
import {el} from "./common.js";

/** Ficha del jugador: encabezado con el índice, pentágono de los cinco ejes, datos clave y el detalle por eje. */
export function playerCard(f, team) {
    const card = el("article", "ficha" + (f.goalkeeper ? " arquero" : "") + (f.scoreIndex == null ? " sin-datos" : ""));
    const head = el("header", "ficha-head");
    const whoName = el("div");
    whoName.append(el("b", null, f.name),
        el("small", null, `${f.number ? "#" + f.number + " · " : ""}${f.goalkeeper ? "Arquero" : team}`),
        el("small", "ficha-pj", `${f.matches} ${f.matches === 1 ? "partido" : "partidos"}`));
    const idx = el("div", "ficha-indice");
    idx.append(el("b", null, f.scoreIndex == null ? "–" : String(f.scoreIndex)), el("small", null, "Índice"));
    head.append(whoName, idx);
    const chart = el("div", "ficha-pentagono");
    if (f.scoreIndex == null) chart.append(el("p", "coach-vacio", "Sin acciones cargadas todavía."));
    else chart.innerHTML = PlayerCards.pentagon(f.axes);
    const e = Object.fromEntries(f.axes.map(x => [x.key, x]));
    const data = f.goalkeeper
        ? [[`${e.effectivenessPct.a}`, "Atajados"], [`${e.effectivenessPct.b}`, "Recibidos"],
           [`${e.setPieces.a}/${e.setPieces.a + e.setPieces.b}`, "Penal/libre"]]
        : [[`${e.attack.a}/${e.attack.b}`, "Goles/tiros"], [`${e.recovery.a} | ${e.recovery.b}`, "Rec. | Pérd."],
           [`${e.discipline.b}`, "Tarjetas"]];
    const chips = el("div", "ficha-datos");
    for (const [v, n] of data) { const c = el("div"); c.append(el("b", null, v), el("span", null, n)); chips.append(c); }
    const detail = el("details", "ficha-detalle");
    detail.append(el("summary", null, "Detalle por eje"));
    for (const x of f.axes) {
        const row = el("div", "ficha-fila");
        row.append(el("span", "t", x.name), el("span", "v", x.text),
            el("span", "d", x.labels.join(" · ")), el("span", "n", `${x.a} | ${x.b}`));
        detail.append(row);
    }
    card.append(head, chart, chips, detail);
    return card;
}
