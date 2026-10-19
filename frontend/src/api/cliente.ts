// Pedidos a la API de RinkMetrics: con el token CSRF que Spring deja en la cookie XSRF-TOKEN, la sesión del navegador
// y, si la sesión venció (401), al ingreso con el aviso. Lo mismo que hace js/app.js en las páginas estáticas.
import {navegar} from "../app/navegar";

/** La sesión del usuario (GET /api/sesion). */
export interface Sesion {
    autenticado: boolean;
    usuario: string;
    nombre: string | null;
    apellido: string | null;
    perfiles: string[];
    club: {id: number; nombre: string; codigo: string};
    inicio: string;
    suscripcion: {codigo: string; nombre: string; equiposMax: number; partidosPorMes: number} | null;
}

/** Error de la API: el detail del ProblemDetail y el status. */
export class ErrorApi extends Error {
    constructor(message: string, readonly status: number) {
        super(message);
    }
}

export function csrfToken(): string {
    const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
    return m?.[1] ? decodeURIComponent(m[1]) : "";
}

/** Al ingreso (con el aviso "Tu sesión venció"); después de ingresar vuelve a esta misma pantalla. */
export function sesionVencida(aviso = true): void {
    const volver = location.pathname + location.search + location.hash;
    navegar.reemplazar(`/login.html?${aviso ? "vencida&" : ""}volver=${encodeURIComponent(volver)}`);
}

/** fetch con el header CSRF y la sesión del navegador. */
export async function api(url: string, options: RequestInit = {}): Promise<Response> {
    const headers = {"X-XSRF-TOKEN": csrfToken(), ...(options.headers as Record<string, string> | undefined)};
    const res = await fetch(url, {credentials: "same-origin", ...options, headers});
    if (res.status === 401) sesionVencida();
    return res;
}

async function cuerpo(res: Response): Promise<unknown> {
    if (res.status === 204) return {};
    return res.json().catch(() => ({}));
}

/**
 * GET a la API. at: cuándo se armó la respuesta (sin señal, el service worker devuelve una copia guardada antes, con
 * el header X-Guardado), para que el reloj del partido no se atrase.
 */
export async function getJson<T>(url: string): Promise<{data: T; at: number}> {
    const res = await api(url);
    const data = await cuerpo(res);
    if (!res.ok) throw new ErrorApi((data as {detail?: string}).detail || res.statusText, res.status);
    return {data: data as T, at: Number(res.headers.get("X-Guardado")) || Date.now()};
}

/** POST, PUT o DELETE con cuerpo JSON; devuelve la respuesta o lanza ErrorApi con el detalle del servidor. */
export async function enviar<T>(method: "POST" | "PUT" | "DELETE", url: string, body?: unknown): Promise<T> {
    const res = await api(url, {method, ...(body === undefined ? {} : {headers: {"Content-Type": "application/json"},
        body: JSON.stringify(body)})});
    const data = await cuerpo(res);
    if (!res.ok) throw new ErrorApi((data as {detail?: string}).detail || res.statusText, res.status);
    return data as T;
}

/** Datos del usuario logueado ({autenticado: false} si no hay sesión). También deja la cookie CSRF. */
export async function pedirSesion(): Promise<Sesion> {
    const res = await fetch("/api/sesion", {credentials: "same-origin"});
    return res.json() as Promise<Sesion>;
}

/** Sin señal: el pedido no llegó al servidor (fetch falla con TypeError). */
export const sinSenal = (err: unknown): boolean => err instanceof TypeError;
