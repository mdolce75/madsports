// Períodos de los informes en la pantalla (js/coach/report.js): los mismos valores y nombres que ReportPeriod.java.
import {test} from "node:test";
import assert from "node:assert/strict";
import * as Report from "../../main/resources/static/js/coach/report.js";

const p = (id, date, tournament, status = "CLOSED") => ({id, date, tournament, status});
const matches = [p(1, "2026-03-10", "Apertura"), p(2, "2026-04-02", "Apertura"), p(3, "2026-09-30", "Clausura"),
    p(4, "2026-10-12", "Clausura", "FINISHED"), p(5, "2026-10-20", "Clausura", "PENDING"), p(6, "2026-07-01", null)];

test("cada partido cae en su mes, bimestre, semestre y campeonato", () => {
    const x = matches[3];
    assert.equal(Report.value("MONTH", x), "2026-10");
    assert.equal(Report.value("TWO_MONTHS", x), "2026-5");
    assert.equal(Report.value("SEMESTER", x), "2026-2");
    assert.equal(Report.value("CHAMPIONSHIP", x), "Clausura");
    assert.equal(Report.value("CHAMPIONSHIP", matches[5]), "");
});

test("nombres de los períodos, como en el PDF", () => {
    assert.equal(Report.name("MONTH", "2026-10"), "Octubre 2026");
    assert.equal(Report.name("TWO_MONTHS", "2026-5"), "Bimestre septiembre-octubre 2026");
    assert.equal(Report.name("SEMESTER", "2026-1"), "1.º semestre 2026 (enero a junio)");
    assert.equal(Report.name("CHAMPIONSHIP", ""), "Sin campeonato");
});

test("opciones: solo con partidos finalizados, del período más reciente al más viejo", () => {
    assert.deepEqual(Report.options("MONTH", matches).map(o => [o.value, o.n]),
        [["2026-10", 1], ["2026-09", 1], ["2026-07", 1], ["2026-04", 1], ["2026-03", 1]]);
    assert.deepEqual(Report.options("SEMESTER", matches).map(o => [o.value, o.n]), [["2026-2", 3], ["2026-1", 2]]);
    // Campeonatos: por su último partido.
    assert.deepEqual(Report.options("CHAMPIONSHIP", matches).map(o => o.name), ["Clausura", "Sin campeonato", "Apertura"]);
});

test("métricas sin intentos: la efectividad queda vacía, no en cero", () => {
    const m = Report.metrics([{kind: "FOUL", outcome: "COMMITTED"}]);
    assert.equal(m.shotEffectiveness, null);
    assert.equal(m.penaltyEffectiveness, null);
    assert.equal(m.foulsCommitted, 1);
});
