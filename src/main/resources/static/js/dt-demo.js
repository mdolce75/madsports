// Demo de Métricas DT (/dt-demo.html): la misma pantalla de carga, con un partido de ejemplo y datos ficticios. Las
// llamadas a la API no salen del navegador: este archivo las contesta como lo haría el servidor (sesión, plantel,
// partido, acciones y reloj). Nada se guarda: al recargar, la demo vuelve a empezar.
(() => {
    const DUR = 25 * 60;
    const hoy = new Date().toISOString().slice(0, 10);
    const SESION = {
        autenticado: true, usuario: "demo", nombre: "DT", apellido: "Demo", email: null, perfiles: ["DTFPP"],
        club: {id: 0, nombre: "CLUB DEMO", codigo: "DEMO"}, jugador: null, inicio: "/dt-demo.html",
        suscripcion: {codigo: "DT_MENSUAL", nombre: "DT mensual", equiposMax: 2, partidosPorMes: 20},
        informesPedidos: 0, federacion: "FPP",
        equipos: [{categoria: "PRIMERA", rama: "MASCULINO", nombre: "Primera Masculino"}],
    };
    const PLANTEL = [
        [101, "1", "Martín Acosta", true], [102, "12", "Lucas Benítez", true], [103, "3", "Tomás Ferreyra", false],
        [104, "5", "Joaquín Ríos", false], [105, "7", "Facundo Medina", false], [106, "8", "Nicolás Sosa", false],
        [107, "9", "Agustín Paz", false], [108, "10", "Santiago Herrera", false], [109, "11", "Bruno Castro", false],
        [110, "14", "Mateo Giménez", false],
    ].map(([id, numero, nombre, arquero]) => ({id, numero, nombre, arquero}));
    const RIVALES = ["Atlético Norte", "Club Social Sur", "Deportivo Oeste", "Unión Patín", "Sporting Central"]
        .map((nombre, i) => ({clubId: 900 + i, nombre}));

    const nombreDe = id => (PLANTEL.find(j => j.id === id) || {}).nombre;
    let seq = 0;
    const accion = (jugadorId, tipo, resultado, tiempo, segundo) =>
        ({id: "demo-" + (++seq), jugadorId, jugadorNombre: nombreDe(jugadorId), tipo, resultado, tiempo, segundo});

    // Partido de ejemplo ya empezado: 1T, 12' jugados, con algunas acciones cargadas.
    const partidos = [{
        id: 1, categoria: "PRIMERA", rama: "MASCULINO", idp: null, torneo: null, rival: "Deportivo Oeste", fecha: hoy,
        local: true, minutosTiempo: 25, estado: "EN_JUEGO", tiempo: 1, segundos: 12 * 60 + 18, desde: Date.now(),
        acciones: [
            accion(104, "RECUPERACION", "RIVAL", 1, 95), accion(104, "TIRO", "GOL", 1, 101),
            accion(107, "PERDIDA", "PROPIO", 1, 212), accion(101, "ATAJADA", null, 1, 215),
            accion(108, "FALTA", "COMETIDA", 1, 288), accion(101, "LIBRE_CONTRA", "ATAJADO", 1, 290),
            accion(105, "TIRO", "NO_GOL", 1, 344), accion(106, "RECUPERACION", "PROPIO", 1, 402),
            accion(109, "TARJETA", "AZUL", 1, 455), accion(107, "TIRO", "GOL", 1, 610),
            accion(101, "GOL_RECIBIDO", null, 1, 668),
        ],
    }];

    // Historia de la demo: partidos ya jugados en los últimos meses (con acciones generadas siempre iguales) y dos
    // programados, para que el dashboard y las fichas de los jugadores tengan datos.
    let semilla = 7;
    const azar = () => (semilla = (semilla * 16807) % 2147483647) / 2147483647;
    const elegir = lista => lista[Math.floor(azar() * lista.length)];
    const dia = (meses, d) => { const x = new Date(); x.setMonth(x.getMonth() - meses); x.setDate(d); return x.toISOString().slice(0, 10); };
    const enDias = n => { const x = new Date(); x.setDate(x.getDate() + n); return x.toISOString().slice(0, 10); };
    const CAMPO = PLANTEL.filter(j => !j.arquero).map(j => j.id);
    const TIPOS = [["PERDIDA", "PROPIO"], ["PERDIDA", "RIVAL"], ["RECUPERACION", "PROPIO"], ["RECUPERACION", "RIVAL"],
        ["RECUPERACION", "RIVAL"], ["TIRO", "GOL"], ["TIRO", "NO_GOL"], ["TIRO", "NO_GOL"], ["FALTA", "COMETIDA"],
        ["FALTA", "RECIBIDA"], ["LIBRE_FAVOR", "NO_GOL"], ["TARJETA", "AMARILLA"]];
    function jugado(id, rival, fecha, local) {
        const acciones = [];
        for (let i = 0; i < 26; i++) {
            const [tipo, res] = elegir(TIPOS);
            // Los delanteros (7, 9, 10) tiran más; el resto recupera más.
            const j = tipo === "TIRO" ? elegir([105, 107, 108, 108, 109]) : elegir(CAMPO);
            acciones.push(accion(j, tipo, res, 1 + (i % 2), Math.floor(azar() * DUR)));
        }
        for (let i = 0; i < 4; i++) acciones.push(accion(elegir([101, 101, 102]), elegir(["ATAJADA", "ATAJADA", "GOL_RECIBIDO"]), null, 1 + (i % 2), Math.floor(azar() * DUR)));
        return {id, categoria: "PRIMERA", rama: "MASCULINO", idp: null, torneo: null, rival, fecha, local,
            minutosTiempo: 25, estado: "CERRADO", tiempo: 2, segundos: DUR, desde: null, acciones};
    }
    partidos.push(
        jugado(2, "Atlético Norte", dia(1, 20), false), jugado(3, "Unión Patín", dia(1, 6), true),
        jugado(4, "Sporting Central", dia(2, 24), true), jugado(5, "Club Social Sur", dia(2, 9), false),
        jugado(6, "Deportivo Oeste", dia(3, 15), false), jugado(7, "Atlético Norte", dia(4, 18), true),
        {id: 8, categoria: "PRIMERA", rama: "MASCULINO", idp: null, torneo: null, rival: "Club Social Sur", fecha: enDias(3),
            local: true, minutosTiempo: 25, estado: "PENDIENTE", tiempo: 1, segundos: 0, desde: null, acciones: []},
        {id: 9, categoria: "PRIMERA", rama: "MASCULINO", idp: null, torneo: null, rival: "Unión Patín", fecha: enDias(10),
            local: false, minutosTiempo: 25, estado: "PENDIENTE", tiempo: 1, segundos: 0, desde: null, acciones: []});

    const golAFavor = a => a.resultado === "GOL" && ["TIRO", "LIBRE_FAVOR", "PENAL_FAVOR"].includes(a.tipo);
    const golEnContra = a => a.tipo === "GOL_RECIBIDO" || a.resultado === "GOL" && ["LIBRE_CONTRA", "PENAL_CONTRA"].includes(a.tipo);
    const jugados = p => Math.max(0, Math.min(DUR, p.segundos + (p.desde ? Math.floor((Date.now() - p.desde) / 1000) : 0)));

    function vista(p, conAcciones = true) {
        const j = jugados(p);
        return {
            id: p.id, categoria: p.categoria, rama: p.rama, idp: p.idp, torneo: p.torneo, rival: p.rival, fecha: p.fecha,
            local: p.local, minutosTiempo: p.minutosTiempo, estado: p.estado,
            golesFavor: p.acciones.filter(golAFavor).length, golesContra: p.acciones.filter(golEnContra).length,
            reloj: {tiempo: p.tiempo, jugados: j, restante: DUR - j, corriendo: !!p.desde},
            acciones: conAcciones ? p.acciones.slice().sort((a, b) => a.tiempo - b.tiempo || a.segundo - b.segundo) : [],
        };
    }

    function reloj(p, accionReloj, segundos) {
        const ahora = Date.now();
        const enJuego = () => { if (p.estado !== "EN_JUEGO") throw [409, "Primero iniciá el partido."]; };
        switch (accionReloj) {
            case "INICIAR":
                if (p.estado === "PENDIENTE") p.tiempo = 1;
                else if (p.estado === "ENTRETIEMPO") p.tiempo = 2;
                else throw [409, "El partido ya está en juego o terminó."];
                p.estado = "EN_JUEGO"; p.segundos = 0; p.desde = ahora; break;
            case "PAUSAR": enJuego(); p.segundos = jugados(p); p.desde = null; break;
            case "REANUDAR": enJuego(); p.desde = ahora; break;
            case "FIN_TIEMPO":
                enJuego(); p.segundos = DUR; p.desde = null; p.estado = p.tiempo === 1 ? "ENTRETIEMPO" : "TERMINADO"; break;
            case "AJUSTAR": {
                enJuego();
                const corriendo = !!p.desde;
                p.segundos = Math.max(0, Math.min(DUR, jugados(p) + (segundos || 0)));
                p.desde = corriendo ? ahora : null;
                break;
            }
        }
    }

    const json = (status, body) => new Response(JSON.stringify(body), {status, headers: {"Content-Type": "application/json"}});

    function api(method, path, body) {
        const url = new URL(path, location.origin);
        const r = url.pathname;
        if (r === "/api/sesion") return json(200, SESION);
        if (r === "/api/logout") { location.replace("/dt-inicio.html"); return json(200, {}); }
        if (r === "/api/dt/rivales") return json(200, RIVALES);
        if (r === "/api/dt/plantel") return json(200, PLANTEL);
        if (r === "/api/dt/partidos" && method === "GET") return json(200, partidos.slice()
            .sort((a, b) => b.fecha.localeCompare(a.fecha) || b.id - a.id).map(p => vista(p, false)));
        if (r === "/api/dt/acciones") return json(200, partidos.flatMap(p => p.acciones.map(a => Object.assign({partidoId: p.id}, a))));
        if (r === "/api/dt/partidos" && method === "POST") {
            const n = {id: Math.max(...partidos.map(x => x.id)) + 1, categoria: "PRIMERA", rama: "MASCULINO", idp: null, torneo: null,
                rival: body.rival, fecha: body.fecha || hoy, local: body.local !== false, minutosTiempo: 25,
                estado: "PENDIENTE", tiempo: 1, segundos: 0, desde: null, acciones: []};
            partidos.unshift(n);
            return json(201, vista(n));
        }
        const m = r.match(/^\/api\/dt\/partidos\/(\d+)(?:\/(acciones|reloj|cerrar|reabrir))?(?:\/(.+))?$/);
        const p = m && partidos.find(x => x.id === Number(m[1]));
        if (!p) return json(404, {detail: "No existe ese partido."});
        try {
            if (!m[2]) return json(200, vista(p));
            if (m[2] === "acciones" && method === "POST") {
                if (p.estado === "CERRADO") throw [409, "El partido está cerrado: reabrilo para cargar acciones."];
                for (const a of body) {
                    if (!p.acciones.some(x => x.id === a.id)) p.acciones.push(Object.assign({}, a,
                        {jugadorNombre: a.jugadorId > 0 ? nombreDe(a.jugadorId) : a.jugadorNombre}));
                }
                return json(200, vista(p));
            }
            if (m[2] === "acciones" && method === "DELETE") {
                p.acciones = p.acciones.filter(x => x.id !== decodeURIComponent(m[3]));
                return json(200, vista(p));
            }
            if (m[2] === "reloj") { reloj(p, body.accion, body.segundos); return json(200, vista(p)); }
            if (m[2] === "cerrar") { p.segundos = jugados(p); p.desde = null; p.estado = "CERRADO"; return json(200, vista(p)); }
            if (m[2] === "reabrir") { p.estado = "TERMINADO"; return json(200, vista(p)); }
        } catch (e) {
            if (Array.isArray(e)) return json(e[0], {detail: e[1]});
            throw e;
        }
        return json(404, {detail: "No existe."});
    }

    // Todas las llamadas a /api/ las contesta la demo; el resto (fuentes, estilos) va a la red como siempre.
    const red = window.fetch.bind(window);
    window.fetch = async (input, init = {}) => {
        const url = typeof input === "string" ? input : input.url;
        if (!new URL(url, location.origin).pathname.startsWith("/api/")) return red(input, init);
        await new Promise(r => setTimeout(r, 60));   // como una respuesta real, un instante después
        return api((init.method || "GET").toUpperCase(), url, init.body ? JSON.parse(init.body) : null);
    };

    // La demo abre directamente el partido de ejemplo (o la pestaña pedida: ?tab=resumen).
    if (!location.hash) history.replaceState(null, "", location.pathname + location.search + "#partido=1");
    const tab = new URLSearchParams(location.search).get("tab");
    if (tab) document.addEventListener("DOMContentLoaded", () => setTimeout(() => {
        document.querySelector("[aria-label^='9 '], [aria-label^='10 ']")?.click();
        $("t-" + tab)?.click();
    }, 700));
    document.addEventListener("DOMContentLoaded", () => setTimeout(() => {
        // Un jugador ya elegido, para que se vea la selección.
        if (!document.querySelector(".dt-player[aria-pressed='true']")) document.querySelector("[aria-label^='10 ']")?.click();
    }, 600));
})();
