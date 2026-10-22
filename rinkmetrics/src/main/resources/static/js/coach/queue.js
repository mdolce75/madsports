// Cola de RinkMetrics: cada acción (y cada borrado) se guarda primero en el teléfono, en IndexedDB, y se manda al
// servidor en tandas cada 5 segundos o cuando vuelve la conexión. Si se corta la señal o se recarga la página, nada se
// pierde; el id de cada acción lo genera el teléfono, así reenviar una tanda no la duplica en el servidor.
const DB = "rinkmetrics-dt", STORE = "ops", EVERY_MS = 5000;
let db = null;           // IndexedDB; null si el navegador no lo permite (se usa la memoria)
let memory = [];        // ops si no hay IndexedDB
let sec = 0;
let sending = false;
let again = false;          // llegó algo nuevo mientras se enviaba: al terminar, se vuelve a enviar
const inFlight = new Set();    // ids de las acciones que están viajando al servidor ahora
let notify = () => {};

function openItem() {
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

function tx(mode, fn) {
    return new Promise((resolve, reject) => {
        const t = db.transaction(STORE, mode);
        const r = fn(t.objectStore(STORE));
        t.oncomplete = () => resolve(r && r.result);
        t.onerror = () => reject(t.error);
    });
}

async function all() {
    if (!db) return memory.slice();
    return tx("readonly", s => s.getAll());
}

async function save(op) {
    if (!db) { memory.push(Object.assign({key: ++sec}, op)); return; }
    await tx("readwrite", s => s.add(op));
}

async function removeOps(keys) {
    if (!keys.length) return;
    if (!db) { memory = memory.filter(o => !keys.includes(o.key)); return; }
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
async function pending(matchId) {
    return (await all()).filter(o => o.matchId === matchId);
}

async function enqueue(matchId, action) {
    await save({matchId, kind: "alta", action});
    notifyChange(null);
    send();
}

/**
 * Borra una acción: si todavía no se mandó, alcanza con sacarla de la cola. Si se está mandando justo ahora, puede
 * llegar al servidor igual: se encola el borrado, que sale apenas termine ese envío.
 */
async function deleteItem(matchId, actionId) {
    const addPlayer = (await pending(matchId)).find(o => o.kind === "alta" && o.action.id === actionId);
    if (addPlayer && !inFlight.has(actionId)) await removeOps([addPlayer.key]);
    else await save({matchId, kind: "baja", actionId});
    notifyChange(null);
    send();
}

async function notifyChange(match, error) {
    notify({match, pending: (await all()).length, error});
}

async function post(url, body) {
    return api(url, {method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify(body)});
}

async function detail(res) {
    const d = await res.json().catch(() => ({}));
    return d.detail || res.statusText;
}

/**
 * Manda lo pendiente. Un error de red deja todo en la cola para el próximo intento. Una acción que el servidor
 * rechaza (400: no corresponde; 409: partido cerrado) se descarta y se avisa, para que no trabe a las demás.
 */
async function send() {
    if (!navigator.onLine) return;
    if (sending) { again = true; return; }
    sending = true;
    try {
        const ops = await all();
        for (const id of [...new Set(ops.map(o => o.matchId))]) {
            const ofMatch = ops.filter(o => o.matchId === id);
            const creations = ofMatch.filter(o => o.kind === "alta");
            if (creations.length) {
                creations.forEach(o => inFlight.add(o.action.id));
                const res = await post(`/api/coach/matches/${id}/actions`, creations.map(o => o.action));
                if (res.ok) {
                    await removeOps(creations.map(o => o.key));
                    await notifyChange(await res.json());
                } else if (res.status === 400 && creations.length > 1) {
                    // Una de la tanda no va: de a una, para descartar solo esa.
                    for (const o of creations) {
                        const r1 = await post(`/api/coach/matches/${id}/actions`, [o.action]);
                        if (r1.ok) { await removeOps([o.key]); await notifyChange(await r1.json()); }
                        else if (r1.status === 400 || r1.status === 409 || r1.status === 404) {
                            await removeOps([o.key]);
                            await notifyChange(null, "No se guardó una acción: " + await detail(r1));
                        } else return;
                    }
                } else if (res.status === 400 || res.status === 409 || res.status === 404) {
                    await removeOps(creations.map(o => o.key));
                    await notifyChange(null, "No se guardó: " + await detail(res));
                } else {
                    if (res.status === 401) await notifyChange(null, "La sesión venció: volvé a ingresar para enviar lo cargado.");
                    return;
                }
            }
            for (const o of ofMatch.filter(o => o.kind === "baja")) {
                const res = await api(`/api/coach/matches/${id}/actions/${o.actionId}`, {method: "DELETE"});
                if (res.ok) { await removeOps([o.key]); await notifyChange(await res.json()); }
                else if (res.status === 404 || res.status === 409) {
                    await removeOps([o.key]);
                    await notifyChange(null, "No se borró: " + await detail(res));
                } else return;
            }
        }
    } catch (e) {
        // Sin señal: queda todo para el próximo intento.
    } finally {
        inFlight.clear();
        sending = false;
        if (again) { again = false; send(); }
    }
}

/** Arranca la cola. {@code alCambiar({partido, pendientes, error})}: el partido que devolvió el servidor, si hay. */
async function start(onChange) {
    notify = onChange;
    await openItem();
    setInterval(send, EVERY_MS);
    window.addEventListener("online", send);
    await notifyChange(null);
    send();
}

export {start, enqueue, deleteItem, pending, send, uuid};
