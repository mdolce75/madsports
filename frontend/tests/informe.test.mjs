// Períodos de los informes en la pantalla (js/dt/informe.js): los mismos valores y nombres que Periodo.java.
// @vitest-environment node
import {test} from "vitest";
import assert from "node:assert/strict";
import * as Informe from "../../src/main/resources/static/js/dt/informe.js";

const p = (id, fecha, torneo, estado = "CERRADO") => ({id, fecha, torneo, estado});
const partidos = [p(1, "2026-03-10", "Apertura"), p(2, "2026-04-02", "Apertura"), p(3, "2026-09-30", "Clausura"),
    p(4, "2026-10-12", "Clausura", "TERMINADO"), p(5, "2026-10-20", "Clausura", "PENDIENTE"), p(6, "2026-07-01", null)];

test("cada partido cae en su mes, bimestre, semestre y campeonato", () => {
    const x = partidos[3];
    assert.equal(Informe.valor("MES", x), "2026-10");
    assert.equal(Informe.valor("BIMESTRE", x), "2026-5");
    assert.equal(Informe.valor("SEMESTRE", x), "2026-2");
    assert.equal(Informe.valor("CAMPEONATO", x), "Clausura");
    assert.equal(Informe.valor("CAMPEONATO", partidos[5]), "");
});

test("nombres de los períodos, como en el PDF", () => {
    assert.equal(Informe.nombre("MES", "2026-10"), "Octubre 2026");
    assert.equal(Informe.nombre("BIMESTRE", "2026-5"), "Bimestre septiembre-octubre 2026");
    assert.equal(Informe.nombre("SEMESTRE", "2026-1"), "1.º semestre 2026 (enero a junio)");
    assert.equal(Informe.nombre("CAMPEONATO", ""), "Sin campeonato");
});

test("opciones: solo con partidos finalizados, del período más reciente al más viejo", () => {
    assert.deepEqual(Informe.opciones("MES", partidos).map(o => [o.valor, o.n]),
        [["2026-10", 1], ["2026-09", 1], ["2026-07", 1], ["2026-04", 1], ["2026-03", 1]]);
    assert.deepEqual(Informe.opciones("SEMESTRE", partidos).map(o => [o.valor, o.n]), [["2026-2", 3], ["2026-1", 2]]);
    // Campeonatos: por su último partido.
    assert.deepEqual(Informe.opciones("CAMPEONATO", partidos).map(o => o.nombre), ["Clausura", "Sin campeonato", "Apertura"]);
});

test("métricas sin intentos: la efectividad queda vacía, no en cero", () => {
    const m = Informe.metricas([{tipo: "FALTA", resultado: "COMETIDA"}]);
    assert.equal(m.efectividadTiros, null);
    assert.equal(m.efectividadPenales, null);
    assert.equal(m.faltasCometidas, 1);
});
