// Vistas que todavía no pasaron a React: llevan a la misma vista en la pantalla actual (dt.html o la demo).
import type {Ruta} from "../app/rutas";

const TITULOS: Record<string, string> = {
    "partidos": "Partidos", "estadisticas-equipo": "Estadísticas del equipo",
    "estadisticas-jugadores": "Estadísticas de los jugadores", "informes": "Informes", "equipo": "Equipo",
    "partido": "Partido en vivo",
};

export function Pendiente({ruta, demo}: {ruta: Ruta; demo: boolean}) {
    const destino = (demo ? "/dt-demo.html" : "/dt.html") + location.hash;
    return (
        <section className="dt-vista">
            <h2 className="section-title">{TITULOS[ruta.vista]}</h2>
            <p className="hint">Esta sección está pasando a la nueva versión de Métricas DT. Mientras tanto, usala en la
                pantalla actual.</p>
            <a className="btn primary" href={destino}>Abrir en la pantalla actual</a>
        </section>
    );
}
