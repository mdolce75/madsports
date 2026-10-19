// Cambios de página fuera de la app (al ingreso, a la pantalla de otro perfil, a la portada). Pasan por acá para
// poder probarlos: jsdom no deja reemplazar location.replace.
export const navegar = {
    reemplazar(url: string): void {
        location.replace(url);
    },
};
