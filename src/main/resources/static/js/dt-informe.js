// Informes de Métricas DT: el de un partido (con sus eventos) y el de los partidos finalizados de un mes, un bimestre,
// un semestre o un campeonato; del equipo o de un jugador. Se arma con las acciones (también las que todavía no se
// enviaron) para verlo en vivo y sin señal. Las métricas son las mismas que las del PDF (InformeDt.java).
const DtInforme = (() => {
    const MESES = ["enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre",
        "noviembre", "diciembre"];
    const ARCO = new Set(["ATAJADA", "GOL_RECIBIDO", "LIBRE_CONTRA", "PENAL_CONTRA"]);
    const golAFavor = a => a.resultado === "GOL" && ["TIRO", "LIBRE_FAVOR", "PENAL_FAVOR"].includes(a.tipo);
    const golEnContra = a => a.tipo === "GOL_RECIBIDO" || a.resultado === "GOL" && ["LIBRE_CONTRA", "PENAL_CONTRA"].includes(a.tipo);
    const clave = a => a.jugadorId > 0 ? "j" + a.jugadorId : "m" + a.jugadorNombre;
    const finalizado = p => p.estado === "TERMINADO" || p.estado === "CERRADO";
    const mmss = s => String(Math.floor(s / 60)).padStart(2, "0") + ":" + String(s % 60).padStart(2, "0");
    const fecha = f => f ? `${f.slice(8, 10)}/${f.slice(5, 7)}/${f.slice(0, 4)}` : "";
    const pct = (a, b) => b ? Math.round(100 * a / b) : null;

    /** Las métricas de un grupo de acciones (las mismas claves que el JSON del servidor). */
    function metricas(acc) {
        const n = (tipo, res) => acc.filter(a => a.tipo === tipo && (res === undefined || a.resultado === res)).length;
        const m = {
            goles: acc.filter(golAFavor).length, golesRecibidos: acc.filter(golEnContra).length,
            tiros: n("TIRO"), tirosGol: n("TIRO", "GOL"),
            atajadas: n("ATAJADA") + n("LIBRE_CONTRA", "ATAJADO") + n("PENAL_CONTRA", "ATAJADO"),
            perdidasDefensa: n("PERDIDA", "PROPIO"), perdidasAtaque: n("PERDIDA", "RIVAL"),
            recuperacionesDefensa: n("RECUPERACION", "PROPIO"), recuperacionesAtaque: n("RECUPERACION", "RIVAL"),
            faltasRecibidas: n("FALTA", "RECIBIDA"), faltasCometidas: n("FALTA", "COMETIDA"),
            azules: n("TARJETA", "AZUL"), amarillas: n("TARJETA", "AMARILLA"), rojas: n("TARJETA", "ROJA"),
            penales: n("PENAL_FAVOR"), penalesGol: n("PENAL_FAVOR", "GOL"),
            libres: n("LIBRE_FAVOR"), libresGol: n("LIBRE_FAVOR", "GOL"),
        };
        m.perdidas = m.perdidasDefensa + m.perdidasAtaque;
        m.recuperaciones = m.recuperacionesDefensa + m.recuperacionesAtaque;
        m.efectividadTiros = pct(m.tirosGol, m.tiros);
        m.efectividadPenales = pct(m.penalesGol, m.penales);
        m.efectividadLibres = pct(m.libresGol, m.libres);
        return m;
    }

    /** Resultado del partido para el DT: G, E o P. */
    const resultado = p => p.golesFavor > p.golesContra ? "G" : p.golesFavor === p.golesContra ? "E" : "P";

    /**
     * Arma el informe. partidos: los del informe (uno en el de un partido), con golesFavor y golesContra; acciones: las
     * activas, con partidoId; jugadores: [{key, numero, nombre, arquero}] para el número y los arqueros; jugador: la
     * clave del elegido, o null.
     */
    function armar({titulo, detalle, unPartido, partidos, acciones, jugadores = [], jugador = null, nombreAccion}) {
        const porJugador = new Map();
        for (const a of acciones) {
            if (!porJugador.has(clave(a))) porJugador.set(clave(a), []);
            porJugador.get(clave(a)).push(a);
        }
        const conocidos = new Map(jugadores.map(j => [j.key, j]));
        let filas = [...porJugador].map(([key, acc]) => {
            const j = conocidos.get(key);
            return {clave: key, numero: j && j.numero || "", nombre: acc[0].jugadorNombre || (j && j.nombre) || "",
                arquero: j ? !!j.arquero : acc.some(a => ARCO.has(a.tipo)),
                partidos: new Set(acc.map(a => a.partidoId)).size, metricas: metricas(acc)};
        });
        const num = n => parseInt(n, 10) || 999;
        filas.sort((a, b) => num(a.numero) - num(b.numero) || a.nombre.localeCompare(b.nombre, "es"));
        const elegido = jugador ? filas.find(f => f.clave === jugador) || null : null;
        const lasDel = elegido ? porJugador.get(elegido.clave) : acciones;
        if (elegido) filas = [elegido];
        const nums = new Map(filas.map(f => [f.clave, f.numero]));
        const dur = (partidos[0] && partidos[0].minutosTiempo || 25) * 60;
        const eventos = !unPartido ? [] : lasDel.slice()
            .sort((a, b) => a.tiempo - b.tiempo || a.segundo - b.segundo)
            .map(a => {
                const n = nombreAccion(a.tipo, a.resultado);
                return {tiempo: a.tiempo, segundo: a.segundo, minuto: `${a.tiempo}T ${mmss(Math.min(a.segundo, dur))}`,
                    clave: clave(a), numero: nums.get(clave(a)) || "", jugador: a.jugadorNombre, accion: n.nombre,
                    color: n.col, pendiente: !!a.pendiente};
            });
        const ps = partidos.map(p => ({id: p.id, fecha: p.fecha, rival: p.rival, local: p.local, torneo: p.torneo,
            golesFavor: p.golesFavor, golesContra: p.golesContra, resultado: resultado(p)}));
        const balance = {jugados: ps.length, ganados: ps.filter(p => p.resultado === "G").length,
            empatados: ps.filter(p => p.resultado === "E").length, perdidos: ps.filter(p => p.resultado === "P").length,
            golesFavor: ps.reduce((s, p) => s + p.golesFavor, 0), golesContra: ps.reduce((s, p) => s + p.golesContra, 0)};
        return {titulo, detalle, unPartido, partidos: ps, balance, totales: metricas(lasDel), jugadores: filas,
            jugador: elegido, eventos};
    }

    // ---------- Períodos ----------

    const TIPOS = {MES: "Mes", BIMESTRE: "Bimestre", SEMESTRE: "Semestre", CAMPEONATO: "Campeonato"};

    /** Valor del período en el que cae un partido (igual que Periodo.java). */
    function valor(tipo, p) {
        const y = p.fecha.slice(0, 4), m = Number(p.fecha.slice(5, 7));
        if (tipo === "MES") return p.fecha.slice(0, 7);
        if (tipo === "BIMESTRE") return `${y}-${Math.floor((m + 1) / 2)}`;
        if (tipo === "SEMESTRE") return `${y}-${m <= 6 ? 1 : 2}`;
        return p.torneo || "";
    }

    function nombre(tipo, v) {
        if (tipo === "CAMPEONATO") return v ? v : "Sin campeonato";
        const [y, n] = v.split("-").map(Number);
        if (tipo === "MES") return MESES[n - 1].replace(/^./, c => c.toUpperCase()) + " " + y;
        if (tipo === "BIMESTRE") return `Bimestre ${MESES[2 * n - 2]}-${MESES[2 * n - 1]} ${y}`;
        return `${n}.º semestre ${y} (${n === 1 ? "enero a junio" : "julio a diciembre"})`;
    }

    /** Los valores del tipo en los que hay partidos finalizados, del más reciente al más viejo: [{valor, nombre, n}]. */
    function opciones(tipo, partidos) {
        const cuenta = new Map(), ultima = new Map();
        for (const p of partidos.filter(finalizado)) {
            const v = valor(tipo, p);
            cuenta.set(v, (cuenta.get(v) || 0) + 1);
            if (!ultima.has(v) || ultima.get(v) < p.fecha) ultima.set(v, p.fecha);
        }
        return [...cuenta].map(([v, n]) => ({valor: v, nombre: nombre(tipo, v), n}))
            .sort((a, b) => tipo === "CAMPEONATO" ? ultima.get(b.valor).localeCompare(ultima.get(a.valor))
                : b.valor.localeCompare(a.valor, undefined, {numeric: true}));
    }

    // ---------- Pantalla ----------

    const el = (tag, cls, txt) => { const e = document.createElement(tag); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; };
    const ef = (v, a, b) => v == null ? ["–", "sin intentos"] : [v + "%", `${a}/${b}`];

    /** Las 14 métricas del informe, como tarjetas: [valor, nombre, detalle, clase]. */
    function tarjetas(m) {
        const [et, etd] = ef(m.efectividadTiros, m.tirosGol, m.tiros);
        const [ep, epd] = ef(m.efectividadPenales, m.penalesGol, m.penales);
        const [el2, eld] = ef(m.efectividadLibres, m.libresGol, m.libres);
        return [
            [m.goles, "Goles"], [m.golesRecibidos, "Goles recibidos"], [m.tiros, "Tiros"],
            [et, "Efectividad tiros", etd], [m.atajadas, "Atajadas"],
            [m.perdidas, "Pérdidas", `${m.perdidasDefensa} def. · ${m.perdidasAtaque} ataq.`],
            [m.recuperaciones, "Recuperaciones", `${m.recuperacionesDefensa} def. · ${m.recuperacionesAtaque} ataq.`],
            [m.faltasRecibidas, "Faltas recibidas"], [m.faltasCometidas, "Faltas cometidas"],
            [m.azules, "Tarjeta azul", null, "azul"], [m.amarillas, "Tarjeta amarilla", null, "amarilla"],
            [m.rojas, "Tarjeta roja", null, "roja"],
            [ep, "Efectividad penales", epd], [el2, "Efectividad libres", eld],
        ];
    }

    /**
     * Muestra el informe en box. opts: onJugador(clave o null) al elegir un jugador, onPdf() para exportar, jugadores
     * para el selector (todos los del informe del equipo) y vacio (texto sin acciones).
     */
    function render(box, inf, opts = {}) {
        box.replaceChildren();
        const head = el("div", "inf-head");
        const t = el("div", "inf-titulo");
        t.append(el("h3", null, inf.titulo), el("small", null, inf.detalle));
        const acciones = el("div", "inf-acciones");
        const sel = el("select");
        sel.setAttribute("aria-label", "Jugador del informe");
        const todos = el("option", null, "Todo el equipo");
        todos.value = "";
        sel.append(todos);
        for (const j of opts.jugadores || inf.jugadores) {
            const o = el("option", null, `${j.numero ? j.numero + " · " : ""}${j.nombre}`);
            o.value = j.clave;
            sel.append(o);
        }
        sel.value = inf.jugador ? inf.jugador.clave : "";
        sel.addEventListener("change", () => opts.onJugador && opts.onJugador(sel.value || null));
        acciones.append(sel);
        if (opts.onPdf) {
            const b = el("button", "btn primary inf-pdf", "Exportar PDF");
            b.type = "button";
            b.addEventListener("click", opts.onPdf);
            acciones.append(b);
        }
        head.append(t, acciones);
        box.append(head);

        const m = inf.totales;
        box.append(el("h4", "inf-sub", inf.jugador ? `Estadísticas de ${inf.jugador.nombre}` : "Estadísticas del equipo"));
        const kp = el("div", "inf-kpis");
        for (const [v, n, d, cls] of tarjetas(m)) {
            const k = el("div", "dt-kpi" + (cls ? " t-" + cls : ""));
            k.append(el("b", null, String(v)), el("span", null, n));
            if (d) k.append(el("small", null, d));
            kp.append(k);
        }
        box.append(kp);

        if (!inf.unPartido) {
            const b = inf.balance;
            box.append(el("h4", "inf-sub", "Partidos"),
                el("p", "hint", `${b.jugados} jugados · ${b.ganados} ganados · ${b.empatados} empatados · ${b.perdidos} perdidos`
                    + ` · Goles: ${b.golesFavor} a favor, ${b.golesContra} en contra`));
            const ul = el("ul", "dt-lista inf-partidos");
            for (const p of inf.partidos) {
                const li = el("li"), bt = el("button");
                bt.type = "button";
                bt.append(el("span", null, `${fecha(p.fecha)} · ${p.rival}`),
                    el("b", "r-" + p.resultado, `${p.golesFavor} - ${p.golesContra}`),
                    el("small", null, [p.local ? "Local" : "Visitante", p.torneo].filter(Boolean).join(" · ")));
                if (opts.onPartido) bt.addEventListener("click", () => opts.onPartido(p.id));
                li.append(bt);
                ul.append(li);
            }
            box.append(ul);
        }

        if (!inf.jugador) {
            box.append(el("h4", "inf-sub", "Estadísticas por jugador"));
            const wrap = el("div", "dt-tablewrap"), tb = el("table", "dt-table inf-tabla");
            const cols = [
                ["G", x => x.goles], ["GR", x => x.golesRecibidos], ["Tiros", x => x.tiros],
                ["Ef.", x => x.efectividadTiros == null ? "–" : x.efectividadTiros + "%"], ["Ataj.", x => x.atajadas],
                ["Pérd.", x => x.perdidas], ["Rec.", x => x.recuperaciones], ["FR", x => x.faltasRecibidas],
                ["FC", x => x.faltasCometidas], ["Az", x => x.azules], ["Am", x => x.amarillas], ["Rj", x => x.rojas],
                ["Pen.", x => x.penales ? `${x.penalesGol}/${x.penales}` : "–"],
                ["Lib.", x => x.libres ? `${x.libresGol}/${x.libres}` : "–"],
            ];
            if (!inf.unPartido) cols.unshift(["PJ", null]);
            const hr = el("tr");
            ["Jugador", ...cols.map(c => c[0])].forEach(h => { const th = el("th", null, h); th.scope = "col"; hr.append(th); });
            const thead = el("thead");
            thead.append(hr);
            const body = el("tbody");
            for (const j of inf.jugadores) {
                const tr = el("tr", "clic");
                const td = el("td");
                td.append(document.createTextNode(`${j.numero ? j.numero + " " : ""}${j.nombre}`));
                if (j.arquero) td.append(el("span", "inf-ar", "Arq."));
                tr.append(td);
                for (const [h, f] of cols) tr.append(el("td", null, String(h === "PJ" ? j.partidos : f(j.metricas))));
                tr.title = "Ver solo a " + j.nombre;
                tr.addEventListener("click", () => opts.onJugador && opts.onJugador(j.clave));
                body.append(tr);
            }
            if (!inf.jugadores.length) {
                const tr = el("tr"), td = el("td", null, opts.vacio || "Sin acciones cargadas.");
                td.colSpan = cols.length + 1;
                tr.append(td);
                body.append(tr);
            }
            const foot = el("tfoot"), fr = el("tr");
            fr.append(el("td", null, "Equipo"));
            for (const [h, f] of cols) fr.append(el("td", null, String(h === "PJ" ? inf.balance.jugados : f(m))));
            foot.append(fr);
            tb.append(thead, body, foot);
            wrap.append(tb);
            box.append(wrap);
        }

        if (inf.unPartido) {
            box.append(el("h4", "inf-sub", inf.jugador ? `Eventos de ${inf.jugador.nombre}` : "Eventos del partido"));
            const ul = el("ul", "inf-eventos");
            if (!inf.eventos.length) ul.append(el("li", "dt-vacio", "Todavía no hay acciones cargadas."));
            for (const e of inf.eventos) {
                const li = el("li");
                const tag = el("span", "dt-tag");
                tag.style.background = e.color;
                const w = el("span", "w");
                w.append(tag, e.accion);
                li.append(el("span", "m", e.minuto), w,
                    el("span", "j", `${e.numero ? e.numero + " " : ""}${e.jugador}${e.pendiente ? " · sin enviar" : ""}`));
                ul.append(li);
            }
            box.append(ul);
        }
        box.append(el("p", "dt-note", "G: goles (tiros, libres y penales convertidos) · GR: goles recibidos por el arquero"
            + " · Ef.: efectividad de los tiros al arco · Ataj.: atajadas, con libres y penales atajados · Pérd. y Rec.:"
            + " en defensa (campo propio) y en ataque (campo rival) · FR y FC: faltas recibidas y cometidas."
            + (inf.unPartido ? " Minuto: tiempo jugado del período." : " PJ: partidos con acciones del jugador.")));
    }

    return {metricas, armar, opciones, nombre, valor, render, finalizado, fecha, TIPOS};
})();
