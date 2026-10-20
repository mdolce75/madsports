// API de Métricas DT simulada para la demo: contesta en el navegador lo mismo que el servidor (DtController y sus
// servicios: equipos, jugadores, partidos, acciones, reloj e informes), con las mismas validaciones y los mismos
// mensajes. Se verifica contra los mismos casos que el servidor (src/test/resources/dt/contrato-api.json). No usa el
// DOM: corre también en Node para esos tests.
import {golAFavor, golEnContra} from "./acciones.js";
import * as Informe from "./informe.js";

const UUID = /^[0-9a-fA-F-]{36}$/;
const RESULTADOS = {
    PERDIDA: ["PROPIO", "RIVAL"], RECUPERACION: ["PROPIO", "RIVAL"], TIRO: ["GOL", "NO_GOL"], ATAJADA: [],
    GOL_RECIBIDO: [], LIBRE_FAVOR: ["GOL", "NO_GOL"], LIBRE_CONTRA: ["GOL", "ATAJADO"], PENAL_FAVOR: ["GOL", "NO_GOL"],
    PENAL_CONTRA: ["GOL", "ATAJADO"], FALTA: ["COMETIDA", "RECIBIDA"], TARJETA: ["AMARILLA", "AZUL", "ROJA"],
};
const REAPERTURA = 48 * 3600 * 1000;
const PRIMER_ID_PROPIO = 900000000;

/** Un error de la API: el status y el detalle que devuelve el servidor (ProblemDetail). */
class Rechazo extends Error {
    constructor(status, detail) { super(detail); this.status = status; }
}
const malPedido = d => new Rechazo(400, d), noExiste = d => new Rechazo(404, d), conflicto = d => new Rechazo(409, d);

const json = (status, body) => new Response(JSON.stringify(body), {status, headers: {"Content-Type": "application/json"}});
const limpio = s => (s == null ? "" : String(s)).trim().replace(/\s+/g, " ");
const porTexto = (a, b) => a.localeCompare(b, "es", {sensitivity: "base"});
const fechaCorta = f => `${f.slice(8, 10)}/${f.slice(5, 7)}/${f.slice(0, 4)}`;

/**
 * Crea la API. sesion: la de /api/sesion (con club y suscripcion: equiposMax y partidosPorMes); datos iniciales:
 * equipos [{id, nombre}], jugadores [{id, numero, nombres, apellido, arquero, equipos}] y partidos (con sus acciones;
 * desde = cuándo arrancó el reloj, en ms). club: el club en SIDGAD, para armar equipos desde una categoría:
 * {categorias: [{clave, rama, nombre}], planteles: {"CLAVE|RAMA": [{id, numero, nombre, nombres, apellido, arquero}]}}.
 * ahora: el reloj (ms), para los tests.
 */
export function crearApi({sesion, equipos = [], jugadores = [], partidos = [], club = {categorias: [], planteles: {}},
                          ahora = () => Date.now()}) {
    const hoy = () => { const d = new Date(ahora()); return new Date(d - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10); };
    let proximoEquipo = Math.max(0, ...equipos.map(e => e.id)) + 1;
    let proximoPartido = Math.max(0, ...partidos.map(p => p.id)) + 1;
    let creados = 0;
    jugadores = jugadores.map(j => Object.assign({activo: true, pendientes: []}, j, {numero: j.numero || null}));
    partidos = partidos.map(p => Object.assign({idp: null, torneo: null, tiempo: 1, segundos: 0, desde: null,
        cerrado: null, acciones: []}, p));

    // ---------- Equipos ----------

    const finalizado = p => p.estado === "TERMINADO" || p.estado === "CERRADO";
    const activas = p => p.acciones.filter(a => !a.borrada);
    const gf = p => activas(p).filter(golAFavor).length, gc = p => activas(p).filter(golEnContra).length;

    function equipoView(e) {
        const ps = partidos.filter(p => p.equipoId === e.id && finalizado(p));
        return {id: e.id, nombre: e.nombre, jugados: ps.length, ganados: ps.filter(p => gf(p) > gc(p)).length,
            empatados: ps.filter(p => gf(p) === gc(p)).length, perdidos: ps.filter(p => gf(p) < gc(p)).length,
            categoriaSidgad: e.categoriaSidgad || null, ramaSidgad: e.ramaSidgad || null};
    }

    /** El equipo pedido; sin id, el primero. */
    function equipo(id) {
        if (id == null) {
            if (!equipos.length) throw malPedido("Primero creá un equipo en la sección Equipo.");
            return equipos[0];
        }
        const e = equipos.find(x => x.id === id);
        if (!e) throw noExiste("No existe ese equipo.");
        return e;
    }

    function nombreEquipo(nombre, actual) {
        const n = limpio(nombre);
        if (!n) throw malPedido("Escribí el nombre del equipo.");
        if (n.length > 60) throw malPedido("El nombre del equipo es muy largo (hasta 60 letras).");
        const mismo = actual && actual.nombre.toLowerCase() === n.toLowerCase();
        if (!mismo && equipos.some(e => e.nombre.toLowerCase() === n.toLowerCase())) throw conflicto(`Ya tenés un equipo llamado ${n}.`);
        return n;
    }

    /** Equipo nuevo: con nombre libre, o desde una categoría del club con su plantel de SIDGAD (menos los excluidos). */
    function crearEquipo(body) {
        if (body.categoria) return crearEquipoDesdeSidgad(body);
        return json(201, equipoView(nuevoEquipo(body.nombre, null, null)));
    }

    function nuevoEquipo(nombre, categoria, rama) {
        const n = nombreEquipo(nombre, null);
        const plan = sesion.suscripcion || {};
        const max = plan.equiposMax || 0;
        if (max > 0 && equipos.length >= max) {
            throw conflicto(`Tu plan ${plan.nombre} permite ${max === 1 ? "1 equipo" : "hasta " + max + " equipos"}. Para sumar `
                + `otro, borrá uno sin partidos${plan.codigo === "DT_FREE" ? " o pasate al plan mensual." : "."}`);
        }
        const e = {id: proximoEquipo++, nombre: n, categoriaSidgad: categoria, ramaSidgad: rama};
        equipos.push(e);
        return e;
    }

    // ---------- Equipos desde SIDGAD ----------

    function categoriaDelClub(categoria, rama) {
        const c = club.categorias.find(x => x.clave === String(categoria || "").trim().toUpperCase()
            && x.rama === String(rama || "").trim().toUpperCase());
        if (!c) throw malPedido("Esa categoría no es de tu club.");
        return c;
    }

    const plantelSidgad = (categoria, rama) => {
        const c = categoriaDelClub(categoria, rama);
        return (club.planteles[c.clave + "|" + c.rama] || []).slice()
            .sort((a, b) => porTexto(a.apellido, b.apellido) || porTexto(a.nombres, b.nombres));
    };

    function crearEquipoDesdeSidgad(body) {
        const c = categoriaDelClub(body.categoria, body.rama);
        const plantelEq = plantelSidgad(c.clave, c.rama);
        const e = nuevoEquipo(limpio(body.nombre) ? body.nombre : c.nombre, c.clave, c.rama);
        const fuera = new Set(body.excluidos || []);
        const usados = new Set();
        // Primero los incluidos: si dos tienen el mismo número, se lo queda uno que juega.
        for (const p of plantelEq.slice().sort((a, b) => fuera.has(a.id) - fuera.has(b.id))) {
            const incluido = !fuera.has(p.id);
            let j = jugadores.find(x => x.id === p.id);
            if (!j) {
                const num = !p.numero || usados.has(p.numero) ? null : p.numero;
                if (num) usados.add(num);
                j = {id: p.id, nombres: p.nombres, apellido: p.apellido, numero: num, arquero: p.arquero, equipos: [],
                    pendientes: [], activo: true};
                jugadores.push(j);
            } else {
                j.activo = true;
                if (j.numero) usados.add(j.numero);
            }
            if (incluido) incluirEn(j, e.id); else if (!j.equipos.includes(e.id) && !j.pendientes.includes(e.id)) j.pendientes.push(e.id);
        }
        return json(201, equipoView(e));
    }

    function incluirEn(j, equipoId) {
        j.pendientes = j.pendientes.filter(x => x !== equipoId);
        if (!j.equipos.includes(equipoId)) j.equipos.push(equipoId);
    }

    function incluir(jugadorId, equipoId) {
        const j = suyo(jugadorId);
        const e = equipo(equipoId);
        verificarNumero([e.id], j.numero, jugadorId);
        incluirEn(j, e.id);
        return json(200, jugadorView(j));
    }

    function borrarEquipo(id) {
        const e = equipo(id);
        const n = partidos.filter(p => p.equipoId === e.id).length;
        if (n) throw conflicto(`${e.nombre} tiene ${n}${n === 1 ? " partido cargado" : " partidos cargados"}: no se puede borrar.`);
        jugadores.forEach(j => {
            j.equipos = j.equipos.filter(x => x !== e.id);
            j.pendientes = j.pendientes.filter(x => x !== e.id);
        });
        equipos.splice(equipos.indexOf(e), 1);
        return new Response(null, {status: 204});
    }

    // ---------- Jugadores ----------

    const completo = j => (j.nombres + " " + j.apellido).trim();
    const jugadorView = j => ({id: j.id, numero: j.numero || "", nombre: completo(j), nombres: j.nombres, apellido: j.apellido,
        arquero: j.arquero, equipos: j.equipos.slice().sort((a, b) => a - b),
        pendientes: j.pendientes.slice().sort((a, b) => a - b)});
    const activos = () => jugadores.filter(j => j.activo);

    function suyo(id) {
        const j = activos().find(x => x.id === id);
        if (!j) throw noExiste("Ese jugador no está en tus equipos.");
        return j;
    }

    function equiposValidos(pedidos) {
        const ids = [...new Set((pedidos || []).filter(x => x != null))];
        if (!ids.length) throw malPedido("Asigná el jugador a un equipo.");
        ids.forEach(equipo);
        return ids;
    }

    function numero(s) {
        const t = (s == null ? "" : String(s)).trim();
        if (!t) return null;
        if (!/^\d{1,3}$/.test(t)) throw malPedido("El número es de 1 a 3 cifras.");
        return String(Number(t));
    }

    function verificarNumero(suyos, num, salvo) {
        if (num == null) return;
        const otro = activos().find(j => j.id !== salvo && j.numero === num && j.equipos.some(e => suyos.includes(e)));
        if (otro) throw conflicto(`El número ${num} ya es de ${completo(otro)} en ese equipo.`);
    }

    function texto(s, campo) {
        const t = limpio(s);
        if (!t) throw malPedido(`Escribí el ${campo} del jugador.`);
        if (t.length > 40) throw malPedido(`El ${campo} es muy largo (hasta 40 letras).`);
        return t;
    }

    function altaJugador(body) {
        const suyos = equiposValidos(body.equipos);
        const num = numero(body.numero);
        verificarNumero(suyos, num, -1);
        const ultimo = Math.max(0, ...jugadores.map(j => j.id));
        const j = {id: ultimo >= PRIMER_ID_PROPIO ? ultimo + 1 : PRIMER_ID_PROPIO, nombres: texto(body.nombre, "nombre"),
            apellido: texto(body.apellido, "apellido"), numero: num, arquero: body.arquero === true, equipos: suyos,
            pendientes: [], activo: true};
        jugadores.push(j);
        return json(201, jugadorView(j));
    }

    function modificarJugador(id, body) {
        const j = suyo(id);
        const suyos = equiposValidos(body.equipos);
        const num = numero(body.numero);
        verificarNumero(suyos, num, id);
        Object.assign(j, {nombres: texto(body.nombre, "nombre"), apellido: texto(body.apellido, "apellido"), numero: num,
            arquero: body.arquero === true, equipos: suyos, pendientes: j.pendientes.filter(x => !suyos.includes(x))});
        return json(200, jugadorView(j));
    }

    /** Los jugadores del equipo, por número (sin número, al final) y apellido. */
    const plantel = e => activos().filter(j => j.equipos.includes(e.id))
        .sort((a, b) => (a.numero == null ? 9999 : Number(a.numero)) - (b.numero == null ? 9999 : Number(b.numero))
            || porTexto(a.apellido, b.apellido));

    // ---------- Partidos ----------

    const duracion = p => p.minutosTiempo * 60;
    const jugados = p => Math.max(0, Math.min(duracion(p), p.segundos + (p.desde != null ? Math.floor((ahora() - p.desde) / 1000) : 0)));
    const enOrden = p => activas(p).map((a, i) => [a, i]).sort(([a, i], [b, k]) => a.tiempo - b.tiempo || a.segundo - b.segundo || i - k)
        .map(([a]) => a);

    function partidoView(p, conAcciones) {
        const j = jugados(p);
        return {id: p.id, equipoId: p.equipoId, equipo: (equipos.find(e => e.id === p.equipoId) || {}).nombre || "",
            idp: p.idp, torneo: p.torneo, rival: p.rival, fecha: p.fecha, local: p.local, minutosTiempo: p.minutosTiempo,
            estado: p.estado, golesFavor: gf(p), golesContra: gc(p),
            reloj: {tiempo: p.tiempo, jugados: j, restante: duracion(p) - j, corriendo: p.desde != null},
            acciones: conAcciones ? enOrden(p).map(a => ({id: a.id, jugadorId: a.jugadorId, jugadorNombre: a.jugadorNombre,
                tipo: a.tipo, resultado: a.resultado ?? null, tiempo: a.tiempo, segundo: a.segundo})) : []};
    }

    function partido(id) {
        const p = partidos.find(x => x.id === id);
        if (!p) throw noExiste("No existe ese partido.");
        return p;
    }

    function crearPartido(n) {
        const e = equipo(n.equipo ?? null);
        const rival = limpio(n.rival);
        if (!rival) throw malPedido("Escribí el rival.");
        if (rival.length > 60) throw malPedido("El nombre del rival es muy largo.");
        const torneo = limpio(n.torneo);
        if (torneo.length > 60) throw malPedido("El nombre del campeonato es muy largo.");
        const minutos = n.minutos == null ? 25 : n.minutos;
        if (minutos < 10 || minutos > 40) throw malPedido("Cada tiempo dura entre 10 y 40 minutos.");
        const fecha = n.fecha || hoy();
        const existente = partidos.find(p => p.equipoId === e.id && p.rival === rival && p.fecha === fecha);
        if (existente) return json(201, partidoView(existente, true));
        const cupo = (sesion.suscripcion || {}).partidosPorMes || 0;
        if (cupo > 0 && creados >= cupo) throw conflicto(`Ya cargaste los ${cupo} partidos de este mes.`);
        creados++;
        const p = {id: proximoPartido++, equipoId: e.id, idp: null, torneo: torneo || null, rival, fecha,
            local: n.local == null || n.local, minutosTiempo: minutos, estado: "PENDIENTE", tiempo: 1, segundos: 0,
            desde: null, cerrado: null, acciones: []};
        partidos.push(p);
        return json(201, partidoView(p, true));
    }

    function validarAccion(a, p) {
        if (!a || a.id == null || !UUID.test(a.id)) throw malPedido("Cada acción lleva su id (uuid).");
        if (!a.tipo) throw malPedido("Falta el tipo de la acción.");
        const admite = RESULTADOS[a.tipo];
        if (!admite) throw malPedido("El pedido no es válido.");
        const res = a.resultado ?? null;
        if (admite.length ? !admite.includes(res) : res !== null) throw malPedido(`El resultado ${res} no corresponde a ${a.tipo}.`);
        if (!(a.tiempo >= 1 && a.tiempo <= 2)) throw malPedido("El tiempo es 1 o 2.");
        if (!(a.segundo >= 0 && a.segundo <= duracion(p))) throw malPedido("El minuto está fuera del tiempo.");
    }

    /** Agrega una tanda de acciones; si una no va, no se guarda ninguna (como la transacción del servidor). */
    function agregar(p, nuevas) {
        if (p.estado === "CERRADO") throw conflicto("El partido está cerrado: reabrilo para cargar acciones.");
        const delEquipo = new Map(plantel(equipo(p.equipoId)).map(j => [j.id, j]));
        const tanda = [];
        for (const a of nuevas || []) {
            validarAccion(a, p);
            if (p.acciones.some(x => x.id === a.id) || tanda.some(x => x.id === a.id)) continue;
            let nombre;
            if (a.jugadorId > 0) {
                const j = delEquipo.get(a.jugadorId);
                if (!j) throw malPedido(`El jugador ${a.jugadorId} no está en el plantel del equipo.`);
                nombre = completo(j);
            } else {
                if (!a.jugadorNombre || !a.jugadorNombre.trim()) throw malPedido("Elegí el jugador de la acción.");
                nombre = a.jugadorNombre.trim();
            }
            tanda.push({id: a.id, jugadorId: Math.max(0, a.jugadorId), jugadorNombre: nombre, tipo: a.tipo,
                resultado: a.resultado ?? null, tiempo: a.tiempo, segundo: a.segundo});
        }
        p.acciones.push(...tanda);
        return json(200, partidoView(p, true));
    }

    function borrarAccion(p, accionId) {
        if (p.estado === "CERRADO") throw conflicto("El partido está cerrado: reabrilo para borrar acciones.");
        // Borrar una ya borrada no es un error (el teléfono puede reenviar el borrado).
        const a = p.acciones.find(x => x.id === accionId);
        if (!a) throw noExiste("No existe esa acción en el partido.");
        a.borrada = true;
        return json(200, partidoView(p, true));
    }

    function enJuego(p) {
        if (p.estado !== "EN_JUEGO") {
            throw conflicto(p.estado === "PENDIENTE" ? "Primero iniciá el partido."
                : p.estado === "ENTRETIEMPO" ? "Es el entretiempo: iniciá el 2T." : "El partido ya terminó.");
        }
    }

    function reloj(p, accion, segundos) {
        const t = ahora();
        switch (accion) {
            case "INICIAR":
                if (p.estado === "PENDIENTE") p.tiempo = 1;
                else if (p.estado === "ENTRETIEMPO") p.tiempo = 2;
                else throw conflicto("El partido ya está en juego o terminó.");
                p.estado = "EN_JUEGO"; p.segundos = 0; p.desde = t;
                break;
            case "PAUSAR":
                enJuego(p);
                if (p.desde == null) throw conflicto("El reloj ya está en pausa.");
                p.segundos = jugados(p); p.desde = null;
                break;
            case "REANUDAR":
                enJuego(p);
                if (p.desde != null) throw conflicto("El reloj ya está corriendo.");
                if (jugados(p) >= duracion(p)) throw conflicto("El tiempo ya terminó.");
                p.desde = t;
                break;
            case "FIN_TIEMPO":
                enJuego(p);
                p.segundos = duracion(p); p.desde = null; p.estado = p.tiempo === 1 ? "ENTRETIEMPO" : "TERMINADO";
                break;
            case "AJUSTAR": {
                enJuego(p);
                p.segundos = Math.max(0, Math.min(duracion(p), jugados(p) + (segundos || 0)));
                if (p.desde != null) p.desde = t;
                break;
            }
            default:
                throw malPedido(accion == null ? "Indicá la acción del reloj." : "El pedido no es válido.");
        }
        return json(200, partidoView(p, true));
    }

    function cerrar(p) {
        if (p.estado === "CERRADO") throw conflicto("El partido ya está cerrado.");
        if (p.desde != null) { p.segundos = jugados(p); p.desde = null; }
        p.estado = "CERRADO";
        p.cerrado = ahora();
        return json(200, partidoView(p, true));
    }

    function reabrir(p) {
        if (p.estado !== "CERRADO") throw conflicto("El partido no está cerrado.");
        if (ahora() - p.cerrado > REAPERTURA) throw conflicto("Pasaron más de 48 horas desde que se cerró: ya no se puede reabrir.");
        p.estado = "TERMINADO";
        p.cerrado = null;
        return json(200, partidoView(p, true));
    }

    // ---------- Informes (el mismo armado que la pantalla sin señal: informe.js) ----------

    function informe(titulo, detalle, unPartido, ps, jugador) {
        const ids = new Set(ps.map(p => p.id));
        const acciones = partidos.filter(p => ids.has(p.id)).sort((a, b) => a.id - b.id)
            .flatMap(p => enOrden(p).map(a => Object.assign({partidoId: p.id}, a)));
        const conocidos = activos().map(j => ({key: "j" + j.id, numero: j.numero || "", nombre: completo(j), arquero: j.arquero}));
        const vistas = ps.map(p => Object.assign(partidoView(p, false), {golesFavor: gf(p), golesContra: gc(p)}));
        const inf = Informe.armar({titulo, detalle, unPartido, partidos: vistas, acciones, jugadores: conocidos,
            jugador: jugador || null});
        if (jugador && !inf.jugador) throw malPedido("Ese jugador no tiene acciones en el informe.");
        return json(200, Object.assign({club: sesion.club.nombre, equipo: equipo(ps[0].equipoId).nombre, generado: hoy()}, inf));
    }

    function informePartido(p, jugador) {
        const e = equipo(p.equipoId);
        const detalle = [e.nombre, p.local ? "Local" : "Visitante", p.torneo, fechaCorta(p.fecha)].filter(Boolean).join(" · ");
        return informe(`${sesion.club.nombre} ${gf(p)} - ${gc(p)} ${p.rival}`, detalle, true, [p], jugador);
    }

    function informePeriodo(q) {
        const periodo = q.get("periodo");
        if (!periodo || !Informe.TIPOS[periodo]) throw malPedido("El pedido no es válido.");
        const e = equipo(q.get("equipo") ? Number(q.get("equipo")) : null);
        const v = (q.get("valor") || "").trim();
        const nombre = Informe.nombre(periodo, v);
        const ps = partidos.filter(p => p.equipoId === e.id && finalizado(p) && Informe.valor(periodo, p) === v)
            .sort((a, b) => a.fecha.localeCompare(b.fecha) || a.id - b.id);
        if (!ps.length) throw malPedido(`No hay partidos finalizados en ${nombre}.`);
        return informe(periodo === "CAMPEONATO" && v ? "Campeonato " + nombre : nombre,
            `${e.nombre} · ${ps.length}${ps.length === 1 ? " partido" : " partidos"}`, false, ps, q.get("jugador"));
    }

    // ---------- Rutas ----------

    function rutear(method, url, body) {
        const r = url.pathname, q = url.searchParams;
        const eq = () => equipo(q.get("equipo") ? Number(q.get("equipo")) : null);
        if (r === "/api/sesion") return json(200, sesion);
        if (r === "/api/dt/equipos" && method === "GET") return json(200, equipos.slice().sort((a, b) => a.id - b.id).map(equipoView));
        if (r === "/api/dt/equipos" && method === "POST") return crearEquipo(body || {});
        if (r === "/api/dt/club/categorias") return json(200, club.categorias);
        if (r === "/api/dt/club/plantel") {
            if (!q.get("categoria") || !q.get("rama")) throw malPedido("El pedido no es válido.");
            return json(200, plantelSidgad(q.get("categoria"), q.get("rama")));
        }
        const inc = r.match(/^\/api\/dt\/jugadores\/(\d+)\/equipos\/(\d+)$/);
        if (inc && method === "POST") return incluir(Number(inc[1]), Number(inc[2]));
        let m = r.match(/^\/api\/dt\/equipos\/(\d+)$/);
        if (m && method === "PUT") { const e = equipo(Number(m[1])); e.nombre = nombreEquipo((body || {}).nombre, e); return json(200, equipoView(e)); }
        if (m && method === "DELETE") return borrarEquipo(Number(m[1]));
        if (r === "/api/dt/jugadores" && method === "GET") return json(200, activos()
            .sort((a, b) => porTexto(a.apellido, b.apellido) || porTexto(a.nombres, b.nombres)).map(jugadorView));
        if (r === "/api/dt/jugadores" && method === "POST") return altaJugador(body || {});
        m = r.match(/^\/api\/dt\/jugadores\/(\d+)$/);
        if (m && method === "PUT") return modificarJugador(Number(m[1]), body || {});
        if (m && method === "DELETE") { const j = suyo(Number(m[1])); j.activo = false; j.equipos = []; return new Response(null, {status: 204}); }
        if (r === "/api/dt/plantel") return json(200, plantel(eq()).map(jugadorView));
        if (r === "/api/dt/acciones") {
            const e = eq();
            return json(200, partidos.filter(p => p.equipoId === e.id).sort((a, b) => a.id - b.id)
                .flatMap(p => partidoView(p, true).acciones.map(a => Object.assign({partidoId: p.id}, a))));
        }
        if (r === "/api/dt/partidos" && method === "GET") return json(200, partidos.slice()
            .sort((a, b) => b.fecha.localeCompare(a.fecha) || b.id - a.id).map(p => partidoView(p, false)));
        if (r === "/api/dt/partidos" && method === "POST") return crearPartido(body || {});
        if (r === "/api/dt/informes" && method === "GET") return informePeriodo(q);
        m = r.match(/^\/api\/dt\/partidos\/(\d+)(?:\/(acciones|reloj|cerrar|reabrir|informe))?(?:\/(.+))?$/);
        if (m) {
            const p = partido(Number(m[1]));
            if (!m[2] && method === "GET") return json(200, partidoView(p, true));
            if (!m[2] && method === "DELETE") { partidos.splice(partidos.indexOf(p), 1); return new Response(null, {status: 204}); }
            if (m[2] === "acciones" && method === "POST" && !m[3]) return agregar(p, body);
            if (m[2] === "acciones" && method === "DELETE" && m[3]) return borrarAccion(p, decodeURIComponent(m[3]));
            if (m[2] === "reloj" && method === "POST") return reloj(p, (body || {}).accion ?? null, (body || {}).segundos);
            if (m[2] === "cerrar" && method === "POST") return cerrar(p);
            if (m[2] === "reabrir" && method === "POST") return reabrir(p);
            if (m[2] === "informe" && method === "GET") return informePartido(p, q.get("jugador"));
        }
        return json(404, {detail: "No existe."});
    }

    /** Contesta un pedido: (método, url relativa o absoluta, cuerpo ya parseado) → Response. */
    return function api(method, url, body) {
        try {
            return rutear(method.toUpperCase(), new URL(url, "http://demo"), body);
        } catch (e) {
            if (e instanceof Rechazo) return json(e.status, {status: e.status, detail: e.message});
            throw e;
        }
    };
}
