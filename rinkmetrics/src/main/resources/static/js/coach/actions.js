// Acciones de RinkMetrics: los botones de la carga (tipo, resultado, nombre y color) y las reglas que las cuentan
// (goles a favor y en contra, acciones del arquero). Las mismas que ActionType.java y CoachReport.name en el servidor.

// Colores de los botones: cada acción el suyo (texto blanco; la amarilla, oscuro). Defensa = campo propio, ataque =
// campo rival. Los mismos colores marcan cada acción en la lista de últimas acciones.
export const C = {
    turnDef: "#b91c1c", turnAtt: "#c2410c", recDef: "#15803d", recAtq: "#4d7c0f",
    goal: "#0f766e", noGoal: "#52525b", saveAction: "#1d4ed8", goalConceded: "#9f1239",
    foulCom: "#6d28d9", foulRec: "#0369a1", yellowCard: "#facc15", blueCard: "#1d4ed8", redCard: "#dc2626",
};
// arco: se cargan al arquero en cancha. Las de a pares (gol / no gol) van en una tarjeta.
export const SIMPLE = [
    {g: "g-posesion", kind: "TURNOVER", res: "OWN_HALF", t: "Pérdida en defensa", col: C.turnDef},
    {g: "g-posesion", kind: "TURNOVER", res: "RIVAL_HALF", t: "Pérdida en ataque", col: C.turnAtt},
    {g: "g-posesion", kind: "RECOVERY", res: "OWN_HALF", t: "Recuperación en defensa", col: C.recDef},
    {g: "g-posesion", kind: "RECOVERY", res: "RIVAL_HALF", t: "Recuperación en ataque", col: C.recAtq},
    {g: "g-arco", kind: "SAVE", res: null, t: "Atajada", d: "Arquero", col: C.saveAction, goalArea: true},
    {g: "g-arco", kind: "GOAL_CONCEDED", res: null, t: "Gol recibido", d: "Arquero", col: C.goalConceded, goalArea: true},
    {g: "g-faltas", kind: "FOUL", res: "COMMITTED", t: "Falta cometida", col: C.foulCom},
    {g: "g-faltas", kind: "FOUL", res: "RECEIVED", t: "Falta recibida", col: C.foulRec},
    {g: "g-tarjetas", kind: "CARD", res: "YELLOW", t: "Tarjeta amarilla", col: C.yellowCard, dark: true},
    {g: "g-tarjetas", kind: "CARD", res: "BLUE", t: "Tarjeta azul", col: C.blueCard},
    {g: "g-tarjetas", kind: "CARD", res: "RED", t: "Tarjeta roja", col: C.redCard},
];
const FOR_US = {si: ["GOAL", "Gol", C.goal], no: ["NO_GOAL", "No gol", C.noGoal]};
const AGAINST_US = {si: ["GOAL", "Gol", C.goalConceded], no: ["SAVED", "Atajado", C.saveAction]};
export const PAIRS = [
    Object.assign({g: "g-arco", kind: "SHOT", t: "Tiro al arco", whoName: ""}, FOR_US),
    Object.assign({g: "g-parada", kind: "FREE_HIT_FOR", t: "Libre a favor", whoName: "pateador"}, FOR_US),
    Object.assign({g: "g-parada", kind: "FREE_HIT_AGAINST", t: "Libre en contra", whoName: "arquero", goalArea: true}, AGAINST_US),
    Object.assign({g: "g-parada", kind: "PENALTY_FOR", t: "Penal a favor", whoName: "pateador"}, FOR_US),
    Object.assign({g: "g-parada", kind: "PENALTY_AGAINST", t: "Penal en contra", whoName: "arquero", goalArea: true}, AGAINST_US),
];

/** Nombre completo de una acción ("Pérdida en ataque", "Penal a favor: Gol") y su color. */
export function action(kind, res) {
    const s = SIMPLE.find(x => x.kind === kind && x.res === (res || null));
    if (s) return {name: s.t, col: s.col};
    const p = PAIRS.find(x => x.kind === kind);
    const r = p && [p.si, p.no].find(x => x[0] === res);
    return r ? {name: `${p.t}: ${r[1]}`, col: r[2]} : {name: kind, col: "var(--muted)"};
}

/** Las que se cargan al arquero en cancha. */
export const GOAL_ACTIONS = new Set(["SAVE", "GOAL_CONCEDED", "FREE_HIT_AGAINST", "PENALTY_AGAINST"]);

export const isGoalFor = a => a.outcome === "GOAL" && ["SHOT", "FREE_HIT_FOR", "PENALTY_FOR"].includes(a.kind);
export const isGoalAgainst = a => a.kind === "GOAL_CONCEDED"
    || a.outcome === "GOAL" && ["FREE_HIT_AGAINST", "PENALTY_AGAINST"].includes(a.kind);

/** Clave del jugador de una acción: "j" + id, o "m" + nombre para uno agregado a mano en el partido. */
export const playerKey = a => a.playerId > 0 ? "j" + a.playerId : "m" + a.playerName;
