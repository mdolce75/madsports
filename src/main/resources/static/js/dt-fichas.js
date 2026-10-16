// Fichas de los jugadores y dashboard de Métricas DT: todo se calcula en el navegador con las acciones que cargó el
// DT (GET /api/dt/acciones), el plantel y la lista de partidos. También dibuja los gráficos (SVG propio, sin
// librerías, para que funcione sin señal).
const DtFichas = (() => {
    const A_FAVOR = ["TIRO", "LIBRE_FAVOR", "PENAL_FAVOR"];
    const MESES = ["Ene", "Feb", "Mar", "Abr", "May", "Jun", "Jul", "Ago", "Sep", "Oct", "Nov", "Dic"];
    const num = (v, d = 1) => v.toLocaleString("es-AR", {minimumFractionDigits: 0, maximumFractionDigits: d});
    const pct = v => Math.round(v * 100) + "%";

    // Ejes de cada ficha: los del jugador de campo y los del arquero. alto = más es mejor; bajo = menos es mejor.
    const EJES_CAMPO = [
        {clave: "ataque", nombre: "Ataque", color: "#22b8f0", alto: true},
        {clave: "precision", nombre: "Precisión", color: "#f97316", alto: true},
        {clave: "desequilibrio", nombre: "Desequilibrio", color: "#a78bfa", alto: true},
        {clave: "recuperacion", nombre: "Recuperación", color: "#22c55e", alto: true},
        {clave: "disciplina", nombre: "Disciplina", color: "#ff6b6b", alto: false},
    ];
    const EJES_ARQUERO = [
        {clave: "atajadas", nombre: "Atajadas", color: "#22b8f0", alto: true},
        {clave: "efectividad", nombre: "Efectividad", color: "#22c55e", alto: true},
        {clave: "pelotaParada", nombre: "Pelota parada", color: "#f97316", alto: true},
        {clave: "valla", nombre: "Valla", color: "#a78bfa", alto: false},
        {clave: "disciplina", nombre: "Disciplina", color: "#ff6b6b", alto: false},
    ];

    const clave = a => a.jugadorId > 0 ? "j" + a.jugadorId : "m" + a.jugadorNombre;

    /** Cuenta lo de cada jugador: goles, tiros, recuperaciones, faltas, tarjetas, atajadas... */
    function contar(lista) {
        const c = {partidos: new Set(), goles: 0, intentos: 0, pateadas: 0, faltasRec: 0, rec: 0, perd: 0, faltasCom: 0,
            amarillas: 0, azules: 0, rojas: 0, atajadas: 0, recibidos: 0, ppAtajadas: 0, ppTotal: 0};
        for (const a of lista) {
            c.partidos.add(a.partidoId);
            if (A_FAVOR.includes(a.tipo)) {
                c.intentos++;
                if (a.resultado === "GOL") c.goles++;
                if (a.tipo !== "TIRO") c.pateadas++;
            }
            if (a.tipo === "FALTA") a.resultado === "RECIBIDA" ? c.faltasRec++ : c.faltasCom++;
            if (a.tipo === "RECUPERACION") c.rec++;
            if (a.tipo === "PERDIDA") c.perd++;
            if (a.tipo === "TARJETA") a.resultado === "AMARILLA" ? c.amarillas++ : a.resultado === "AZUL" ? c.azules++ : c.rojas++;
            if (a.tipo === "ATAJADA") c.atajadas++;
            if (a.tipo === "GOL_RECIBIDO") c.recibidos++;
            if (a.tipo === "LIBRE_CONTRA" || a.tipo === "PENAL_CONTRA") {
                c.ppTotal++;
                a.resultado === "GOL" ? c.recibidos++ : c.ppAtajadas++;
            }
        }
        c.pj = c.partidos.size;
        return c;
    }

    const disciplina = c => (c.faltasCom + c.amarillas * 2 + c.azules * 3 + c.rojas * 5) / (c.pj || 1);
    const calificacion = v => v === 0 ? "Impecable" : v < 1 ? "Buena" : v < 2 ? "Regular" : "Para mejorar";

    /** Valor de cada eje (crudo, antes de compararlo con el plantel) y lo que se muestra en la fila. */
    function ejes(c, arquero) {
        const pj = c.pj || 1;
        if (!arquero) {
            const tiros = c.intentos;
            return {
                ataque: {v: c.goles / pj, texto: `${num(c.goles / pj)} goles por partido`, a: c.goles, b: tiros, etiquetas: ["Goles", "Tiros"]},
                precision: {v: tiros ? c.goles / tiros : 0, texto: tiros ? `${pct(c.goles / tiros)} convertidos` : "Sin tiros",
                    a: c.goles, b: tiros - c.goles, etiquetas: ["Convertidos", "Errados"]},
                desequilibrio: {v: c.faltasRec / pj, texto: `${num(c.faltasRec / pj)} por partido`,
                    a: c.faltasRec, b: c.pateadas, etiquetas: ["Faltas recibidas", "Libres y penales"]},
                recuperacion: {v: c.rec + c.perd ? c.rec / (c.rec + c.perd) : 0,
                    texto: c.rec + c.perd ? `${pct(c.rec / (c.rec + c.perd))} de balance` : "Sin datos",
                    a: c.rec, b: c.perd, etiquetas: ["Recuperaciones", "Pérdidas"]},
                disciplina: {v: disciplina(c), texto: calificacion(disciplina(c)), a: c.faltasCom,
                    b: c.amarillas + c.azules + c.rojas, etiquetas: ["Faltas", "Tarjetas"]},
            };
        }
        const salvadas = c.atajadas + c.ppAtajadas;
        return {
            atajadas: {v: salvadas / pj, texto: `${num(salvadas / pj)} por partido`, a: c.atajadas, b: c.ppAtajadas,
                etiquetas: ["Atajadas", "Libres y penales"]},
            efectividad: {v: salvadas + c.recibidos ? salvadas / (salvadas + c.recibidos) : 0,
                texto: salvadas + c.recibidos ? `${pct(salvadas / (salvadas + c.recibidos))} atajados` : "Sin datos",
                a: salvadas, b: c.recibidos, etiquetas: ["Atajados", "Recibidos"]},
            pelotaParada: {v: c.ppTotal ? c.ppAtajadas / c.ppTotal : 0, texto: c.ppTotal ? `${pct(c.ppAtajadas / c.ppTotal)} atajados` : "Sin libres ni penales",
                a: c.ppAtajadas, b: c.ppTotal - c.ppAtajadas, etiquetas: ["Atajados", "Convertidos"]},
            valla: {v: c.recibidos / pj, texto: `${num(c.recibidos / pj)} por partido`, a: c.recibidos, b: c.pj,
                etiquetas: ["Recibidos", "Partidos"]},
            disciplina: {v: disciplina(c), texto: calificacion(disciplina(c)), a: c.faltasCom,
                b: c.amarillas + c.azules + c.rojas, etiquetas: ["Faltas", "Tarjetas"]},
        };
    }

    /**
     * Fichas del plantel: cada eje va de 0 a 100 comparado con el resto del plantel (100 = el mejor del equipo en ese
     * eje), los jugadores de campo entre sí y los arqueros entre sí. El índice es el promedio de los cinco ejes.
     */
    function calcular(plantel, acciones) {
        const porJugador = new Map();
        for (const a of acciones) {
            const k = clave(a);
            if (!porJugador.has(k)) porJugador.set(k, []);
            porJugador.get(k).push(a);
        }
        const jugadores = plantel.map(j => ({key: "j" + j.id, numero: j.numero, nombre: j.nombre, arquero: j.arquero}));
        for (const [k, lista] of porJugador) {
            if (jugadores.some(j => j.key === k)) continue;
            const arco = lista.filter(a => ["ATAJADA", "GOL_RECIBIDO", "LIBRE_CONTRA", "PENAL_CONTRA"].includes(a.tipo)).length;
            jugadores.push({key: k, numero: "", nombre: lista[0].jugadorNombre, arquero: arco * 2 > lista.length});
        }
        const fichas = jugadores.map(j => {
            const c = contar(porJugador.get(j.key) || []);
            return Object.assign({}, j, {partidos: c.pj, valores: ejes(c, j.arquero)});
        });
        for (const arquero of [false, true]) {
            const grupo = fichas.filter(f => f.arquero === arquero && f.partidos > 0);
            for (const e of arquero ? EJES_ARQUERO : EJES_CAMPO) {
                const vs = grupo.map(f => f.valores[e.clave].v);
                const max = Math.max(0, ...vs), min = Math.min(...vs);
                for (const f of grupo) {
                    const v = f.valores[e.clave].v;
                    f.valores[e.clave].puntaje = e.alto ? (max > 0 ? Math.round(100 * v / max) : 0)
                        : (max > min ? Math.round(100 * (max - v) / (max - min)) : 100);
                }
            }
        }
        for (const f of fichas) {
            f.ejes = (f.arquero ? EJES_ARQUERO : EJES_CAMPO).map(e => Object.assign({}, e, f.valores[e.clave],
                {puntaje: f.partidos ? f.valores[e.clave].puntaje : 0}));
            f.indice = f.partidos ? Math.round(f.ejes.reduce((s, e) => s + e.puntaje, 0) / f.ejes.length) : null;
            delete f.valores;
        }
        return fichas.sort((a, b) => (b.indice ?? -1) - (a.indice ?? -1) || (parseInt(a.numero) || 999) - (parseInt(b.numero) || 999));
    }

    /** Dashboard: totales, estado de los partidos, partidos por mes (los últimos 6) y los próximos. */
    function dashboard(partidos, hoy) {
        const mesActual = hoy.slice(0, 7);
        const jugados = p => p.estado !== "PENDIENTE";
        const meses = [];
        const [y, m] = mesActual.split("-").map(Number);
        for (let i = 5; i >= 0; i--) {
            const d = new Date(y, m - 1 - i, 1);
            const k = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
            meses.push({mes: k, etiqueta: MESES[d.getMonth()], cantidad: partidos.filter(p => jugados(p) && p.fecha.startsWith(k)).length});
        }
        return {
            partidos: partidos.length,
            esteMes: partidos.filter(p => p.fecha.startsWith(mesActual)).length,
            estados: {
                programados: partidos.filter(p => p.estado === "PENDIENTE").length,
                enJuego: partidos.filter(p => p.estado === "EN_JUEGO" || p.estado === "ENTRETIEMPO").length,
                finalizados: partidos.filter(p => p.estado === "TERMINADO" || p.estado === "CERRADO").length,
            },
            meses,
            proximos: partidos.filter(p => p.estado === "PENDIENTE" && p.fecha >= hoy)
                .sort((a, b) => a.fecha.localeCompare(b.fecha)).slice(0, 5),
        };
    }

    // ---------- Gráficos (SVG) ----------

    const esc = s => String(s).replace(/[&<>"]/g, c => ({"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;"}[c]));

    /** Pentágono de la ficha: anillos al 33, 66 y 100 % y el área del jugador. */
    function pentagono(ejesFicha) {
        const cx = 160, cy = 104, R = 68;
        const punto = (i, r) => {
            const ang = -Math.PI / 2 + i * 2 * Math.PI / 5;
            return [cx + r * Math.cos(ang), cy + r * Math.sin(ang)];
        };
        const poly = r => [0, 1, 2, 3, 4].map(i => punto(i, r).map(n => n.toFixed(1)).join(",")).join(" ");
        const area = ejesFicha.map((e, i) => punto(i, R * Math.max(0.06, e.puntaje / 100)).map(n => n.toFixed(1)).join(",")).join(" ");
        const etiquetas = ejesFicha.map((e, i) => {
            const [x, y] = punto(i, R + 16);
            const ancla = Math.abs(x - cx) < 4 ? "middle" : x > cx ? "start" : "end";
            return `<text x="${x.toFixed(1)}" y="${(y + 3).toFixed(1)}" text-anchor="${ancla}">${esc(e.nombre)}</text>`;
        }).join("");
        const titulo = ejesFicha.map(e => `${e.nombre} ${e.puntaje}`).join(", ");
        return `<svg class="pentagono" viewBox="0 0 320 200" role="img" aria-label="${esc(titulo)}"><title>${esc(titulo)}</title>
            <g class="anillos">${[1, 2 / 3, 1 / 3].map(f => `<polygon points="${poly(R * f)}"/>`).join("")}
            ${[0, 1, 2, 3, 4].map(i => { const [x, y] = punto(i, R); return `<line x1="${cx}" y1="${cy}" x2="${x.toFixed(1)}" y2="${y.toFixed(1)}"/>`; }).join("")}</g>
            <polygon class="area" points="${area}"/>
            <g class="etiquetas">${etiquetas}</g></svg>`;
    }

    /** Estado de los partidos: una barra por estado. */
    function barrasEstado(est) {
        const filas = [["Finalizados", est.finalizados, "var(--accent)"], ["En juego", est.enJuego, "var(--lime)"],
            ["Programados", est.programados, "var(--muted)"]];
        const max = Math.max(1, ...filas.map(f => f[1]));
        return `<svg class="grafico" viewBox="0 0 300 110" role="img" aria-label="Estado de los partidos: ${filas.map(f => f[0] + " " + f[1]).join(", ")}">
            ${filas.map(([n, v, c], i) => `<text x="0" y="${22 + i * 34}" class="eje">${n}</text>
                <rect x="96" y="${9 + i * 34}" width="${Math.max(3, 170 * v / max).toFixed(1)}" height="18" rx="4" fill="${c}"><title>${n}: ${v}</title></rect>
                <text x="${(102 + 170 * v / max).toFixed(1)}" y="${22 + i * 34}" class="valor">${v}</text>`).join("")}</svg>`;
    }

    /** Partidos jugados por mes: eje X los meses, eje Y la cantidad. */
    function lineaMeses(meses) {
        const W = 320, H = 150, x0 = 30, y0 = 120, ancho = W - x0 - 14, alto = 100;
        const max = Math.max(2, ...meses.map(m => m.cantidad));
        const paso = ancho / (meses.length - 1);
        const pts = meses.map((m, i) => [x0 + i * paso, y0 - alto * m.cantidad / max]);
        const ticks = [0, Math.round(max / 2), max];
        return `<svg class="grafico" viewBox="0 0 ${W} ${H}" role="img" aria-label="Partidos por mes: ${meses.map(m => m.etiqueta + " " + m.cantidad).join(", ")}">
            ${ticks.map(t => { const y = (y0 - alto * t / max).toFixed(1); return `<line class="grilla" x1="${x0}" y1="${y}" x2="${W - 10}" y2="${y}"/><text class="eje" x="${x0 - 8}" y="${+y + 3}" text-anchor="end">${t}</text>`; }).join("")}
            <polygon class="area-linea" points="${x0},${y0} ${pts.map(p => p.map(n => n.toFixed(1)).join(",")).join(" ")} ${(x0 + ancho).toFixed(1)},${y0}"/>
            <polyline class="linea" points="${pts.map(p => p.map(n => n.toFixed(1)).join(",")).join(" ")}"/>
            ${pts.map(([x, y], i) => `<circle class="punto" cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="4"><title>${meses[i].etiqueta}: ${meses[i].cantidad} partidos</title></circle>
                <text class="eje" x="${x.toFixed(1)}" y="${H - 8}" text-anchor="middle">${meses[i].etiqueta}</text>`).join("")}</svg>`;
    }

    return {calcular, dashboard, pentagono, barrasEstado, lineaMeses};
})();
