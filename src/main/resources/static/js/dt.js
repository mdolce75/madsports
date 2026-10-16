// Planilla DT en vivo: elegir el partido, manejar el reloj y cargar las acciones de cada jugador. Las acciones pasan
// por DtCola (dt-cola.js), así se guardan aunque no haya señal; el reloj sí necesita conexión.
(() => {
    // Acciones de la planilla. arco: se cargan al arquero en cancha. Las de a pares (gol / no gol) van en una tarjeta.
    const SIMPLES = [
        {g: "g-posesion", tipo: "PERDIDA", res: "PROPIO", t: "Pérdida", d: "En campo propio", cls: "bad"},
        {g: "g-posesion", tipo: "PERDIDA", res: "RIVAL", t: "Pérdida", d: "En campo rival", cls: "bad"},
        {g: "g-posesion", tipo: "RECUPERACION", res: "PROPIO", t: "Recuperación", d: "En campo propio", cls: "good"},
        {g: "g-posesion", tipo: "RECUPERACION", res: "RIVAL", t: "Recuperación", d: "En campo rival", cls: "good"},
        {g: "g-arco", tipo: "ATAJADA", res: null, t: "Atajada", d: "Arquero", cls: "good", arco: true},
        {g: "g-arco", tipo: "GOL_RECIBIDO", res: null, t: "Gol recibido", d: "Arquero", cls: "bad", arco: true},
        {g: "g-faltas", tipo: "FALTA", res: "COMETIDA", t: "Falta cometida", d: "", cls: "bad"},
        {g: "g-faltas", tipo: "FALTA", res: "RECIBIDA", t: "Falta recibida", d: "", cls: "good"},
        {g: "g-tarjetas", tipo: "TARJETA", res: "AMARILLA", t: "Amarilla", d: "", cls: "card-y"},
        {g: "g-tarjetas", tipo: "TARJETA", res: "AZUL", t: "Azul", d: "", cls: "card-b"},
        {g: "g-tarjetas", tipo: "TARJETA", res: "ROJA", t: "Roja", d: "", cls: "card-r"},
    ];
    const PARES = [
        {g: "g-arco", tipo: "TIRO", t: "Tiro al arco", quien: "", si: ["GOL", "Gol"], no: ["NO_GOL", "No gol"]},
        {g: "g-parada", tipo: "LIBRE_FAVOR", t: "Libre a favor", quien: "pateador", si: ["GOL", "Gol"], no: ["NO_GOL", "No gol"]},
        {g: "g-parada", tipo: "LIBRE_CONTRA", t: "Libre en contra", quien: "arquero", arco: true, si: ["GOL", "Gol"], no: ["ATAJADO", "Atajado"]},
        {g: "g-parada", tipo: "PENAL_FAVOR", t: "Penal a favor", quien: "pateador", si: ["GOL", "Gol"], no: ["NO_GOL", "No gol"]},
        {g: "g-parada", tipo: "PENAL_CONTRA", t: "Penal en contra", quien: "arquero", arco: true, si: ["GOL", "Gol"], no: ["ATAJADO", "Atajado"]},
    ];
    const ARCO = new Set(["ATAJADA", "GOL_RECIBIDO", "LIBRE_CONTRA", "PENAL_CONTRA"]);
    const NOMBRE = {
        PERDIDA: "Pérdida", RECUPERACION: "Recuperación", TIRO: "Tiro al arco", ATAJADA: "Atajada",
        GOL_RECIBIDO: "Gol recibido", LIBRE_FAVOR: "Libre a favor", LIBRE_CONTRA: "Libre en contra",
        PENAL_FAVOR: "Penal a favor", PENAL_CONTRA: "Penal en contra", FALTA: "Falta", TARJETA: "Tarjeta",
    };
    const RESULTADO = {
        GOL: "Gol", NO_GOL: "No gol", ATAJADO: "Atajado", PROPIO: "Campo propio", RIVAL: "Campo rival",
        COMETIDA: "Cometida", RECIBIDA: "Recibida", AMARILLA: "Amarilla", AZUL: "Azul", ROJA: "Roja",
    };
    const ESTADO = {PENDIENTE: "Sin iniciar", EN_JUEGO: "En juego", ENTRETIEMPO: "Entretiempo", TERMINADO: "Terminado", CERRADO: "Cerrado"};
    const COLOR = {good: "var(--good)", bad: "var(--err)", "card-y": "var(--warn)", "card-b": "#2563eb", "card-r": "var(--err)"};

    const golAFavor = a => a.resultado === "GOL" && ["TIRO", "LIBRE_FAVOR", "PENAL_FAVOR"].includes(a.tipo);
    const golEnContra = a => a.tipo === "GOL_RECIBIDO" || a.resultado === "GOL" && ["LIBRE_CONTRA", "PENAL_CONTRA"].includes(a.tipo);

    let sesion = null;
    // Partido abierto: lo que devolvió el servidor, cuándo (para el reloj), plantel, pendientes de la cola.
    let partido = null, recibido = 0, plantel = [], pend = [], pendientesTotal = 0;
    let seleccionado = null, arquero = null;
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

    // ---------- Elegir el partido ----------

    function equipoActual() {
        return sesion.equipos[Number($("equipo").value) || 0];
    }

    /** Combo de rivales: los equipos de los otros clubes en la categoría y rama del equipo, y "Otro". */
    async function cargarRivales() {
        const e = equipoActual();
        const sel = $("rival");
        sel.disabled = true;
        sel.replaceChildren(el("option", null, "Cargando los clubes…"));
        $("rival-hint").textContent = "";
        let rivales = [];
        try {
            rivales = (await getJson(`/api/dt/rivales?categoria=${encodeURIComponent(e.categoria)}`
                + `&rama=${encodeURIComponent(e.rama)}`)).data;
        } catch (err) {
            $("rival-hint").textContent = "No se pudo leer la lista de clubes (" + err.message + "). Escribí el rival.";
        }
        const vacio = el("option", null, "Elegí el rival…");
        vacio.value = "";
        sel.replaceChildren(vacio);
        for (const r of rivales) {
            const o = el("option", null, r.nombre);
            o.value = r.nombre;
            sel.append(o);
        }
        const otro = el("option", null, "Otro (lo escribo)");
        otro.value = "*";
        sel.append(otro);
        sel.disabled = false;
        if (!rivales.length) sel.value = "*";
        elegirRival();
    }

    function elegirRival() {
        $("f-otro").hidden = $("rival").value !== "*";
        if (!$("f-otro").hidden) $("otro").focus();
        $("crear").disabled = !rivalElegido();
    }

    const rivalElegido = () => $("rival").value === "*" ? $("otro").value.trim() : $("rival").value;

    async function crear() {
        const e = equipoActual();
        const body = {categoria: e.categoria, rama: e.rama, rival: rivalElegido(), fecha: $("fecha").value || null,
            local: $("local").value === "true"};
        if (!body.rival) { status("Elegí el rival.", true); return; }
        $("crear").disabled = true;
        status("Creando el partido…");
        try {
            const p = await postJson("/api/dt/partidos", body);
            status("");
            location.hash = "partido=" + p.id;
        } catch (err) {
            status(err instanceof TypeError ? "Sin señal: para empezar un partido hace falta conexión." : err.message, true);
        } finally {
            $("crear").disabled = !rivalElegido();
        }
    }

    async function cargarMis() {
        const ul = $("mis");
        try {
            const lista = (await getJson("/api/dt/partidos")).data;
            ul.replaceChildren();
            $("mis-n").textContent = lista.length ? String(lista.length) : "";
            if (!lista.length) ul.append(el("li", "dt-vacio", "Todavía no cargaste partidos."));
            for (const p of lista) {
                const li = el("li");
                const b = el("button");
                b.type = "button";
                const t = el("span", null, `${diaMes(p.fecha)} · ${p.rival}`);
                const r = el("b", null, `${p.golesFavor} - ${p.golesContra}`);
                const sub = el("small", null, [nombreEquipo(p), p.local ? "Local" : "Visitante", p.torneo, ESTADO[p.estado]]
                    .filter(Boolean).join(" · "));
                b.append(t, r, sub);
                b.addEventListener("click", () => { location.hash = "partido=" + p.id; });
                li.append(b);
                ul.append(li);
            }
        } catch (err) {
            ul.replaceChildren(el("li", "dt-vacio", "No se pudo cargar la lista: " + err.message));
        }
    }

    function nombreEquipo(p) {
        const e = sesion.equipos.find(x => x.categoria === p.categoria && x.rama === p.rama);
        return e ? e.nombre : p.categoria + " " + p.rama;
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
            delete $("jugadores").dataset.firma;
            delete $("log").dataset.firma;
            avisado = "";
            try {
                plantel = (await getJson(`/api/dt/plantel?categoria=${encodeURIComponent(r.data.categoria)}`
                    + `&rama=${encodeURIComponent(r.data.rama)}`)).data;
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
            location.hash = "";
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

    async function reloj(accion, segundos) {
        try {
            recibir(await postJson(`/api/dt/partidos/${partido.id}/reloj`, {accion, segundos}), Date.now());
        } catch (err) {
            toast(err instanceof TypeError ? "Sin señal: el reloj necesita conexión. Las acciones se siguen guardando."
                : err.message);
        }
    }

    async function cerrar() {
        if (!confirm("¿Cerrar el partido? Ya no se podrán cargar acciones (se puede reabrir durante 48 horas).")) return;
        if (pend.length) { toast("Esperá a que se envíe lo cargado antes de cerrar."); DtCola.enviar(); return; }
        try { recibir(await postJson(`/api/dt/partidos/${partido.id}/cerrar`, {}), Date.now()); }
        catch (err) { toast(err instanceof TypeError ? "Sin señal: para cerrar el partido hace falta conexión." : err.message); }
    }

    async function reabrir() {
        try { recibir(await postJson(`/api/dt/partidos/${partido.id}/reabrir`, {}), Date.now()); }
        catch (err) { toast(err instanceof TypeError ? "Sin señal: para reabrir hace falta conexión." : err.message); }
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
            if (!lista.length) box.append(el("p", "dt-note", "No hay plantel en las actas de SIDGAD: agregá los jugadores a mano."));
            for (const j of lista) {
                const b = el("button", "dt-player" + (j.arquero ? " gk" : ""));
                b.type = "button";
                b.dataset.key = j.key;
                b.append(el("span", "n", j.numero || "·"), el("span", "s", corto(j.nombre)));
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
        $("sel").textContent = sel ? `${sel.numero ? sel.numero + " · " : ""}${sel.nombre}` : "—";
        $("ar").textContent = ar ? `${ar.numero ? ar.numero + " · " : ""}${corto(ar.nombre)}` : "elegir";
    }

    /** Contadores de los botones de acción: [span, función que da el texto con las acciones del partido]. */
    let contadores = null;

    function renderAcciones(acc) {
        if (!contadores) {
            contadores = [];
            for (const p of PARES.filter(x => x.g === "g-arco")) $(p.g).append(par(p));
            for (const a of SIMPLES) {
                const b = el("button", "dt-act " + (a.cls || ""));
                b.type = "button";
                const c = el("span", "c");
                b.append(el("span", "t", a.t), el("span", "d", a.d || " "), c);
                b.setAttribute("aria-label", `${a.t} ${a.d}`.trim());
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
        for (const [res, label, cls] of [[p.si[0], p.si[1], "yes"], [p.no[0], p.no[1], "no"]]) {
            const b = el("button", cls, label);
            b.type = "button";
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
        const accion = {id: DtCola.uuid(), jugadorId: j.id, jugadorNombre: j.nombre, tipo, resultado,
            tiempo: partido.estado === "PENDIENTE" ? 1 : r.tiempo, segundo: jugados()};
        if (navigator.vibrate) navigator.vibrate(15);
        await DtCola.agregar(partido.id, accion);
        await refrescarPend();
        toast(`${NOMBRE[tipo]}${resultado ? " · " + RESULTADO[resultado] : ""} · ${j.numero ? j.numero + " " : ""}${corto(j.nombre)}`,
            () => borrar(accion.id));
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
            const def = SIMPLES.find(s => s.tipo === a.tipo && s.res === a.resultado);
            const tag = el("span", "dt-tag");
            tag.style.background = def && COLOR[def.cls] || (golAFavor(a) ? "var(--good)" : golEnContra(a) ? "var(--err)" : "var(--muted)");
            w.append(tag, `${NOMBRE[a.tipo]}${a.resultado ? " · " + RESULTADO[a.resultado] : ""}`);
            const n = nums.get(clave(a));
            w.append(el("small", null, `${n ? n + " " : ""}${a.jugadorNombre}${a.pendiente ? " · sin enviar" : ""}`));
            const del = el("button", null, "Borrar");
            del.type = "button";
            del.setAttribute("aria-label", `Borrar ${NOMBRE[a.tipo]} de ${a.jugadorNombre}`);
            del.disabled = partido.estado === "CERRADO";
            del.addEventListener("click", () => borrar(a.id));
            li.append(m, w, del);
            ul.append(li);
        }
    }

    function renderResumen(acc) {
        const c = (k, tipo, res) => acc.filter(a => (k == null || clave(a) === k) && a.tipo === tipo
            && (res === undefined || a.resultado === res)).length;
        const tiros = c(null, "TIRO"), rec = c(null, "RECUPERACION"), perd = c(null, "PERDIDA");
        const kp = [
            [`${c(null, "TIRO", "GOL")}/${tiros}`, "Tiros al arco convertidos"],
            [`${rec} - ${perd}`, "Recuperaciones - pérdidas"],
            [c(null, "ATAJADA") + c(null, "LIBRE_CONTRA", "ATAJADO") + c(null, "PENAL_CONTRA", "ATAJADO"), "Atajadas (con libres y penales)"],
        ];
        $("kpis").replaceChildren(...kp.map(([v, l]) => { const d = el("div", "dt-kpi"); d.append(el("b", null, String(v)), el("span", null, l)); return d; }));
        const conv = (k, tipo) => `${c(k, tipo, "GOL")}/${c(k, tipo)}`;
        const cols = [
            ["Pérd. P", k => c(k, "PERDIDA", "PROPIO")], ["Pérd. R", k => c(k, "PERDIDA", "RIVAL")],
            ["Rec. P", k => c(k, "RECUPERACION", "PROPIO")], ["Rec. R", k => c(k, "RECUPERACION", "RIVAL")],
            ["Tiros", k => conv(k, "TIRO")], ["Libres", k => conv(k, "LIBRE_FAVOR")], ["Penales", k => conv(k, "PENAL_FAVOR")],
            ["Ataj.", k => c(k, "ATAJADA") + c(k, "LIBRE_CONTRA", "ATAJADO") + c(k, "PENAL_CONTRA", "ATAJADO")],
            ["Recib.", k => c(k, "GOL_RECIBIDO") + c(k, "LIBRE_CONTRA", "GOL") + c(k, "PENAL_CONTRA", "GOL")],
            ["F. com.", k => c(k, "FALTA", "COMETIDA")], ["F. rec.", k => c(k, "FALTA", "RECIBIDA")],
            ["Am", k => c(k, "TARJETA", "AMARILLA")], ["Az", k => c(k, "TARJETA", "AZUL")], ["Rj", k => c(k, "TARJETA", "ROJA")],
        ];
        const t = $("resumen");
        t.replaceChildren();
        const head = el("thead"), hr = el("tr");
        ["Jugador", ...cols.map(x => x[0])].forEach(h => { const th = el("th", null, h); th.scope = "col"; hr.append(th); });
        head.append(hr);
        const body = el("tbody");
        for (const j of jugadores()) {
            if (!acc.some(a => clave(a) === j.key)) continue;
            const tr = el("tr");
            tr.append(el("td", null, `${j.numero ? j.numero + " " : ""}${corto(j.nombre)}`));
            cols.forEach(([, f]) => tr.append(el("td", null, String(f(j.key)))));
            body.append(tr);
        }
        if (!body.children.length) {
            const tr = el("tr"), td = el("td", null, "Sin acciones cargadas.");
            td.colSpan = cols.length + 1;
            tr.append(td);
            body.append(tr);
        }
        const foot = el("tfoot"), fr = el("tr");
        fr.append(el("td", null, "Equipo"));
        cols.forEach(([, f]) => fr.append(el("td", null, String(f(null)))));
        foot.append(fr);
        t.append(head, body, foot);

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
        if (id === "t-partidos") { location.hash = ""; return; }
        for (const [t, v] of [["t-cargar", "p-cargar"], ["t-resumen", "p-resumen"]]) {
            $(t).setAttribute("aria-selected", String(t === id));
            $(v).hidden = t !== id;
        }
    }

    function ruta() {
        const m = location.hash.match(/^#partido=(\d+)$/);
        if (m) { abrir(Number(m[1])); return; }
        partido = null;
        actualizarWakeLock();
        $("v-partido").hidden = true;
        $("v-elegir").hidden = false;
        document.body.classList.remove("dt-vivo");
        cargarMis();
    }

    // ---------- Inicio ----------

    $("brand").outerHTML = BRAND;
    for (const t of ["t-cargar", "t-resumen", "t-partidos"]) $(t).addEventListener("click", () => tab(t));
    $("rival").addEventListener("change", elegirRival);
    $("equipo").addEventListener("change", cargarRivales);
    $("otro").addEventListener("input", () => { $("crear").disabled = !rivalElegido(); });
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
        const eq = $("equipo");
        s.equipos.forEach((e, i) => { const o = el("option", null, e.nombre); o.value = String(i); eq.append(o); });
        $("f-equipo").hidden = s.equipos.length < 2;
        if (s.suscripcion && s.suscripcion.partidosPorMes) $("cupo").textContent = `${s.suscripcion.partidosPorMes} partidos por mes`;
        await DtCola.iniciar(async ({partido: p, pendientes, error}) => {
            pendientesTotal = pendientes;
            if (p && partido && p.id === partido.id) recibir(p, Date.now());
            if (error) toast(error);
            if (partido) await refrescarPend(); else if (!$("v-elegir").hidden && p) cargarMis();
        });
        if (s.equipos.length) cargarRivales();
        ruta();
    });
})();
