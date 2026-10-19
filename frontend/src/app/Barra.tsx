// Barra superior de Métricas DT: la marca (lleva a la portada), el menú del DT y el usuario con "Salir".
import {useEffect, useRef, useState} from "react";
import {api, type Sesion} from "../api/cliente";
import {navegar} from "./navegar";
import type {Ruta} from "./rutas";

/** Copias que Métricas DT guarda para abrir sin señal (dt-sw.js): se borran al salir. */
const CACHE_SIN_SENAL = "rinkmetrics-dt-v1";

function Marca() {
    return (
        <header className="brand">
            <a className="brand-link" href="/" aria-label="RinkMetrics: ir al inicio">
                <svg viewBox="0 0 64 64" aria-hidden="true">
                    <rect width="64" height="64" rx="14" fill="#22b8f0"/>
                    <rect x="8" y="15" width="48" height="34" rx="14" fill="none" stroke="#fff" strokeWidth="4"/>
                    <rect x="20" y="33" width="6" height="9" rx="1.5" fill="#fff"/>
                    <rect x="29" y="27" width="6" height="15" rx="1.5" fill="#fff"/>
                    <rect x="38" y="21" width="6" height="21" rx="1.5" fill="#fff"/>
                </svg>
                <div><b>RinkMetrics</b><small>Hockey sobre patines</small></div>
            </a>
        </header>
    );
}

/** Menú: Control panel, Partidos, Estadísticas (Equipo y Jugadores), Informes y Equipo. */
function Menu({ruta}: {ruta: Ruta}) {
    const [abierto, setAbierto] = useState(false);
    const sub = useRef<HTMLDivElement>(null);
    // El submenú se cierra al elegir, al tocar afuera o con Escape.
    useEffect(() => {
        if (!abierto) return;
        const afuera = (ev: MouseEvent) => { if (!sub.current?.contains(ev.target as Node)) setAbierto(false); };
        const escape = (ev: KeyboardEvent) => { if (ev.key === "Escape") setAbierto(false); };
        document.addEventListener("click", afuera);
        document.addEventListener("keydown", escape);
        return () => {
            document.removeEventListener("click", afuera);
            document.removeEventListener("keydown", escape);
        };
    }, [abierto]);
    useEffect(() => setAbierto(false), [ruta]);

    const actual = (v: string) => (ruta.vista === v ? {"aria-current": "page" as const} : {});
    const enEstadisticas = ruta.vista.startsWith("estadisticas-");
    return (
        <nav className="dt-menu" aria-label="Métricas DT">
            <a href="#dashboard" {...actual("dashboard")}>Control panel</a>
            <a href="#partidos" {...actual("partidos")}>Partidos</a>
            <div className={"dt-sub" + (enEstadisticas ? " activo" : "")} ref={sub}>
                <button type="button" className="dt-sub-btn" aria-expanded={abierto} aria-controls="dt-sub-est-menu"
                        onClick={() => setAbierto(!abierto)}>Estadísticas</button>
                <div className="dt-sub-menu" id="dt-sub-est-menu" hidden={!abierto}>
                    <a href="#estadisticas-equipo" {...actual("estadisticas-equipo")}>Equipo</a>
                    <a href="#estadisticas-jugadores" {...actual("estadisticas-jugadores")}>Jugadores</a>
                </div>
            </div>
            <a href="#informes" {...actual("informes")}>Informes</a>
            <a href="#equipo" {...actual("equipo")}>Equipo</a>
        </nav>
    );
}

function Usuario({sesion}: {sesion: Sesion}) {
    async function salir() {
        await api("/api/logout", {method: "POST"});
        // Las copias para abrir sin señal no quedan para el próximo usuario del teléfono.
        if (window.caches) await caches.delete(CACHE_SIN_SENAL).catch(() => false);
        navegar.reemplazar("/");
    }
    return (
        <div className="userbar">
            <span><b>{sesion.nombre ? `${sesion.nombre} ${sesion.apellido ?? ""}`.trim() : sesion.usuario}</b>
                {" · " + sesion.club.nombre}</span>
            <button type="button" onClick={salir}>Salir</button>
        </div>
    );
}

/** La barra de arriba; en la carga de un partido no lleva el menú (la pantalla es para cargar). */
export function Barra({sesion, ruta}: {sesion: Sesion; ruta: Ruta}) {
    return (
        <div className="topbar">
            <Marca/>
            {ruta.vista !== "partido" && <Menu ruta={ruta}/>}
            <Usuario sesion={sesion}/>
        </div>
    );
}
