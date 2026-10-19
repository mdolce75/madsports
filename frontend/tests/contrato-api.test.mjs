// La API simulada de la demo (js/dt/demo-api.js) contra el contrato de la API de Métricas DT: los mismos escenarios
// que corre el servidor (ContratoApiDtTest), con las mismas respuestas.
// @vitest-environment node
import {test} from "vitest";
import assert from "node:assert/strict";
import {crearApi} from "../../src/main/resources/static/js/dt/demo-api.js";
import contrato from "../../src/test/resources/dt/contrato-api.json";

const {inicio} = contrato;

/** Reemplaza {nombre} por lo guardado: un valor entero ("{equipo}") conserva su tipo; dentro de un texto, se concatena. */
function sustituir(v, vars) {
    if (typeof v === "string") {
        const entero = v.match(/^\{(\w+)\}$/);
        if (entero && entero[1] in vars) return vars[entero[1]];
        return v.replace(/\{(\w+)\}/g, (m, k) => (k in vars ? String(vars[k]) : m));
    }
    if (Array.isArray(v)) return v.map(x => sustituir(x, vars));
    if (v && typeof v === "object") return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, sustituir(x, vars)]));
    return v;
}

/** esperado ⊆ real: los objetos, por las claves esperadas; las listas, completas y en orden. */
function contiene(esperado, real, ruta) {
    if (Array.isArray(esperado)) {
        assert.ok(Array.isArray(real), `${ruta}: se esperaba una lista y vino ${JSON.stringify(real)}`);
        assert.equal(real.length, esperado.length, `${ruta}: largo de la lista (${JSON.stringify(real)})`);
        esperado.forEach((e, i) => contiene(e, real[i], `${ruta}[${i}]`));
    } else if (esperado && typeof esperado === "object") {
        assert.ok(real && typeof real === "object" && !Array.isArray(real), `${ruta}: se esperaba un objeto y vino ${JSON.stringify(real)}`);
        for (const [k, e] of Object.entries(esperado)) {
            assert.ok(k in real, `${ruta}.${k}: no vino (${JSON.stringify(real)})`);
            contiene(e, real[k], `${ruta}.${k}`);
        }
    } else {
        assert.equal(real, esperado, ruta);
    }
}

for (const escenario of contrato.escenarios) {
    test(escenario.nombre, async () => {
        let ahora = Date.parse(inicio.instante);
        const api = crearApi({
            sesion: {usuario: inicio.usuario, club: {nombre: inicio.club},
                suscripcion: {codigo: "DT_MENSUAL", nombre: "DT mensual", equiposMax: 3, partidosPorMes: 25}},
            ahora: () => ahora,
        });
        const vars = {};
        for (const [i, paso] of escenario.pasos.entries()) {
            if (paso.avanzar) { ahora += paso.avanzar * 1000; continue; }
            const [method, ruta] = sustituir(paso.pedido, vars).split(" ");
            const donde = `paso ${i + 1} (${method} ${ruta})`;
            const res = api(method, ruta, paso.cuerpo === undefined ? null : sustituir(paso.cuerpo, vars));
            const texto = await res.text();
            const cuerpo = texto ? JSON.parse(texto) : null;
            assert.equal(res.status, paso.estado, `${donde}: status (${texto})`);
            if (paso.detalle) assert.equal(cuerpo.detail, paso.detalle, `${donde}: detail`);
            if (paso.respuesta !== undefined) contiene(sustituir(paso.respuesta, vars), cuerpo, donde);
            for (const [nombre, campo] of Object.entries(paso.guardar || {})) vars[nombre] = cuerpo[campo];
        }
    });
}
