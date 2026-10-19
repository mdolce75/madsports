// Fechas como las muestra Métricas DT (las mismas que js/dt/comun.js).

/** Fecha de hoy en el teléfono (AAAA-MM-DD, hora local). */
export function hoy(): string {
    const d = new Date();
    return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
}

/** "2026-10-18" → "18/10". */
export const diaMes = (f: string): string => (f ? f.slice(8, 10) + "/" + f.slice(5, 7) : "");
