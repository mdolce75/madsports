import {leerRuta} from "./rutas";

describe("rutas de Métricas DT (las mismas que dt.html)", () => {
    test("vistas del menú; sin nada o desconocida, el Control panel", () => {
        expect(leerRuta("")).toEqual({vista: "dashboard"});
        expect(leerRuta("#partidos")).toEqual({vista: "partidos"});
        expect(leerRuta("#estadisticas-equipo")).toEqual({vista: "estadisticas-equipo"});
        expect(leerRuta("#cualquiera")).toEqual({vista: "dashboard"});
    });

    test("el partido en vivo, el informe de un partido y el enlace viejo a las fichas", () => {
        expect(leerRuta("#partido=12")).toEqual({vista: "partido", partido: 12});
        expect(leerRuta("#informe=7")).toEqual({vista: "informes", informe: 7});
        expect(leerRuta("#jugadores")).toEqual({vista: "estadisticas-jugadores"});
    });
});
