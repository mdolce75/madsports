// La demo de Métricas DT en React: la misma app, con los pedidos a /api/ contestados en el navegador por la API
// simulada (js/dt/demo-api.js, verificada contra el contrato del servidor) y los mismos datos de ejemplo que dt.html.
import {simularApi} from "@dominio/demo.js";

/** La demo arranca en el Control panel. */
export function instalarDemo(demora?: number): void {
    simularApi(demora);
    if (!location.hash) history.replaceState(null, "", location.pathname + location.search + "#dashboard");
}
