// La sesión del DT y si es la demo, para las vistas.
import {createContext, useContext} from "react";
import type {Sesion} from "../api/cliente";

export interface Contexto {
    sesion: Sesion;
    demo: boolean;
}

export const SesionContexto = createContext<Contexto | null>(null);

export function useSesion(): Contexto {
    const c = useContext(SesionContexto);
    if (!c) throw new Error("useSesion fuera de la app de Métricas DT");
    return c;
}
