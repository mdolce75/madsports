// Métricas DT en vivo: elegir el partido, manejar el reloj y cargar las acciones de cada jugador. Las acciones pasan
// por DtCola (dt-cola.js), así se guardan aunque no haya señal; el reloj sí necesita conexión.
(() => {
    // Colores de los botones: cada acción el suyo (texto blanco; la amarilla, oscuro). Defensa = campo propio, ataque =
    // campo rival. Los mismos colores marcan cada acción en la lista de últimas acciones.
    const C = {
        perdDef: "#b91c1c", perdAtq: "#c2410c", recDef: "#15803d", recAtq: "#4d7c0f",
        gol: "#0f766e", noGol: "#52525b", atajada: "#1d4ed8", golRecibido: "#9f1239",
        faltaCom: "#6d28d9", faltaRec: "#0369a1", amarilla: "#facc15", azul: "#1d4ed8", roja: "#dc2626",
    };
    // Acciones de Métricas DT. arco: se cargan al arquero en cancha. Las de a pares (gol / no gol) van en una tarjeta.
    const SIMPLES = [
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
    const PARES = [
        Object.assign({g: "g-arco", tipo: "TIRO", t: "Tiro al arco", quien: ""}, A_FAVOR),
        Object.assign({g: "g-parada", tipo: "LIBRE_FAVOR", t: "Libre a favor", quien: "pateador"}, A_FAVOR),
        Object.assign({g: "g-parada", tipo: "LIBRE_CONTRA", t: "Libre en contra", quien: "arquero", arco: true}, EN_CONTRA),
        Object.assign({g: "g-parada", tipo: "PENAL_FAVOR", t: "Penal a favor", quien: "pateador"}, A_FAVOR),
        Object.assign({g: "g-parada", tipo: "PENAL_CONTRA", t: "Penal en contra", quien: "arquero", arco: true}, EN_CONTRA),
    ];

    /** Nombre completo de una acción ("Pérdida en ataque", "Penal a favor: Gol") y su color. */
    function accion(tipo, res) {
        const s = SIMPLES.find(x => x.tipo === tipo && x.res === (res || null));
        if (s) return {nombre: s.t, col: s.col};
        const p = PARES.find(x => x.tipo === tipo);
        const r = p && [p.si, p.no].find(x => x[0] === res);
        return r ? {nombre: `${p.t}: ${r[1]}`, col: r[2]} : {nombre: tipo, col: "var(--muted)"};
    }
    const ARCO = new Set(["ATAJADA", "GOL_RECIBIDO", "LIBRE_CONTRA", "PENAL_CONTRA"]);
    const ESTADO = {PENDIENTE: "Sin iniciar", EN_JUEGO: "En juego", ENTRETIEMPO: "Entretiempo", TERMINADO: "Terminado", CERRADO: "Cerrado"};

    const golAFavor = a => a.resultado === "GOL" && ["TIRO", "LIBRE_FAVOR", "PENAL_FAVOR"].includes(a.tipo);
    const golEnContra = a => a.tipo === "GOL_RECIBIDO" || a.resultado === "GOL" && ["LIBRE_CONTRA", "PENAL_CONTRA"].includes(a.tipo);

    let sesion = null;
    let equipos = [];   // equipos del DT (sección Equipo): [{id, nombre, jugados, ganados, empatados, perdidos}]
    // Partido abierto: lo que devolvió el servidor, cuándo (para el reloj), plantel, pendientes de la cola.
    let partido = null, recibido = 0, plantel = [], pend = [], pendientesTotal = 0;
    let seleccionado = null, arquero = null;
    let jugadorInforme = null;   // jugador elegido en el informe del partido (null: todo el equipo)
    let avisado = "";   // tiempo cuyo 00:00 ya se avisó
    let wakeLock = null;

    /** Fecha de hoy en el teléfono (AAAA-MM-DD, hora local). */
    const hoy = () => { const d = new Date(); return new Date(d - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10); };
    const mmss = s => String(Math.floor(s / 60)).padStart(2, "0") + ":" + String(s % 60).padStart(2, "0");
    const diaMes = f => f ? f.slice(8, 10) + "/" + f.slice(5, 7) : "";
    const corto = n => { const w = (n || "").trim().split(/\s+/); return w[w.length - 1] || n; };
    const el = (tag, cls, txt) => { const e = document.createElement(tag); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; };

    /** GET a la API; {data, at}: at es cuándo se armó la respuesta (sin señal, la copia guardada es más vieja). */
    async function getJson(url) {
        const res = await fetch(url, {credentials: "same-origin"});
        const data = await res.json().catch(() => ({}));
        if (!res.ok) { const e = new Error(data.detail || res.statusText); e.status = res.status; throw e; }
        return {data, at: Number(res.headers.get("X-Guardado")) || Date.now()};
    }

    // ---------- Equipos del DT ----------

    async function traerEquipos() {
        equipos = (await getJson("/api/dt/equipos")).data;
        return equipos;
    }

    /** Llena un combo de equipos conservando el elegido; con un solo equipo, el combo se oculta (si ocultar). */
    function llenarEquipos(sel, ocultar = true) {
        const antes = sel.value;
        sel.replaceChildren(...equipos.map(e => { const o = el("option", null, e.nombre); o.value = String(e.id); return o; }));
        if (equipos.some(e => String(e.id) === antes)) sel.value = antes;
        if (ocultar) sel.hidden = equipos.length < 2;
    }

    /** El equipo elegido en un combo (o el primero). */
    const equipoDe = sel => equipos.find(e => String(e.id) === sel.value) || equipos[0];

    const nombreEquipo = p => p.equipo || "";

    // ---------- Partidos: nuevo partido y lista ----------

    function rivalElegido() {
        return $("rival").value.trim();
    }

    async function crear() {
        const e = equipoDe($("equipo"));
        if (!e) { status("Primero creá un equipo en Equipo.", true); return; }
        const body = {equipo: e.id, rival: rivalElegido(), fecha: $("fecha").value || null,
            local: $("local").value === "true", torneo: $("torneo").value.trim() || null, minutos: Number($("minutos").value)};
        if (!body.rival) { status("Escribí el rival.", true); return; }
        $("crear").disabled = true;
        status("Creando el partido…");
        try {
            const p = await postJson("/api/dt/partidos", body);
            status("");
            location.hash = "partido=" + p.id;
        } catch (err) {
            status(err instanceof TypeError ? "Sin señal: para empezar un partido hace falta conexión." : err.message, true);
        } finally {
            $("crear").disabled = !rivalElegido() || !equipos.length;
        }
    }

    async function cargarMis() {
        const ul = $("mis");
        try {
            const [lista] = await Promise.all([getJson("/api/dt/partidos").then(r => r.data), traerEquipos()]);
            llenarEquipos($("equipo"), false);
            $("sin-equipos").hidden = equipos.length > 0;
            $("equipo").disabled = !equipos.length;
            $("crear").disabled = !rivalElegido() || !equipos.length;
            // Los campeonatos y rivales ya usados, para elegirlos al crear el próximo partido.
            const opciones = valores => [...new Set(valores.filter(Boolean))].map(t => { const o = el("option"); o.value = t; return o; });
            $("torneos").replaceChildren(...opciones(lista.map(p => p.torneo)));
            $("rivales").replaceChildren(...opciones(lista.map(p => p.rival)));
            ul.replaceChildren();
            $("mis-n").textContent = lista.length ? String(lista.length) : "";
            if (!lista.length) ul.append(el("li", "dt-vacio", "Todavía no cargaste partidos."));
            for (const p of lista) {
                const li = el("li", "con-borrar");
                const b = el("button");
                b.type = "button";
                const t = el("span", null, `${diaMes(p.fecha)} · ${p.rival}`);
                const r = el("b", null, `${p.golesFavor} - ${p.golesContra}`);
                const sub = el("small", null, [nombreEquipo(p), p.local ? "Local" : "Visitante", p.torneo, ESTADO[p.estado]]
                    .filter(Boolean).join(" · "));
                b.append(t, r, sub);
                b.addEventListener("click", () => { location.hash = "partido=" + p.id; });
                const del = el("button", "dt-borrar", "Eliminar");
                del.type = "button";
                del.setAttribute("aria-label", `Eliminar el partido contra ${p.rival} del ${diaMes(p.fecha)}`);
                del.addEventListener("click", () => eliminarPartido(p));
                li.append(b, del);
                ul.append(li);
            }
        } catch (err) {
            ul.replaceChildren(el("li", "dt-vacio", "No se pudo cargar la lista: " + err.message));
        }
    }

    /** Elimina el partido con todas sus acciones (no se puede deshacer). */
    async function eliminarPartido(p) {
        if (!confirm(`¿Eliminar el partido contra ${p.rival} del ${diaMes(p.fecha)}? Se borran todas sus acciones y deja `
            + "de contar en las estadísticas y los informes. No se puede deshacer.")) return;
        try {
            const res = await api(`/api/dt/partidos/${p.id}`, {method: "DELETE"});
            if (!res.ok) { const data = await res.json().catch(() => ({})); throw new Error(data.detail || res.statusText); }
            toast(`Partido contra ${p.rival} eliminado.`);
            cargarMis();
        } catch (err) {
            toast(err instanceof TypeError ? "Sin señal: para eliminar un partido hace falta conexión." : err.message);
        }
    }

    // ---------- Partido abierto ----------

    async function abrir(id) {
        $("v-elegir").hidden = true;
        $("v-partido").hidden = false;
        document.body.classList.add("dt-vivo");
        tab("t-cargar");
        try {
            const r = await getJson(`/api/dt/partidos/${id}`);
            partido = r.data;
            recibido = r.at;
            pend = [];
            seleccionado = null;
            jugadorInforme = null;
            delete $("informe-vivo").dataset.firma;
            delete $("jugadores").dataset.firma;
            delete $("log").dataset.firma;
            avisado = "";
            try {
                plantel = (await getJson(`/api/dt/plantel?equipo=${r.data.equipoId}`)).data;
            } catch (e) {
                plantel = [];
            }
            plantel.sort((a, b) => (parseInt(a.numero) || 999) - (parseInt(b.numero) || 999));
            arquero = leer("ar", null) || (plantel.find(j => j.arquero) ? "j" + plantel.find(j => j.arquero).id : null);
            recibir(r.data, r.at);
            await refrescarPend();
            window.scrollTo(0, 0);
        } catch (err) {
            toast(err.status === 404 ? "No existe ese partido." : "No se pudo abrir el partido: " + err.message);
            location.hash = "partidos";
        }
    }

    /** Lo que devuelve el servidor (al abrir, al mover el reloj, al mandar acciones). */
    function recibir(p, at) {
        if (partido && p.id !== partido.id) return;
        partido = p;
        recibido = at;
        render();
    }

    async function refrescarPend() {
        if (!partido) return;
        pend = await DtCola.pendientes(partido.id);
        render();
    }

    // Lo guardado en el teléfono por partido: jugadores agregados a mano y el arquero en cancha.
    function leer(k, def) {
        try { const v = JSON.parse(localStorage.getItem(`dt-${k}-${partido.id}`)); return v == null ? def : v; }
        catch (e) { return def; }
    }
    function guardar(k, v) {
        try { localStorage.setItem(`dt-${k}-${partido.id}`, JSON.stringify(v)); } catch (e) { /* sin almacenamiento */ }
    }

    /** Segundos jugados del tiempo, contando desde la última respuesta del servidor. */
    function jugados() {
        if (!partido) return 0;
        const dur = partido.minutosTiempo * 60, r = partido.reloj;
        const extra = r.corriendo ? Math.floor((Date.now() - recibido) / 1000) : 0;
        return Math.max(0, Math.min(dur, r.jugados + extra));
    }

    /** Acciones activas: las del servidor sin las borradas en la cola, más las que todavía no se mandaron. */
    function acciones() {
        const bajas = new Set(pend.filter(o => o.tipo === "baja").map(o => o.accionId));
        const lista = partido.acciones.filter(a => !bajas.has(a.id));
        const ids = new Set(lista.map(a => a.id));
        for (const o of pend) if (o.tipo === "alta" && !ids.has(o.accion.id)) lista.push(Object.assign({pendiente: true}, o.accion));
        return lista.sort((a, b) => a.tiempo - b.tiempo || a.segundo - b.segundo);
    }

    const clave = a => a.jugadorId > 0 ? "j" + a.jugadorId : "m" + a.jugadorNombre;

    function jugadores() {
        const lista = plantel.map(j => ({key: "j" + j.id, id: j.id, numero: j.numero, nombre: j.nombre, arquero: j.arquero}));
        const extras = leer("extras", []);
        for (const x of extras) lista.push({key: "m" + x.nombre, id: 0, numero: x.numero, nombre: x.nombre, arquero: false});
        for (const a of partido.acciones) {
            if (a.jugadorId === 0 && !lista.some(j => j.key === "m" + a.jugadorNombre)) {
                lista.push({key: "m" + a.jugadorNombre, id: 0, numero: "", nombre: a.jugadorNombre, arquero: false});
            }
        }
        return lista;
    }

    function render() {
        if (!partido) return;
        $("m-propio").textContent = sesion.club.nombre;
        $("m-propio-sub").textContent = nombreEquipo(partido);
        $("m-rival").textContent = partido.rival;
        $("m-rival-sub").textContent = [partido.local ? "Local" : "Visitante", partido.torneo].filter(Boolean).join(" · ");
        const acc = acciones();
        $("m-gf").textContent = acc.filter(golAFavor).length;
        $("m-gc").textContent = acc.filter(golEnContra).length;
        renderReloj();
        renderBotones();
        renderSync();
        renderJugadores();
        renderAcciones(acc);
        renderLog(acc);
        renderResumen(acc);
    }

    function renderReloj() {
        if (!partido) return;
        const dur = partido.minutosTiempo * 60, j = jugados();
        const e = partido.estado;
        $("m-tiempo").textContent = e === "PENDIENTE" ? "1T" : e === "ENTRETIEMPO" ? "ET"
            : e === "TERMINADO" || e === "CERRADO" ? "FIN" : partido.reloj.tiempo + "T";
        $("m-reloj").textContent = mmss(dur - j);
        $("m-reloj").classList.toggle("on", partido.reloj.corriendo);
        const fin = e === "EN_JUEGO" && j >= dur;
        const k = partido.id + ":" + partido.reloj.tiempo;
        if (fin && avisado !== k) {
            avisado = k;
            if (navigator.vibrate) navigator.vibrate([400, 150, 400]);
            aviso(`Terminó el ${partido.reloj.tiempo}T: tocá «${partido.reloj.tiempo === 1 ? "Fin del 1T" : "Fin del partido"}».`);
            renderBotones();
        } else if (!fin && $("m-aviso").dataset.tipo === "fin") {
            aviso("");
        }
    }

    function aviso(txt) {
        $("m-aviso").hidden = !txt;
        $("m-aviso").textContent = txt;
        $("m-aviso").dataset.tipo = txt ? "fin" : "";
    }

    function renderBotones() {
        const box = $("m-botones");
        box.replaceChildren();
        const r = partido.reloj, e = partido.estado;
        const boton = (txt, accion, cls, confirmar) => {
            const b = el("button", "dt-play " + (cls || ""), txt);
            b.type = "button";
            b.addEventListener("click", () => {
                if (confirmar && !confirm(confirmar)) return;
                reloj(accion);
            });
            box.append(b);
        };
        if (e === "PENDIENTE") boton("Iniciar partido", "INICIAR");
        if (e === "EN_JUEGO") {
            const queda = jugados() < partido.minutosTiempo * 60;
            if (r.corriendo && queda) boton("Pausar", "PAUSAR", "on");
            else if (!r.corriendo && queda) boton("Reanudar", "REANUDAR");
            boton(r.tiempo === 1 ? "Fin del 1T" : "Fin del partido", "FIN_TIEMPO", "ghost",
                r.tiempo === 1 ? "¿Terminar el primer tiempo?" : "¿Terminar el partido?");
        }
        if (e === "ENTRETIEMPO") boton("Iniciar 2T", "INICIAR");
        if (e === "TERMINADO") {
            const b = el("button", "dt-play ghost", "Cerrar partido");
            b.type = "button";
            b.addEventListener("click", cerrar);
            box.append(b);
        }
        $("m-ajuste").hidden = e !== "EN_JUEGO";
        actualizarWakeLock();
    }

    /**
     * POST del partido abierto. "Sin señal" solo si el pedido no llegó al servidor (fetch falla con TypeError); un
     * error del servidor muestra su detalle. Lo que pasa después (pintar la respuesta) queda fuera del try: un error
     * de la pantalla no se confunde con falta de señal.
     */
    async function enviarPartido(ruta, body, sinSenal) {
        let p;
        try {
            p = await postJson(`/api/dt/partidos/${partido.id}/${ruta}`, body);
        } catch (err) {
            toast(err instanceof TypeError ? sinSenal : err.message);
            return;
        }
        const antes = partido.estado;
        recibir(p, Date.now());
        // Al terminar el partido (fin del 2T), directo al informe.
        if (antes === "EN_JUEGO" && p.estado === "TERMINADO") {
            tab("t-resumen");
            window.scrollTo(0, 0);
        }
    }

    function reloj(accion, segundos) {
        return enviarPartido("reloj", {accion, segundos},
            "Sin señal: el reloj necesita conexión. Las acciones se siguen guardando.");
    }

    async function cerrar() {
        if (!confirm("¿Cerrar el partido? Ya no se podrán cargar acciones (se puede reabrir durante 48 horas).")) return;
        if (pend.length) { toast("Esperá a que se envíe lo cargado antes de cerrar."); DtCola.enviar(); return; }
        await enviarPartido("cerrar", {}, "Sin señal: para cerrar el partido hace falta conexión.");
    }

    function reabrir() {
        return enviarPartido("reabrir", {}, "Sin señal: para reabrir hace falta conexión.");
    }

    function renderSync() {
        const n = pendientesTotal;
        const s = $("m-sync");
        s.className = "dt-sync" + (n ? " pend" : "") + (navigator.onLine ? "" : " off");
        s.textContent = (navigator.onLine ? "" : "Sin señal · ") + (n ? `Sin enviar: ${n}` : navigator.onLine ? "Todo enviado" : "nada pendiente");
    }

    // Los botones se arman una sola vez (o cuando cambia el plantel) y después solo se actualizan: si se rearmaran con
    // cada respuesta del servidor, un toque justo en ese momento se perdería.
    function renderJugadores() {
        const box = $("jugadores");
        const lista = jugadores();
        const firma = lista.map(j => `${j.key}|${j.numero}|${j.arquero}`).join(";");
        if (box.dataset.firma !== firma) {
            box.dataset.firma = firma;
            box.replaceChildren();
            if (!lista.length) {
                const p = el("p", "dt-note");
                const a = el("a", null, "Equipo");
                a.href = "#equipo";
                p.append("Este equipo no tiene jugadores: cargalos en ", a, ", o agregá uno solo para este partido.");
                box.append(p);
            }
            for (const j of lista) {
                const b = el("button", "dt-player" + (j.arquero ? " gk" : ""));
                b.type = "button";
                b.dataset.key = j.key;
                // Lo principal es el nombre (completo, en una o dos líneas); el número va chico, arriba.
                const arriba = el("span", "cab");
                arriba.append(el("span", "n", j.numero ? "#" + j.numero : "s/n"));
                if (j.arquero) arriba.append(el("span", "ar", "Arquero"));
                b.append(arriba, el("span", "s", j.nombre));
                b.addEventListener("click", () => { seleccionado = j.key; renderJugadores(); });
                box.append(b);
            }
        }
        for (const b of box.querySelectorAll(".dt-player")) {
            const j = lista.find(x => x.key === b.dataset.key);
            b.classList.toggle("oncourt", j.key === arquero);
            b.setAttribute("aria-pressed", String(j.key === seleccionado));
            b.setAttribute("aria-label", `${j.numero} ${j.nombre}${j.arquero ? ", arquero" : ""}${j.key === arquero ? ", en cancha" : ""}`);
        }
        const sel = lista.find(j => j.key === seleccionado), ar = lista.find(j => j.key === arquero);
        $("sel").textContent = sel ? `${sel.nombre}${sel.numero ? " (#" + sel.numero + ")" : ""}` : "—";
        $("ar").textContent = ar ? `${ar.nombre}${ar.numero ? " (#" + ar.numero + ")" : ""}` : "elegir";
    }

    /** Contadores de los botones de acción: [span, función que da el texto con las acciones del partido]. */
    let contadores = null;

    function renderAcciones(acc) {
        if (!contadores) {
            contadores = [];
            for (const p of PARES.filter(x => x.g === "g-arco")) $(p.g).append(par(p));
            for (const a of SIMPLES) {
                const b = el("button", "dt-act" + (a.oscuro ? " oscuro" : ""));
                b.type = "button";
                b.style.setProperty("--c", a.col);
                const c = el("span", "c");
                b.append(el("span", "t", a.t));
                if (a.d) b.append(el("span", "d", a.d));
                b.append(c);
                b.setAttribute("aria-label", a.t);
                b.addEventListener("click", () => cargar(a.tipo, a.res));
                contadores.push([c, l => `Partido: ${cuenta(l, a.tipo, a.res)}`]);
                $(a.g).append(b);
            }
            for (const p of PARES.filter(x => x.g !== "g-arco")) $(p.g).append(par(p));
        }
        for (const [span, texto] of contadores) span.textContent = texto(acc);
    }

    const cuenta = (acc, tipo, res) => acc.filter(a => a.tipo === tipo && (res === undefined || a.resultado === res)).length;

    function par(p) {
        const card = el("div", "dt-pair");
        const t = el("div", "t");
        const c = el("small");
        t.append(el("span", null, p.t), c);
        contadores.push([c, l => `${cuenta(l, p.tipo, p.si[0])} / ${cuenta(l, p.tipo)}${p.quien ? " · " + p.quien : ""}`]);
        const btns = el("div", "btns");
        for (const [res, label, col] of [p.si, p.no]) {
            const b = el("button", null, label);
            b.type = "button";
            b.style.setProperty("--c", col);
            b.setAttribute("aria-label", `${p.t}: ${label}`);
            b.addEventListener("click", () => cargar(p.tipo, res));
            btns.append(b);
        }
        card.append(t, btns);
        return card;
    }

    let toastTimer;
    function toast(txt, deshacer) {
        $("toast-txt").textContent = txt;
        $("toast-undo").hidden = !deshacer;
        $("toast-undo").onclick = deshacer ? () => { $("toast").hidden = true; deshacer(); } : null;
        $("toast").hidden = false;
        clearTimeout(toastTimer);
        toastTimer = setTimeout(() => { $("toast").hidden = true; }, deshacer ? 4000 : 5000);
    }

    async function cargar(tipo, resultado) {
        if (partido.estado === "CERRADO") { toast("El partido está cerrado: reabrilo para cargar acciones."); return; }
        const lista = jugadores();
        const key = ARCO.has(tipo) && arquero ? arquero : seleccionado;
        const j = lista.find(x => x.key === key);
        if (!j) { toast(ARCO.has(tipo) ? "Elegí el arquero en cancha o tocá un jugador." : "Tocá primero el jugador."); return; }
        const r = partido.reloj;
        const nueva = {id: DtCola.uuid(), jugadorId: j.id, jugadorNombre: j.nombre, tipo, resultado,
            tiempo: partido.estado === "PENDIENTE" ? 1 : r.tiempo, segundo: jugados()};
        if (navigator.vibrate) navigator.vibrate(15);
        await DtCola.agregar(partido.id, nueva);
        await refrescarPend();
        toast(`${accion(tipo, resultado).nombre} · ${j.numero ? j.numero + " " : ""}${corto(j.nombre)}`,
            () => borrar(nueva.id));
    }

    async function borrar(id) {
        await DtCola.borrar(partido.id, id);
        await refrescarPend();
    }

    function renderLog(acc) {
        const ul = $("log");
        const ultimas = acc.slice().reverse().slice(0, 10);
        const firma = partido.estado + ":" + ultimas.map(a => a.id + (a.pendiente ? "*" : "")).join(",");
        if (ul.dataset.firma === firma) return;
        ul.dataset.firma = firma;
        ul.replaceChildren();
        if (!ultimas.length) ul.append(el("li", "dt-vacio", "Todavía no hay acciones: tocá un jugador y después la acción."));
        const nums = new Map(jugadores().map(j => [j.key, j.numero]));
        for (const a of ultimas) {
            const li = el("li");
            const m = el("span", "m", `${a.tiempo}T ${mmss(partido.minutosTiempo * 60 - a.segundo)}`);
            const w = el("span", "w");
            const def = accion(a.tipo, a.resultado);
            const tag = el("span", "dt-tag");
            tag.style.background = def.col;
            w.append(tag, def.nombre);
            const n = nums.get(clave(a));
            w.append(el("small", null, `${n ? n + " " : ""}${a.jugadorNombre}${a.pendiente ? " · sin enviar" : ""}`));
            const del = el("button", null, "Borrar");
            del.type = "button";
            del.setAttribute("aria-label", `Borrar ${def.nombre} de ${a.jugadorNombre}`);
            del.disabled = partido.estado === "CERRADO";
            del.addEventListener("click", () => borrar(a.id));
            li.append(m, w, del);
            ul.append(li);
        }
    }

    /** Pestaña Informe: el informe del partido en vivo (con lo que todavía no se envió), por equipo o por jugador. */
    function renderResumen(acc) {
        const box = $("informe-vivo");
        const firma = [partido.id, partido.estado, jugadorInforme, acc.map(a => a.id + (a.pendiente ? "*" : "")).join(",")].join("|");
        if (box.dataset.firma !== firma) {
            box.dataset.firma = firma;
            const p = Object.assign({}, partido, {golesFavor: acc.filter(golAFavor).length, golesContra: acc.filter(golEnContra).length});
            const lista = jugadores();
            const armar = jugador => DtInforme.armar({titulo: `${sesion.club.nombre} ${p.golesFavor} - ${p.golesContra} ${p.rival}`,
                detalle: detallePartido(p), unPartido: true, partidos: [p], acciones: acc, jugadores: lista, jugador,
                nombreAccion: accion});
            const inf = armar(jugadorInforme);
            DtInforme.render(box, inf, {
                jugadores: jugadorInforme ? armar(null).jugadores : inf.jugadores,
                onJugador: k => { jugadorInforme = k; renderResumen(acciones()); },
                onPdf: () => {
                    if (pend.length) { toast("Esperá a que se envíe lo cargado: el PDF se arma con lo guardado en el servidor."); DtCola.enviar(); return; }
                    exportarPdf(`/api/dt/partidos/${partido.id}/informe.pdf` + (jugadorInforme ? "?jugador=" + encodeURIComponent(jugadorInforme) : ""));
                },
            });
        }

        const cierre = $("cierre");
        cierre.replaceChildren();
        if (partido.estado === "CERRADO") {
            cierre.append(el("p", "dt-note", "El partido está cerrado. Durante 48 horas lo podés reabrir para corregir acciones."));
            const b = el("button", "dt-play ghost", "Reabrir partido");
            b.type = "button";
            b.addEventListener("click", reabrir);
            cierre.append(b);
        } else if (partido.estado !== "PENDIENTE") {
            const b = el("button", "go", "Cerrar partido");
            b.type = "button";
            b.addEventListener("click", cerrar);
            cierre.append(b);
        }
    }

    /** "Primera A Masculino · Local · Apertura · 18/10/2026", como en el PDF. */
    function detallePartido(p) {
        return [nombreEquipo(p), p.local ? "Local" : "Visitante", p.torneo, DtInforme.fecha(p.fecha)].filter(Boolean).join(" · ");
    }

    /** Abre el PDF en otra pestaña (la demo no los genera). */
    function exportarPdf(url) {
        if (window.DT_DEMO) { toast("La demo no genera PDF: con tu cuenta de Métricas DT exportás cada informe."); return; }
        window.open(url, "_blank", "noopener");
    }

    // ---------- Wake Lock: la pantalla no se apaga mientras corre el reloj ----------

    async function actualizarWakeLock() {
        const quiere = partido && partido.reloj.corriendo && !$("v-partido").hidden;
        try {
            if (quiere && !wakeLock && "wakeLock" in navigator && document.visibilityState === "visible") {
                wakeLock = await navigator.wakeLock.request("screen");
                wakeLock.addEventListener("release", () => { wakeLock = null; });
            } else if (!quiere && wakeLock) {
                await wakeLock.release();
                wakeLock = null;
            }
        } catch (e) {
            wakeLock = null;   // el teléfono no lo permite: se sigue sin él
        }
    }

    // ---------- Pestañas y navegación ----------

    function tab(id) {
        if (id === "t-partidos") { location.hash = "partidos"; return; }
        for (const [t, v] of [["t-cargar", "p-cargar"], ["t-resumen", "p-resumen"]]) {
            $(t).setAttribute("aria-selected", String(t === id));
            $(v).hidden = t !== id;
        }
    }

    /** Submenú Estadísticas (Equipo y Jugadores): se abre con su botón y se cierra al elegir o al tocar afuera. */
    function submenu(abrir) {
        $("dt-sub-est-menu").hidden = !abrir;
        document.querySelector("#dt-sub-est .dt-sub-btn").setAttribute("aria-expanded", String(abrir));
    }

    /**
     * #partido=ID abre la carga; #dashboard (sin nada), #partidos, #estadisticas-equipo, #estadisticas-jugadores,
     * #informes y #equipo (el plantel) muestran esa vista.
     */
    function ruta() {
        const m = location.hash.match(/^#partido=(\d+)$/);
        if (m) { $("dt-menu").hidden = true; for (const v of ["v-dashboard", "v-equipo", "v-jugadores", "v-informes", "v-plantel"]) $(v).hidden = true; abrir(Number(m[1])); return; }
        partido = null;
        actualizarWakeLock();
        $("v-partido").hidden = true;
        document.body.classList.remove("dt-vivo");
        const inf = location.hash.match(/^#informe=(\d+)$/);
        // #jugadores es el enlace de antes a las fichas.
        const vista = inf ? "informes" : location.hash === "#jugadores" ? "estadisticas-jugadores" : (location.hash.slice(1) || "dashboard");
        const VISTAS = {"dashboard": "v-dashboard", "partidos": "v-elegir", "estadisticas-equipo": "v-equipo",
            "estadisticas-jugadores": "v-jugadores", "informes": "v-informes", "equipo": "v-plantel"};
        const v = VISTAS[vista] ? vista : "dashboard";
        $("dt-menu").hidden = false;
        document.querySelectorAll("#dt-menu a").forEach(a =>
            a.dataset.vista === v ? a.setAttribute("aria-current", "page") : a.removeAttribute("aria-current"));
        $("dt-sub-est").classList.toggle("activo", v.startsWith("estadisticas-"));
        submenu(false);
        for (const [k, id] of Object.entries(VISTAS)) $(id).hidden = k !== v;
        if (v === "informes") cargarInformes(inf ? Number(inf[1]) : null);
        if (v === "dashboard") cargarDashboard();
        if (v === "partidos") cargarMis();
        if (v === "estadisticas-jugadores") cargarFichas();
        if (v === "estadisticas-equipo") cargarEquipo();
        if (v === "equipo") cargarPlantel();
    }

    // ---------- Dashboard general ----------

    async function cargarDashboard() {
        try {
            const lista = (await getJson("/api/dt/partidos")).data;
            const d = DtFichas.dashboard(lista, hoy());
            $("d-partidos").textContent = d.partidos;
            $("d-mes").textContent = d.esteMes;
            const cupo = sesion.suscripcion && sesion.suscripcion.partidosPorMes;
            $("d-mes-label").textContent = cupo ? `Este mes (plan: ${cupo} por mes)` : "Este mes";
            $("d-estados").innerHTML = DtFichas.barrasEstado(d.estados);
            $("d-meses").innerHTML = DtFichas.lineaMeses(d.meses);
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

    // ---------- Equipo: estadísticas consolidadas ----------

    /** Vista Equipo: balance, las métricas de todos los partidos cargados del equipo y los rankings. */
    async function cargarEquipo() {
        const sel = $("e-equipo");
        $("e-kpis").replaceChildren(el("p", "dt-vacio", "Cargando…"));
        try {
            await traerEquipos();
            llenarEquipos(sel);
            const e = equipoDe(sel);
            if (!e) { sinEquipos($("e-kpis")); return; }
            const q = `equipo=${e.id}`;
            const [lista, acc, pl] = await Promise.all([getJson("/api/dt/partidos").then(r => r.data),
                getJson("/api/dt/acciones?" + q).then(r => r.data), getJson("/api/dt/plantel?" + q).then(r => r.data).catch(() => [])]);
            const fin = lista.filter(p => p.equipoId === e.id && DtInforme.finalizado(p));
            const plantelEq = pl.map(j => ({key: "j" + j.id, numero: j.numero, nombre: j.nombre, arquero: j.arquero}));
            const inf = DtInforme.armar({titulo: "", detalle: "", unPartido: false, partidos: fin, acciones: acc,
                jugadores: plantelEq, nombreAccion: accion});
            const b = inf.balance;
            $("e-balance").replaceChildren(...[[b.jugados, "Jugados"], [b.ganados, "Ganados"], [b.empatados, "Empatados"],
                [b.perdidos, "Perdidos"], [b.golesFavor, "Goles a favor"], [b.golesContra, "Goles en contra"]].map(([v, n]) => {
                const k = el("div", "dt-kpi"); k.append(el("b", null, String(v)), el("span", null, n)); return k;
            }));
            $("e-kpis").replaceChildren(...DtInforme.tarjetas(inf.totales).map(([v, n, d, cls]) => {
                const k = el("div", "dt-kpi" + (cls ? " t-" + cls : ""));
                k.append(el("b", null, String(v)), el("span", null, n));
                if (d) k.append(el("small", null, d));
                return k;
            }));
            rankings(inf.jugadores);
        } catch (err) {
            $("e-kpis").replaceChildren(el("p", "dt-vacio", "No se pudieron cargar las estadísticas: " + err.message));
        }
    }

    /**
     * Goleadores del equipo (los 5 con más goles) y los 3 mejores en penales y en libres a favor (por efectividad; a
     * igual efectividad, el que más pateó).
     */
    function rankings(js) {
        const pintar = (id, filas, vacio) => {
            const ol = $(id);
            ol.replaceChildren();
            if (!filas.length) { ol.append(el("li", "dt-vacio", vacio)); return; }
            const max = Math.max(...filas.map(f => f.barra));
            filas.forEach((f, i) => {
                const li = el("li");
                const barra = el("span", "barra");
                barra.style.width = (max ? 100 * f.barra / max : 0) + "%";
                li.append(el("span", "pos", String(i + 1)), el("span", "quien", `${f.j.numero ? f.j.numero + " " : ""}${f.j.nombre}`),
                    el("b", null, f.valor), el("small", null, f.detalle || ""), barra);
                ol.append(li);
            });
        };
        pintar("e-goleadores", js.filter(j => j.metricas.goles > 0)
            .sort((a, b) => b.metricas.goles - a.metricas.goles || a.nombre.localeCompare(b.nombre, "es")).slice(0, 5)
            .map(j => ({j, valor: String(j.metricas.goles), barra: j.metricas.goles,
                detalle: `${j.metricas.goles === 1 ? "gol" : "goles"} · ${j.partidos} PJ`})),
            "Todavía no hay goles cargados.");
        const top = (gol, total, vacio, id) => pintar(id, js.filter(j => j.metricas[total] > 0)
            .map(j => ({j, pct: Math.round(100 * j.metricas[gol] / j.metricas[total]), a: j.metricas[gol], b: j.metricas[total]}))
            .sort((x, y) => y.pct - x.pct || y.b - x.b || x.j.nombre.localeCompare(y.j.nombre, "es")).slice(0, 3)
            .map(x => ({j: x.j, valor: x.pct + "%", barra: x.pct, detalle: `${x.a}/${x.b} convertidos`})), vacio);
        top("penalesGol", "penales", "Todavía no hay penales a favor cargados.", "e-penales");
        top("libresGol", "libres", "Todavía no hay libres a favor cargados.", "e-libres");
    }

    // ---------- Informes ----------

    let infTipo = "PARTIDOS", infValor = null, infJugador = null;

    /** Sección Informes: los partidos finalizados o el informe de un período; con id, el informe de ese partido. */
    async function cargarInformes(id) {
        const sel = $("i-equipo");
        const box = $("i-informe"), lista = $("i-lista");
        box.replaceChildren(el("p", "dt-vacio", "Cargando…"));
        try {
            await traerEquipos();
            llenarEquipos(sel);
            if (!equipos.length) { lista.replaceChildren(); sinEquipos(box); return; }
            if (id) {
                const p = (await getJson(`/api/dt/partidos/${id}`)).data;
                if (String(p.equipoId) !== sel.value) sel.value = String(p.equipoId);
            }
            const [todos, acc, plantelEq] = await datosDelEquipo();
            const equipo = equipoDe(sel);
            if (id) {
                const p = todos.find(x => x.id === id) || (await getJson(`/api/dt/partidos/${id}`)).data;
                return informeDePartido(p, acc.filter(a => a.partidoId === p.id), plantelEq);
            }
            $("i-volver").hidden = true;
            document.querySelectorAll("#i-tipo button").forEach(b => b.setAttribute("aria-pressed", String(b.dataset.tipo === infTipo)));
            const fin = todos.filter(DtInforme.finalizado);
            if (infTipo === "PARTIDOS") {
                $("i-valor").hidden = true;
                box.replaceChildren();
                lista.hidden = false;
                lista.replaceChildren();
                if (!fin.length) lista.append(el("li", "dt-vacio", "Todavía no hay partidos finalizados en este equipo. El informe de cada partido queda acá cuando termina."));
                for (const p of fin) {
                    const li = el("li"), b = el("button");
                    b.type = "button";
                    b.append(el("span", null, `${DtInforme.fecha(p.fecha)} · ${p.rival}`), el("b", null, `${p.golesFavor} - ${p.golesContra}`),
                        el("small", null, [p.local ? "Local" : "Visitante", p.torneo || "Sin campeonato", ESTADO[p.estado]].join(" · ")));
                    b.addEventListener("click", () => { location.hash = "informe=" + p.id; });
                    li.append(b);
                    lista.append(li);
                }
                return;
            }
            lista.hidden = true;
            const ops = DtInforme.opciones(infTipo, fin);
            const v = $("i-valor");
            v.hidden = false;
            v.replaceChildren(...ops.map(o => { const op = el("option", null, `${o.nombre} (${o.n} ${o.n === 1 ? "partido" : "partidos"})`); op.value = o.valor; return op; }));
            if (!ops.length) { v.hidden = true; box.replaceChildren(el("p", "dt-vacio", "Todavía no hay partidos finalizados en este equipo.")); return; }
            if (!ops.some(o => o.valor === infValor)) infValor = ops[0].valor;
            v.value = infValor;
            const ps = fin.filter(p => DtInforme.valor(infTipo, p) === infValor).reverse();
            const ids = new Set(ps.map(p => p.id));
            const nombre = DtInforme.nombre(infTipo, infValor);
            const armar = jugador => DtInforme.armar({titulo: infTipo === "CAMPEONATO" && infValor ? "Campeonato " + nombre : nombre,
                detalle: `${equipo.nombre} · ${ps.length} ${ps.length === 1 ? "partido" : "partidos"}`, unPartido: false, partidos: ps,
                acciones: acc.filter(a => ids.has(a.partidoId)), jugadores: plantelEq, jugador, nombreAccion: accion});
            const infm = armar(infJugador);
            if (infJugador && !infm.jugador) infJugador = null;
            DtInforme.render(box, infJugador && infm.jugador ? infm : armar(null), {
                jugadores: armar(null).jugadores,
                onJugador: k => { infJugador = k; cargarInformes(null); },
                onPartido: pid => { location.hash = "informe=" + pid; },
                onPdf: () => exportarPdf(`/api/dt/informes.pdf?equipo=${equipo.id}`
                    + `&periodo=${infTipo}&valor=${encodeURIComponent(infValor)}` + (infJugador ? "&jugador=" + encodeURIComponent(infJugador) : "")),
            });
        } catch (err) {
            box.replaceChildren(el("p", "dt-vacio", "No se pudieron cargar los informes: " + err.message));
        }
    }

    /** Partidos del DT del equipo elegido en Informes, sus acciones y el plantel (con las claves de los jugadores). */
    async function datosDelEquipo() {
        const e = equipoDe($("i-equipo"));
        const q = `equipo=${e.id}`;
        const [lista, acc, pl] = await Promise.all([getJson("/api/dt/partidos").then(r => r.data),
            getJson("/api/dt/acciones?" + q).then(r => r.data), getJson("/api/dt/plantel?" + q).then(r => r.data).catch(() => [])]);
        return [lista.filter(p => p.equipoId === e.id), acc,
            pl.map(j => ({key: "j" + j.id, numero: j.numero, nombre: j.nombre, arquero: j.arquero}))];
    }

    function informeDePartido(p, acc, plantelEq, jugador = null) {
        $("i-lista").hidden = true;
        $("i-valor").hidden = true;
        $("i-volver").hidden = false;
        document.querySelectorAll("#i-tipo button").forEach(b => b.setAttribute("aria-pressed", "false"));
        const armar = j => DtInforme.armar({titulo: `${sesion.club.nombre} ${p.golesFavor} - ${p.golesContra} ${p.rival}`,
            detalle: detallePartido(p), unPartido: true, partidos: [p], acciones: acc, jugadores: plantelEq, jugador: j,
            nombreAccion: accion});
        const todos = armar(null);
        DtInforme.render($("i-informe"), jugador ? armar(jugador) : todos, {
            jugadores: todos.jugadores,
            onJugador: k => informeDePartido(p, acc, plantelEq, k),
            onPdf: () => exportarPdf(`/api/dt/partidos/${p.id}/informe.pdf` + (jugador ? "?jugador=" + encodeURIComponent(jugador) : "")),
        });
    }

    // ---------- Equipo: ABM de equipos y de jugadores ----------

    let eqEditando = null;   // id del equipo que se está renombrando (null: alta)
    let plEditando = null;   // id del jugador que se está editando (null: alta)
    let jugadoresDt = [];

    /** Aviso para las vistas que necesitan un equipo. */
    function sinEquipos(box) {
        const p = el("p", "dt-aviso-equipo");
        p.append("Todavía no tenés equipos. Crealos en ");
        const a = el("a", null, "Equipo");
        a.href = "#equipo";
        p.append(a, ", con sus jugadores.");
        box.replaceChildren(p);
    }

    async function cargarPlantel() {
        try {
            const [, js] = await Promise.all([traerEquipos(), getJson("/api/dt/jugadores").then(r => r.data)]);
            jugadoresDt = js;
            pintarEquipos();
            pintarJugadores();
        } catch (err) {
            $("eq-lista").replaceChildren(el("li", "dt-vacio", "No se pudieron cargar los equipos: " + err.message));
        }
    }

    function pintarEquipos() {
        const ul = $("eq-lista");
        // Cuántos equipos usa de los que permite su plan (free 1, mensual 3).
        const max = sesion.suscripcion && sesion.suscripcion.equiposMax;
        $("eq-n").textContent = max ? `${equipos.length} de ${max} ${max === 1 ? "equipo" : "equipos"}`
            : equipos.length ? `${equipos.length} ${equipos.length === 1 ? "equipo" : "equipos"}` : "";
        const lleno = max && equipos.length >= max && eqEditando == null;
        $("eq-nombre").disabled = $("eq-guardar").disabled = !!lleno;
        if (lleno && !$("eq-status").textContent) abmStatus("eq-status", `Tu plan permite ${max === 1 ? "1 equipo" : max + " equipos"}: `
            + "para sumar otro, borrá uno sin partidos" + (max === 1 ? " o pasate al plan mensual." : "."));
        ul.replaceChildren();
        if (!equipos.length) ul.append(el("li", "dt-vacio", "Todavía no creaste equipos: agregá el primero."));
        for (const e of equipos) {
            const li = el("li", "eq-fila");
            li.classList.toggle("editando", e.id === eqEditando);
            const quien = el("div", "quien");
            quien.append(el("b", null, e.nombre));
            const bal = el("div", "eq-balance");
            for (const [v, n, t] of [[e.jugados, "PJ", "Jugados"], [e.ganados, "G", "Ganados"], [e.empatados, "E", "Empatados"],
                [e.perdidos, "P", "Perdidos"]]) {
                const c = el("span", "eq-dato");
                c.title = t;
                c.append(el("b", null, String(v)), el("small", null, n));
                bal.append(c);
            }
            const acc = el("div", "acc");
            const editar = el("button", "dt-link", "Editar");
            editar.type = "button";
            editar.setAttribute("aria-label", "Cambiar el nombre de " + e.nombre);
            editar.addEventListener("click", () => eqFormulario(e));
            const borrar = el("button", "dt-link pl-baja", "Borrar");
            borrar.type = "button";
            borrar.setAttribute("aria-label", "Borrar el equipo " + e.nombre);
            borrar.addEventListener("click", () => eqBorrar(e));
            acc.append(editar, borrar);
            li.append(quien, bal, acc);
            ul.append(li);
        }
        // Equipos en el formulario de jugadores y en el filtro de la lista.
        $("pl-sin-equipos").hidden = equipos.length > 0;
        for (const id of ["pl-nombre", "pl-apellido", "pl-numero", "pl-arquero", "pl-guardar"]) $(id).disabled = !equipos.length;
        const marcados = new Set([...document.querySelectorAll("#pl-equipos input:checked")].map(i => i.value));
        $("pl-equipos").replaceChildren(...equipos.map(e => {
            const l = el("label", "pl-eq");
            const c = el("input");
            c.type = "checkbox";
            c.value = String(e.id);
            c.checked = marcados.has(c.value) || equipos.length === 1;
            l.append(c, " " + e.nombre);
            return l;
        }));
        const f = $("pl-filtro"), antes = f.value;
        const todos = el("option", null, "Todos los equipos");
        todos.value = "";
        f.replaceChildren(todos, ...equipos.map(e => { const o = el("option", null, e.nombre); o.value = String(e.id); return o; }));
        f.value = equipos.some(e => String(e.id) === antes) ? antes : "";
        f.hidden = equipos.length < 2;
    }

    function pintarJugadores() {
        const ul = $("pl-lista");
        const filtro = $("pl-filtro").value;
        const lista = jugadoresDt.filter(j => !filtro || j.equipos.map(String).includes(filtro));
        $("pl-n").textContent = jugadoresDt.length ? `${jugadoresDt.length} ${jugadoresDt.length === 1 ? "jugador" : "jugadores"}` : "";
        ul.replaceChildren();
        if (!lista.length) ul.append(el("li", "dt-vacio", jugadoresDt.length ? "No hay jugadores en ese equipo."
            : "Todavía no hay jugadores: agregá el primero."));
        const nombres = new Map(equipos.map(e => [e.id, e.nombre]));
        for (const j of lista) {
            const li = el("li");
            li.classList.toggle("editando", j.id === plEditando);
            const quien = el("div", "quien");
            quien.append(el("b", null, j.nombre));
            if (j.arquero) quien.append(el("span", "inf-ar", "Arquero"));
            quien.append(el("small", "pl-de", j.equipos.map(id => nombres.get(id)).filter(Boolean).join(" · ")));
            const editar = el("button", "dt-link", "Editar");
            editar.type = "button";
            editar.setAttribute("aria-label", "Editar a " + j.nombre);
            editar.addEventListener("click", () => plFormulario(j));
            const baja = el("button", "dt-link pl-baja", "Dar de baja");
            baja.type = "button";
            baja.setAttribute("aria-label", "Dar de baja a " + j.nombre);
            baja.addEventListener("click", () => plBaja(j));
            const acc = el("div", "acc");
            acc.append(editar, baja);
            li.append(el("span", "pl-num", j.numero ? "#" + j.numero : "s/n"), quien, acc);
            ul.append(li);
        }
    }

    function abmStatus(id, txt, error) {
        $(id).textContent = txt;
        $(id).className = "abm-status" + (txt ? (error ? " error" : " ok") : "");
    }

    /** Envía un alta (POST), un cambio (PUT) o un borrado (DELETE) del ABM; devuelve la respuesta o lanza el error. */
    async function abm(method, url, body) {
        const res = await api(url, Object.assign({method}, body ? {headers: {"Content-Type": "application/json"},
            body: JSON.stringify(body)} : {}));
        const data = res.status === 204 ? {} : await res.json().catch(() => ({}));
        if (!res.ok) throw new Error(data.detail || res.statusText);
        return data;
    }

    const sinSenalAbm = err => err instanceof TypeError ? "Sin señal: para editar los equipos hace falta conexión." : err.message;

    function eqFormulario(e) {
        eqEditando = e ? e.id : null;
        $("eq-titulo").textContent = e ? "Cambiar nombre" : "Nuevo equipo";
        $("eq-guardar").textContent = e ? "Guardar cambios" : "Agregar equipo";
        $("eq-cancelar").hidden = !e;
        $("eq-nombre").value = e ? e.nombre : "";
        abmStatus("eq-status", "");
        pintarEquipos();
        if (e) $("eq-nombre").focus();
    }

    async function eqGuardar(ev) {
        ev.preventDefault();
        const nombre = $("eq-nombre").value.trim();
        if (!nombre) { abmStatus("eq-status", "Escribí el nombre del equipo.", true); $("eq-nombre").focus(); return; }
        $("eq-guardar").disabled = true;
        try {
            const editando = eqEditando;
            const data = await abm(editando ? "PUT" : "POST", editando ? `/api/dt/equipos/${editando}` : "/api/dt/equipos", {nombre});
            eqFormulario(null);
            abmStatus("eq-status", editando ? `Guardado: ${data.nombre}.` : `Equipo creado: ${data.nombre}. Ahora cargá sus jugadores.`);
            await cargarPlantel();
        } catch (err) {
            abmStatus("eq-status", sinSenalAbm(err), true);
        } finally {
            $("eq-guardar").disabled = $("eq-nombre").disabled;   // con el cupo de equipos lleno, sigue deshabilitado
        }
    }

    async function eqBorrar(e) {
        if (!confirm(`¿Borrar el equipo ${e.nombre}? Sus jugadores siguen en tus otros equipos.`)) return;
        try {
            await abm("DELETE", `/api/dt/equipos/${e.id}`);
            if (eqEditando === e.id) eqFormulario(null);
            abmStatus("eq-status", `${e.nombre} se borró.`);
            await cargarPlantel();
        } catch (err) {
            abmStatus("eq-status", sinSenalAbm(err), true);
        }
    }

    /** El formulario de jugadores en modo alta (j null) o para editar a j. */
    function plFormulario(j) {
        plEditando = j ? j.id : null;
        $("pl-titulo").textContent = j ? "Editar jugador" : "Nuevo jugador";
        $("pl-guardar").textContent = j ? "Guardar cambios" : "Agregar jugador";
        $("pl-cancelar").hidden = !j;
        $("pl-nombre").value = j ? j.nombres : "";
        $("pl-apellido").value = j ? j.apellido : "";
        $("pl-numero").value = j ? j.numero : "";
        $("pl-arquero").checked = j ? j.arquero : false;
        document.querySelectorAll("#pl-equipos input").forEach(c => {
            c.checked = j ? j.equipos.map(String).includes(c.value) : equipos.length === 1;
        });
        abmStatus("pl-status", "");
        pintarJugadores();
        if (j) $("pl-nombre").focus();
    }

    async function plGuardar(ev) {
        ev.preventDefault();
        const body = {nombre: $("pl-nombre").value, apellido: $("pl-apellido").value, numero: $("pl-numero").value,
            arquero: $("pl-arquero").checked,
            equipos: [...document.querySelectorAll("#pl-equipos input:checked")].map(c => Number(c.value))};
        if (!body.nombre.trim()) { abmStatus("pl-status", "Escribí el nombre del jugador.", true); $("pl-nombre").focus(); return; }
        if (!body.apellido.trim()) { abmStatus("pl-status", "Escribí el apellido del jugador.", true); $("pl-apellido").focus(); return; }
        if (!body.equipos.length) { abmStatus("pl-status", "Asigná el jugador a un equipo.", true); return; }
        $("pl-guardar").disabled = true;
        try {
            const editando = plEditando;
            const data = await abm(editando ? "PUT" : "POST", editando ? `/api/dt/jugadores/${editando}` : "/api/dt/jugadores", body);
            plFormulario(null);
            abmStatus("pl-status", editando ? `Guardado: ${data.nombre}.` : `Agregado: ${data.nombre}.`);
            $("pl-nombre").focus();
            await cargarPlantel();
        } catch (err) {
            abmStatus("pl-status", sinSenalAbm(err), true);
        } finally {
            $("pl-guardar").disabled = !equipos.length;
        }
    }

    async function plBaja(j) {
        if (!confirm(`¿Dar de baja a ${j.nombre}? Sale de sus equipos; sus acciones quedan en los partidos y los informes.`)) return;
        try {
            await abm("DELETE", `/api/dt/jugadores/${j.id}`);
            if (plEditando === j.id) plFormulario(null);
            abmStatus("pl-status", `${j.nombre} salió del plantel.`);
            await cargarPlantel();
        } catch (err) {
            abmStatus("pl-status", sinSenalAbm(err), true);
        }
    }

    // ---------- Jugadores: fichas ----------

    async function cargarFichas() {
        const sel = $("j-equipo");
        const box = $("fichas");
        box.replaceChildren(el("p", "dt-vacio", "Cargando…"));
        try {
            await traerEquipos();
            llenarEquipos(sel);
            const e = equipoDe(sel);
            if (!e) { sinEquipos(box); return; }
            const q = `equipo=${e.id}`;
            const [plantelEq, acciones] = await Promise.all([getJson("/api/dt/plantel?" + q).then(r => r.data).catch(() => []),
                getJson("/api/dt/acciones?" + q).then(r => r.data)]);
            const fichas = DtFichas.calcular(plantelEq, acciones);
            box.replaceChildren();
            if (!fichas.length) box.append(el("p", "dt-vacio", "Todavía no hay jugadores ni acciones cargadas en este equipo."));
            for (const f of fichas) box.append(ficha(f, e.nombre));
        } catch (err) {
            box.replaceChildren(el("p", "dt-vacio", "No se pudieron cargar las fichas: " + err.message));
        }
    }

    /** Ficha del jugador: encabezado con el índice, pentágono de los cinco ejes, datos clave y el detalle por eje. */
    function ficha(f, equipo) {
        const card = el("article", "ficha" + (f.arquero ? " arquero" : "") + (f.indice == null ? " sin-datos" : ""));
        const head = el("header", "ficha-head");
        const quien = el("div");
        quien.append(el("b", null, f.nombre),
            el("small", null, `${f.numero ? "#" + f.numero + " · " : ""}${f.arquero ? "Arquero" : equipo}`),
            el("small", "ficha-pj", `${f.partidos} ${f.partidos === 1 ? "partido" : "partidos"}`));
        const idx = el("div", "ficha-indice");
        idx.append(el("b", null, f.indice == null ? "–" : String(f.indice)), el("small", null, "Índice"));
        head.append(quien, idx);
        const graf = el("div", "ficha-pentagono");
        if (f.indice == null) graf.append(el("p", "dt-vacio", "Sin acciones cargadas todavía."));
        else graf.innerHTML = DtFichas.pentagono(f.ejes);
        const e = Object.fromEntries(f.ejes.map(x => [x.clave, x]));
        const datos = f.arquero
            ? [[`${e.efectividad.a}`, "Atajados"], [`${e.efectividad.b}`, "Recibidos"],
               [`${e.pelotaParada.a}/${e.pelotaParada.a + e.pelotaParada.b}`, "Penal/libre"]]
            : [[`${e.ataque.a}/${e.ataque.b}`, "Goles/tiros"], [`${e.recuperacion.a} | ${e.recuperacion.b}`, "Rec. | Pérd."],
               [`${e.disciplina.b}`, "Tarjetas"]];
        const chips = el("div", "ficha-datos");
        for (const [v, n] of datos) { const c = el("div"); c.append(el("b", null, v), el("span", null, n)); chips.append(c); }
        const detalle = el("details", "ficha-detalle");
        detalle.append(el("summary", null, "Detalle por eje"));
        for (const x of f.ejes) {
            const fila = el("div", "ficha-fila");
            fila.append(el("span", "t", x.nombre), el("span", "v", x.texto),
                el("span", "d", x.etiquetas.join(" · ")), el("span", "n", `${x.a} | ${x.b}`));
            detalle.append(fila);
        }
        card.append(head, graf, chips, detalle);
        return card;
    }

    // ---------- Inicio ----------

    $("brand").outerHTML = BRAND;
    document.querySelector("#dt-sub-est .dt-sub-btn").addEventListener("click", ev => {
        ev.stopPropagation();
        submenu($("dt-sub-est-menu").hidden);
    });
    document.addEventListener("click", ev => { if (!ev.target.closest("#dt-sub-est")) submenu(false); });
    document.addEventListener("keydown", ev => { if (ev.key === "Escape") submenu(false); });
    $("pl-form").addEventListener("submit", plGuardar);
    $("eq-form").addEventListener("submit", eqGuardar);
    $("eq-cancelar").addEventListener("click", () => eqFormulario(null));
    $("pl-filtro").addEventListener("change", pintarJugadores);
    for (const id of ["e-equipo", "j-equipo"]) $(id).addEventListener("change", () => ruta());
    $("i-equipo").addEventListener("change", () => { infValor = null; infJugador = null; if (location.hash !== "#informes") location.hash = "informes"; else cargarInformes(null); });
    $("pl-cancelar").addEventListener("click", () => plFormulario(null));
    // Un solo menú arriba: el del DT va en la barra superior, al lado de la marca.
    document.querySelector(".topbar header.brand").after($("dt-menu"));
    document.querySelectorAll("#i-tipo button").forEach(b => b.addEventListener("click", () => {
        infTipo = b.dataset.tipo;
        infValor = null;
        infJugador = null;
        if (location.hash !== "#informes") location.hash = "informes"; else cargarInformes(null);
    }));
    $("i-valor").addEventListener("change", () => { infValor = $("i-valor").value; infJugador = null; cargarInformes(null); });
    $("i-volver-btn").addEventListener("click", () => { infTipo = "PARTIDOS"; location.hash = "informes"; });
    for (const t of ["t-cargar", "t-resumen", "t-partidos"]) $(t).addEventListener("click", () => tab(t));
    $("rival").addEventListener("input", () => { $("crear").disabled = !rivalElegido() || !equipos.length; });
    $("fecha").value = hoy();
    $("crear").addEventListener("click", crear);
    document.querySelectorAll("#m-ajuste button").forEach(b =>
        b.addEventListener("click", () => reloj("AJUSTAR", Number(b.dataset.aj))));
    $("cambiar-ar").addEventListener("click", () => {
        // Pasa al siguiente arquero del plantel; sin arqueros marcados, el jugador seleccionado.
        const lista = jugadores(), ars = lista.filter(j => j.arquero);
        if (ars.length) {
            const i = ars.findIndex(j => j.key === arquero);
            arquero = ars[(i + 1) % ars.length].key;
        } else if (seleccionado) {
            arquero = seleccionado;
        } else {
            toast("Tocá el jugador que ataja y después «Arquero en cancha».");
            return;
        }
        guardar("ar", arquero);
        renderJugadores();
    });
    $("agregar-j").addEventListener("click", () => { $("nuevo-j").hidden = !$("nuevo-j").hidden; if (!$("nuevo-j").hidden) $("nj-num").focus(); });
    $("nuevo-j").addEventListener("submit", e => {
        e.preventDefault();
        const nombre = $("nj-nombre").value.trim().replace(/\s+/g, " ");
        if (!nombre) { $("nj-nombre").focus(); return; }
        const extras = leer("extras", []);
        if (!extras.some(x => x.nombre === nombre)) extras.push({numero: $("nj-num").value.trim(), nombre});
        guardar("extras", extras);
        seleccionado = "m" + nombre;
        $("nj-num").value = $("nj-nombre").value = "";
        $("nuevo-j").hidden = true;
        renderJugadores();
    });
    window.addEventListener("hashchange", ruta);
    window.addEventListener("online", () => { renderSync(); refrescar(); });
    window.addEventListener("offline", renderSync);
    document.addEventListener("visibilitychange", () => {
        if (document.visibilityState === "visible") { refrescar(); actualizarWakeLock(); }
    });

    /** Con señal, vuelve a pedir el partido para que el reloj quede igual al del servidor. */
    async function refrescar() {
        if (!partido || !navigator.onLine || document.visibilityState !== "visible") return;
        try { const r = await getJson(`/api/dt/partidos/${partido.id}`); recibir(r.data, r.at); } catch (e) { /* sin señal */ }
    }
    setInterval(renderReloj, 250);
    setInterval(() => { if (partido && !pend.length) refrescar(); }, 15000);

    if ("serviceWorker" in navigator) {
        navigator.serviceWorker.register("/dt-sw.js", {scope: "/dt.html"}).catch(() => { /* sin carga sin señal */ });
    }

    requireProfile(["DTFPP", "DTAMP"]).then(async s => {
        if (!s) return;
        sesion = s;
        if (s.suscripcion && s.suscripcion.partidosPorMes) $("cupo").textContent = `${s.suscripcion.partidosPorMes} partidos por mes`;
        await DtCola.iniciar(async ({partido: p, pendientes, error}) => {
            pendientesTotal = pendientes;
            if (p && partido && p.id === partido.id) recibir(p, Date.now());
            if (error) toast(error);
            if (partido) await refrescarPend(); else if (!$("v-elegir").hidden && p) cargarMis();
        });
        // Un DT sin equipos empieza por la sección Equipo: sin equipo ni jugadores no hay partido que cargar.
        try {
            if (!location.hash && !(await traerEquipos()).length) history.replaceState(null, "", location.pathname + location.search + "#equipo");
        } catch (e) { /* sin señal: el panel avisa */ }
        ruta();
    });
})();
