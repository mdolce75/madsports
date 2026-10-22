// La API simulada de la demo (js/coach/demo-api.js) contra el contrato de la API de RinkMetrics: los mismos escenarios
// que corre el servidor (CoachApiContractTest), con las mismas respuestas.
import {test} from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {createApi} from "../../main/resources/static/js/coach/demo-api.js";

const contract = JSON.parse(readFileSync(new URL("../../../../app/src/test/resources/coach/api-contract.json", import.meta.url), "utf8"));
const {setup} = contract;

/** Reemplaza {nombre} por lo guardado: un valor entero ("{equipo}") conserva su tipo; dentro de un texto, se concatena. */
function replace(v, vars) {
    if (typeof v === "string") {
        const whole = v.match(/^\{(\w+)\}$/);
        if (whole && whole[1] in vars) return vars[whole[1]];
        return v.replace(/\{(\w+)\}/g, (m, k) => (k in vars ? String(vars[k]) : m));
    }
    if (Array.isArray(v)) return v.map(x => replace(x, vars));
    if (v && typeof v === "object") return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, replace(x, vars)]));
    return v;
}

/** esperado ⊆ real: los objetos, por las claves esperadas; las listas, completas y en orden. */
function contains(expected, real, path) {
    if (Array.isArray(expected)) {
        assert.ok(Array.isArray(real), `${path}: se esperaba una lista y vino ${JSON.stringify(real)}`);
        assert.equal(real.length, expected.length, `${path}: largo de la lista (${JSON.stringify(real)})`);
        expected.forEach((e, i) => contains(e, real[i], `${path}[${i}]`));
    } else if (expected && typeof expected === "object") {
        assert.ok(real && typeof real === "object" && !Array.isArray(real), `${path}: se esperaba un objeto y vino ${JSON.stringify(real)}`);
        for (const [k, e] of Object.entries(expected)) {
            assert.ok(k in real, `${path}.${k}: no vino (${JSON.stringify(real)})`);
            contains(e, real[k], `${path}.${k}`);
        }
    } else {
        assert.equal(real, expected, path);
    }
}

for (const scenario of contract.scenarios) {
    test(scenario.name, async () => {
        let now = Date.parse(setup.instant);
        const api = createApi({
            session: {username: setup.username, club: {name: setup.club},
                subscription: {code: "COACH_MONTHLY", name: "DT mensual", maxTeams: 3, matchesPerMonth: 25}},
            now: () => now,
        });
        const vars = {};
        for (const [i, step] of scenario.steps.entries()) {
            if (step.advance) { now += step.advance * 1000; continue; }
            const [method, path] = replace(step.request, vars).split(" ");
            const where = `paso ${i + 1} (${method} ${path})`;
            const res = api(method, path, step.body === undefined ? null : replace(step.body, vars));
            const text = await res.text();
            const body = text ? JSON.parse(text) : null;
            assert.equal(res.status, step.status, `${where}: status (${text})`);
            if (step.detail) assert.equal(body.detail, step.detail, `${where}: detail`);
            if (step.response !== undefined) contains(replace(step.response, vars), body, where);
            for (const [name, field] of Object.entries(step.save || {})) vars[name] = body[field];
        }
    });
}
