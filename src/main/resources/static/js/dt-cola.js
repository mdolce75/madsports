// Cola de la planilla DT: cada acción (y cada borrado) se guarda primero en el teléfono, en IndexedDB, y se manda al
// servidor en tandas cada 5 segundos o cuando vuelve la conexión. Si se corta la señal o se recarga la página, nada se
// pierde; el id de cada acción lo genera el teléfono, así reenviar una tanda no la duplica en el servidor.
const DtCola = (() => {
    const DB = "rinkmetrics-dt", STORE = "ops", CADA = 5000;
    let db = null;           // IndexedDB; null si el navegador no lo permite (se usa la memoria)
    let memoria = [];        // ops si no hay IndexedDB
    let sec = 0;
    let enviando = false;
    let avisar = () => {};

    function abrir() {
        return new Promise(resolve => {
            try {
                const req = indexedDB.open(DB, 1);
                req.onupgradeneeded = () => req.result.createObjectStore(STORE, {keyPath: "key", autoIncrement: true});
                req.onsuccess = () => { db = req.result; resolve(); };
                req.onerror = () => resolve();
            } catch (e) {
                resolve();
            }
        });
    }

    function tx(modo, fn) {
        return new Promise((resolve, reject) => {
            const t = db.transaction(STORE, modo);
            const r = fn(t.objectStore(STORE));
            t.oncomplete = () => resolve(r && r.result);
            t.onerror = () => reject(t.error);
        });
    }

    async function todas() {
        if (!db) return memoria.slice();
        return tx("readonly", s => s.getAll());
    }

    async function guardar(op) {
        if (!db) { memoria.push(Object.assign({key: ++sec}, op)); return; }
        await tx("readwrite", s => s.add(op));
    }

    async function quitar(keys) {
        if (!keys.length) return;
        if (!db) { memoria = memoria.filter(o => !keys.includes(o.key)); return; }
        await tx("readwrite", s => keys.forEach(k => s.delete(k)));
    }

    /** uuid de la acción (crypto.randomUUID solo existe con https; getRandomValues, siempre). */
    function uuid() {
        if (crypto.randomUUID) return crypto.randomUUID();
        const b = crypto.getRandomValues(new Uint8Array(16));
        b[6] = (b[6] & 0x0f) | 0x40;
        b[8] = (b[8] & 0x3f) | 0x80;
        const h = [...b].map(x => x.toString(16).padStart(2, "0")).join("");
        return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
    }

    /** Pendientes de un partido: altas (acciones sin enviar) y bajas (borrados sin enviar). */
    async function pendientes(partidoId) {
        return (await todas()).filter(o => o.partidoId === partidoId);
    }

    async function agregar(partidoId, accion) {
        await guardar({partidoId, tipo: "alta", accion});
        avisarCambio(null);
        enviar();
    }

    /** Borra una acción: si todavía no se mandó, alcanza con sacarla de la cola. */
    async function borrar(partidoId, accionId) {
        const alta = (await pendientes(partidoId)).find(o => o.tipo === "alta" && o.accion.id === accionId);
        if (alta) await quitar([alta.key]);
        else await guardar({partidoId, tipo: "baja", accionId});
        avisarCambio(null);
        enviar();
    }

    async function avisarCambio(partido, error) {
        avisar({partido, pendientes: (await todas()).length, error});
    }

    async function post(url, body) {
        return api(url, {method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify(body)});
    }

    async function detalle(res) {
        const d = await res.json().catch(() => ({}));
        return d.detail || res.statusText;
    }

    /**
     * Manda lo pendiente. Un error de red deja todo en la cola para el próximo intento. Una acción que el servidor
     * rechaza (400: no corresponde; 409: partido cerrado) se descarta y se avisa, para que no trabe a las demás.
     */
    async function enviar() {
        if (enviando || !navigator.onLine) return;
        enviando = true;
        try {
            const ops = await todas();
            for (const id of [...new Set(ops.map(o => o.partidoId))]) {
                const delPartido = ops.filter(o => o.partidoId === id);
                const altas = delPartido.filter(o => o.tipo === "alta");
                if (altas.length) {
                    const res = await post(`/api/dt/partidos/${id}/acciones`, altas.map(o => o.accion));
                    if (res.ok) {
                        await quitar(altas.map(o => o.key));
                        await avisarCambio(await res.json());
                    } else if (res.status === 400 && altas.length > 1) {
                        // Una de la tanda no va: de a una, para descartar solo esa.
                        for (const o of altas) {
                            const r1 = await post(`/api/dt/partidos/${id}/acciones`, [o.accion]);
                            if (r1.ok) { await quitar([o.key]); await avisarCambio(await r1.json()); }
                            else if (r1.status === 400 || r1.status === 409 || r1.status === 404) {
                                await quitar([o.key]);
                                await avisarCambio(null, "No se guardó una acción: " + await detalle(r1));
                            } else return;
                        }
                    } else if (res.status === 400 || res.status === 409 || res.status === 404) {
                        await quitar(altas.map(o => o.key));
                        await avisarCambio(null, "No se guardó: " + await detalle(res));
                    } else {
                        if (res.status === 401) await avisarCambio(null, "La sesión venció: volvé a ingresar para enviar lo cargado.");
                        return;
                    }
                }
                for (const o of delPartido.filter(o => o.tipo === "baja")) {
                    const res = await api(`/api/dt/partidos/${id}/acciones/${o.accionId}`, {method: "DELETE"});
                    if (res.ok) { await quitar([o.key]); await avisarCambio(await res.json()); }
                    else if (res.status === 404 || res.status === 409) {
                        await quitar([o.key]);
                        await avisarCambio(null, "No se borró: " + await detalle(res));
                    } else return;
                }
            }
        } catch (e) {
            // Sin señal: queda todo para el próximo intento.
        } finally {
            enviando = false;
        }
    }

    /** Arranca la cola. {@code alCambiar({partido, pendientes, error})}: el partido que devolvió el servidor, si hay. */
    async function iniciar(alCambiar) {
        avisar = alCambiar;
        await abrir();
        setInterval(enviar, CADA);
        window.addEventListener("online", enviar);
        await avisarCambio(null);
        enviar();
    }

    return {iniciar, agregar, borrar, pendientes, enviar, uuid};
})();
