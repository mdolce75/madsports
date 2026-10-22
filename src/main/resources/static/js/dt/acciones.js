// Acciones de RinkMetrics: los botones de la carga (tipo, resultado, nombre y color) y las reglas que las cuentan
// (goles a favor y en contra, acciones del arquero). Las mismas que TipoAccion.java e InformeDt.nombre en el servidor.

// Colores de los botones: cada acción el suyo (texto blanco; la amarilla, oscuro). Defensa = campo propio, ataque =
// campo rival. Los mismos colores marcan cada acción en la lista de últimas acciones.
export const C = {
    perdDef: "#b91c1c", perdAtq: "#c2410c", recDef: "#15803d", recAtq: "#4d7c0f",
    gol: "#0f766e", noGol: "#52525b", atajada: "#1d4ed8", golRecibido: "#9f1239",
    faltaCom: "#6d28d9", faltaRec: "#0369a1", amarilla: "#facc15", azul: "#1d4ed8", roja: "#dc2626",
};
// arco: se cargan al arquero en cancha. Las de a pares (gol / no gol) van en una tarjeta.
export const SIMPLES = [
    {g: "g-posesion", tipo: "PERDIDA", res: "PROPIO", t: "Pérdida en defensa", col: C.perdDef},
    {g: "g-posesion", tipo: "PERDIDA", res: "RIVAL", t: "Pérdida en ataque", col: C.perdAtq},
    {g: "g-posesion", tipo: "RECUPERACION", res: "PROPIO", t: "Recuperación en defensa", col: C.recDef},
    {g: "g-posesion", tipo: "RECUPERACION", res: "RIVAL", t: "Recuperación en ataque", col: C.recAtq},
    {g: "g-arco", tipo: "ATAJADA", res: null, t: "Atajada", d: "Arquero", col: C.atajada, arco: true},
    {g: "g-arco", tipo: "GOL_RECIBIDO", res: null, t: "Gol recibido", d: "Arquero", col: C.golRecibido, arco: true},
    {g: "g-faltas", tipo: "FALTA", res: "COMETIDA", t: "Falta cometida", col: C.faltaCom},
    {g: "g-faltas", tipo: "FALTA", res: "RECIBIDA", t: "Falta recibida", col: C.faltaRec},
    {g: "g-tarjetas", tipo: "TARJETA", res: "AMARILLA", t: "Tarjeta amarilla", col: C.amarilla, oscuro: true},
    {g: "g-tarjetas", tipo: "TARJETA", res: "AZUL", t: "Tarjeta azul", col: C.azul},
    {g: "g-tarjetas", tipo: "TARJETA", res: "ROJA", t: "Tarjeta roja", col: C.roja},
];
const A_FAVOR = {si: ["GOL", "Gol", C.gol], no: ["NO_GOL", "No gol", C.noGol]};
const EN_CONTRA = {si: ["GOL", "Gol", C.golRecibido], no: ["ATAJADO", "Atajado", C.atajada]};
export const PARES = [
    Object.assign({g: "g-arco", tipo: "TIRO", t: "Tiro al arco", quien: ""}, A_FAVOR),
    Object.assign({g: "g-parada", tipo: "LIBRE_FAVOR", t: "Libre a favor", quien: "pateador"}, A_FAVOR),
    Object.assign({g: "g-parada", tipo: "LIBRE_CONTRA", t: "Libre en contra", quien: "arquero", arco: true}, EN_CONTRA),
    Object.assign({g: "g-parada", tipo: "PENAL_FAVOR", t: "Penal a favor", quien: "pateador"}, A_FAVOR),
    Object.assign({g: "g-parada", tipo: "PENAL_CONTRA", t: "Penal en contra", quien: "arquero", arco: true}, EN_CONTRA),
];

/** Nombre completo de una acción ("Pérdida en ataque", "Penal a favor: Gol") y su color. */
export function accion(tipo, res) {
    const s = SIMPLES.find(x => x.tipo === tipo && x.res === (res || null));
    if (s) return {nombre: s.t, col: s.col};
    const p = PARES.find(x => x.tipo === tipo);
    const r = p && [p.si, p.no].find(x => x[0] === res);
    return r ? {nombre: `${p.t}: ${r[1]}`, col: r[2]} : {nombre: tipo, col: "var(--muted)"};
}

/** Las que se cargan al arquero en cancha. */
export const ARCO = new Set(["ATAJADA", "GOL_RECIBIDO", "LIBRE_CONTRA", "PENAL_CONTRA"]);

export const golAFavor = a => a.resultado === "GOL" && ["TIRO", "LIBRE_FAVOR", "PENAL_FAVOR"].includes(a.tipo);
export const golEnContra = a => a.tipo === "GOL_RECIBIDO"
    || a.resultado === "GOL" && ["LIBRE_CONTRA", "PENAL_CONTRA"].includes(a.tipo);

/** Clave del jugador de una acción: "j" + id, o "m" + nombre para uno agregado a mano en el partido. */
export const clave = a => a.jugadorId > 0 ? "j" + a.jugadorId : "m" + a.jugadorNombre;
