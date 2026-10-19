import {render, screen, waitFor, within} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {App} from "./App";
import {instalarDemo} from "../demo/instalar";
import {navegar} from "./navegar";

const fetchOriginal = window.fetch;

/** Sesión del servidor sin la demo (fetch simulado solo para /api/sesion). */
function conSesion(sesion: object) {
    window.fetch = vi.fn(async () => new Response(JSON.stringify(sesion))) as typeof fetch;
}

beforeEach(() => {
    history.replaceState(null, "", "/dt-app/");
    vi.spyOn(navegar, "reemplazar").mockImplementation(() => {});
});

afterEach(() => {
    window.fetch = fetchOriginal;
});

describe("Métricas DT en React", () => {
    test("la demo arranca en el Control panel con sus datos y el aviso", async () => {
        instalarDemo(0);
        render(<App demo/>);

        expect(await screen.findByRole("heading", {name: "Dashboard general"})).toBeInTheDocument();
        await waitFor(() => expect(screen.getByTestId("d-partidos")).toHaveTextContent("9"));
        expect(screen.getByText("Este mes (plan: 25 por mes)")).toBeInTheDocument();
        expect(screen.getByRole("note")).toHaveTextContent("Nada se guarda");
        const proximos = screen.getByRole("list", {name: "Próximos partidos"});
        expect(within(proximos).getAllByRole("listitem")).toHaveLength(2);
        expect(screen.getByRole("link", {name: "Control panel"})).toHaveAttribute("aria-current", "page");
        expect(document.title).toBe("Demo de Métricas DT · RinkMetrics");
    });

    test("el menú cambia de vista por el # y el submenú de Estadísticas se abre y se cierra", async () => {
        const user = userEvent.setup();
        instalarDemo(0);
        render(<App demo/>);
        await screen.findByRole("heading", {name: "Dashboard general"});

        await user.click(screen.getByRole("link", {name: "Partidos"}));
        expect(await screen.findByRole("heading", {name: "Partidos"})).toBeInTheDocument();
        // Las vistas que todavía no pasaron a React llevan a la pantalla actual, a la misma vista.
        expect(screen.getByRole("link", {name: "Abrir en la pantalla actual"})).toHaveAttribute("href", "/dt-demo.html#partidos");

        const boton = screen.getByRole("button", {name: "Estadísticas"});
        await user.click(boton);
        expect(boton).toHaveAttribute("aria-expanded", "true");
        await user.click(screen.getByRole("link", {name: "Jugadores"}));
        expect(await screen.findByRole("heading", {name: "Estadísticas de los jugadores"})).toBeInTheDocument();
        expect(boton).toHaveAttribute("aria-expanded", "false");
    });

    test("en la carga de un partido no se muestra el menú", async () => {
        instalarDemo(0);
        history.replaceState(null, "", "/dt-app/?demo#partido=1");
        render(<App demo/>);
        expect(await screen.findByRole("heading", {name: "Partido en vivo"})).toBeInTheDocument();
        expect(screen.queryByRole("navigation", {name: "Métricas DT"})).not.toBeInTheDocument();
    });

    test("sin sesión va al ingreso y vuelve a esta pantalla", async () => {
        conSesion({autenticado: false});
        history.replaceState(null, "", "/dt-app/#informes");
        render(<App/>);
        await waitFor(() => expect(navegar.reemplazar).toHaveBeenCalledWith("/login.html?volver=%2Fdt-app%2F%23informes"));
    });

    test("un usuario sin perfil de DT va a la pantalla de su perfil", async () => {
        conSesion({autenticado: true, perfiles: ["CLUBFPP"], inicio: "/club.html"});
        render(<App/>);
        await waitFor(() => expect(navegar.reemplazar).toHaveBeenCalledWith("/club.html"));
        expect(screen.queryByRole("navigation")).not.toBeInTheDocument();
    });
});
