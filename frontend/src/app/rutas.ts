// Rutas de Métricas DT por el "#" de la dirección, las mismas de dt.html (los enlaces guardados siguen andando):
// #dashboard (o nada), #partidos, #estadisticas-equipo, #estadisticas-jugadores, #informes, #informe=ID, #equipo y
// #partido=ID (la carga en vivo). #jugadores es el enlace de antes a las fichas.
import {useEffect, useState} from "react";

export type Vista = "dashboard" | "partidos" | "estadisticas-equipo" | "estadisticas-jugadores" | "informes" | "equipo";

export type Ruta =
    | {vista: Vista; informe?: number}
    | {vista: "partido"; partido: number};

export const VISTAS: readonly Vista[] = ["dashboard", "partidos", "estadisticas-equipo", "estadisticas-jugadores",
    "informes", "equipo"];

export function leerRuta(hash: string): Ruta {
    const partido = hash.match(/^#partido=(\d+)$/);
    if (partido) return {vista: "partido", partido: Number(partido[1])};
    const informe = hash.match(/^#informe=(\d+)$/);
    if (informe) return {vista: "informes", informe: Number(informe[1])};
    if (hash === "#jugadores") return {vista: "estadisticas-jugadores"};
    const pedida = hash.slice(1);
    return {vista: (VISTAS as readonly string[]).includes(pedida) ? pedida as Vista : "dashboard"};
}

/** La ruta actual; cambia con el "#" (enlaces del menú, botones que asignan location.hash, atrás y adelante). */
export function useRuta(): Ruta {
    const [ruta, setRuta] = useState(() => leerRuta(location.hash));
    useEffect(() => {
        const cambio = () => setRuta(leerRuta(location.hash));
        window.addEventListener("hashchange", cambio);
        return () => window.removeEventListener("hashchange", cambio);
    }, []);
    return ruta;
}
