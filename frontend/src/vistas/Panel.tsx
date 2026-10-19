// Control panel: totales, estado de los partidos, partidos jugados por mes y los próximos programados.
import {useEffect, useState} from "react";
import {barrasEstado, dashboard, lineaMeses} from "@dominio/fichas.js";
import {getJson} from "../api/cliente";
import {useSesion} from "../app/sesion";
import {diaMes, hoy} from "./formato";

interface PartidoLista {
    id: number;
    fecha: string;
    rival: string;
    local: boolean;
    equipo: string;
    estado: string;
}

type Datos = ReturnType<typeof dashboard>;

export function Panel() {
    const {sesion} = useSesion();
    const [datos, setDatos] = useState<Datos | null>(null);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        getJson<PartidoLista[]>("/api/dt/partidos")
            .then(r => setDatos(dashboard(r.data, hoy())))
            .catch((e: Error) => setError("No se pudo cargar el dashboard: " + e.message));
    }, []);

    const cupo = sesion.suscripcion?.partidosPorMes;
    return (
        <section className="dt-vista">
            <h2 className="section-title">Dashboard general</h2>
            <div className="dt-kpis dash-kpis">
                <div className="dt-kpi"><b data-testid="d-partidos">{datos ? datos.partidos : "–"}</b><span>Partidos</span></div>
                <div className="dt-kpi"><b data-testid="d-mes">{datos ? datos.esteMes : "–"}</b>
                    <span>{cupo ? `Este mes (plan: ${cupo} por mes)` : "Este mes"}</span></div>
            </div>
            {error && <p className="dt-vacio" role="alert">{error}</p>}
            <div className="dash-grid">
                {/* Los gráficos son SVG armados por fichas.js con números propios (sin datos del usuario). */}
                <div className="dash-box"><h3>Estado de los partidos</h3>
                    {datos && <div dangerouslySetInnerHTML={{__html: barrasEstado(datos.estados)}}/>}</div>
                <div className="dash-box"><h3>Partidos jugados por mes</h3>
                    {datos && <div dangerouslySetInnerHTML={{__html: lineaMeses(datos.meses)}}/>}</div>
                <div className="dash-box dash-proximos"><h3>Próximos partidos</h3>
                    <ul className="dt-lista" aria-label="Próximos partidos">
                        {datos && !datos.proximos.length && (
                            <li className="dt-vacio">No hay partidos programados. Creá uno con fecha futura en Partidos.</li>
                        )}
                        {datos?.proximos.map((p: PartidoLista) => (
                            <li key={p.id}>
                                <button type="button" onClick={() => { location.hash = "partido=" + p.id; }}>
                                    <span>{`${diaMes(p.fecha)} · vs. ${p.rival}`}</span>
                                    <b>{p.local ? "Local" : "Visitante"}</b>
                                    <small>{p.equipo}</small>
                                </button>
                            </li>
                        ))}
                    </ul>
                    <a className="btn primary" href="#partidos">Programar un partido</a></div>
            </div>
        </section>
    );
}
