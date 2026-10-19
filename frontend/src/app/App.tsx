// Métricas DT en React: pide la sesión (solo para DT), arma la barra con el menú y muestra la vista de la ruta. La
// demo es la misma app con la API simulada en el navegador (ver main.tsx).
import {useEffect, useState, type ReactNode} from "react";
import {pedirSesion, sesionVencida, type Sesion} from "../api/cliente";
import {Panel} from "../vistas/Panel";
import {Pendiente} from "../vistas/Pendiente";
import {Barra} from "./Barra";
import {navegar} from "./navegar";
import {SesionContexto} from "./sesion";
import {useRuta, type Ruta} from "./rutas";

const PERFILES_DT = ["DTFPP", "DTAMP"];

function Vista({ruta, demo}: {ruta: Ruta; demo: boolean}): ReactNode {
    switch (ruta.vista) {
        case "dashboard":
            return <Panel/>;
        default:
            return <Pendiente ruta={ruta} demo={demo}/>;
    }
}

export function App({demo = false}: {demo?: boolean}) {
    const [sesion, setSesion] = useState<Sesion | null>(null);
    const [error, setError] = useState<string | null>(null);
    const ruta = useRuta();

    useEffect(() => {
        pedirSesion().then(s => {
            if (!s.autenticado) { sesionVencida(false); return; }
            // Con sesión pero sin perfil de DT: a la pantalla de su perfil.
            if (!s.perfiles.some(p => PERFILES_DT.includes(p))) { navegar.reemplazar(s.inicio); return; }
            setSesion(s);
        }).catch(() => setError("No se pudo abrir Métricas DT: revisá la conexión y volvé a intentar."));
    }, []);

    useEffect(() => {
        document.body.classList.toggle("demo", demo);
        document.body.classList.toggle("dt-vivo", ruta.vista === "partido");
        document.title = demo ? "Demo de Métricas DT · RinkMetrics" : "Métricas DT · RinkMetrics";
    }, [demo, ruta]);

    return (
        <div className="wrap">
            {demo && (
                <div className="demo-aviso" role="note"><b>Demo</b> Métricas DT completo, con datos de ejemplo: recorré el
                    panel, tus partidos (abrí el que está en juego para cargar acciones), las estadísticas, los informes y tu
                    equipo. Nada se guarda. <a href="/registro.html?seccion=dt">Crear mi cuenta de DT</a></div>
            )}
            {error && <p className="dt-vacio" role="alert">{error}</p>}
            {sesion && (
                <SesionContexto.Provider value={{sesion, demo}}>
                    <Barra sesion={sesion} ruta={ruta}/>
                    <main><Vista ruta={ruta} demo={demo}/></main>
                </SesionContexto.Provider>
            )}
            <footer>© 2026 RinkMetrics. Todos los derechos reservados.</footer>
        </div>
    );
}
