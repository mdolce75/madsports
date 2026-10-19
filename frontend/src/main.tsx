import {StrictMode} from "react";
import {createRoot} from "react-dom/client";
import {App} from "./app/App";

// La demo se baja aparte (import dinámico): la app del DT no carga los datos ni la API de ejemplo.
const demo = location.pathname === "/dt-demo.html" || new URLSearchParams(location.search).has("demo");
if (demo) (await import("./demo/instalar")).instalarDemo();

createRoot(document.getElementById("raiz")!).render(
    <StrictMode>
        <App demo={demo}/>
    </StrictMode>,
);
