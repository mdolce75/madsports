// Generador de informes de RinkReports (/rinkreports/reports.html) con la API simulada: federación → club → categoría →
// jugador. Cada combo carga el siguiente y limpia los de abajo; con la categoría elegida ya se puede generar.
import {expect, test} from "@playwright/test";

const CATALOGS = {
    FPP: {available: true, year: 2026, years: [2026], clubs: [
        {id: 74, code: "CAVS", name: "VELEZ", categories: []},
        {id: 94, code: "AAAJ", name: "ARGENTINOS JUNIORS", categories: []}]},
    AMP: {available: true, year: 2026, years: [2026], clubs: [
        {id: 15, code: "GC", name: "GODOY CRUZ", categories: []}]},
};
const CATEGORIES = {
    74: [{key: "PRIMERA", label: "Primera", branches: ["MASCULINO", "FEMENINO"]},
        {key: "INFANTIL:B", label: "Infantil B", branches: ["FEMENINO"]}],
    94: [{key: "JUVENIL", label: "Juvenil", branches: ["MASCULINO"]}],
};

/** La API con sesión de RinkReports; devuelve los pedidos de informe que se mandaron. */
async function api(page) {
    const sent = [];
    await page.route("**/api/**", route => {
        const url = new URL(route.request().url());
        const json = (body, status = 200) => route.fulfill({status, json: body});
        switch (url.pathname) {
            case "/api/session": return json({authenticated: true, username: "lucia", profiles: ["REPORTS"],
                club: {id: 0, name: "", code: ""}, home: "/rinkreports/reports.html", monthlyReports: null});
            case "/api/federations": return json([{code: "FPP", name: "Federación Porteña de Patín"},
                {code: "AMP", name: "Asociación Mendocina de Patín"}]);
            case "/api/catalog": return json(CATALOGS[url.searchParams.get("federation")]);
            case "/api/catalog/club": {
                const id = Number(url.searchParams.get("clubId"));
                return json({id, categories: CATEGORIES[id] || []});
            }
            case "/api/catalog/players": return json([{id: 2, name: "Zoe Diaz"}, {id: 1, name: "Ana Perez"}]);
            case "/api/history": return json([]);
            case "/api/reports":
            case "/api/player/reports":
                sent.push({url: url.pathname, body: route.request().postDataJSON()});
                return json({detail: "Pedido recibido (prueba)."}, 400);
            default: return json({});
        }
    });
    return sent;
}

const options = (page, id) => page.locator(`#${id} option`);

test("cascada: federación, club, categoría y jugador; se genera con la categoría elegida", async ({page}) => {
    const sent = await api(page);
    await page.goto("/rinkreports/reports.html");
    await expect(page.locator("#go")).toBeDisabled();
    await expect(page.locator("#club")).toBeDisabled();
    await expect(options(page, "federacion")).toHaveText(["Elegí la federación",
        "FPP · Federación Porteña de Patín", "AMP · Asociación Mendocina de Patín"]);

    // Los clubes de la federación elegida (ordenados), y solo esos.
    await page.locator("#federacion").selectOption("AMP");
    await expect(options(page, "club")).toHaveText(["Elegí el club", "GODOY CRUZ (GC)"]);
    await page.locator("#federacion").selectOption("FPP");
    await expect(options(page, "club")).toHaveText(["Elegí el club", "ARGENTINOS JUNIORS (AAAJ)", "VELEZ (CAVS)"]);
    await expect(page.locator("#go")).toBeDisabled();

    // Primero, el club completo; después cada categoría por rama.
    await page.locator("#club").selectOption("74");
    await expect(options(page, "categoria")).toHaveText(["Elegí la categoría", "Todas las categorías (informe del club)",
        "Primera Masculino", "Primera Femenino", "Infantil B Femenino"]);
    await expect(page.locator("#go")).toBeDisabled();

    // Con la categoría ya se puede generar; de entrada, «Todos los jugadores» (el informe del equipo).
    await page.locator("#categoria").selectOption("PRIMERA|MASCULINO");
    await expect(page.locator("#go")).toBeEnabled();
    await expect(options(page, "jugador")).toHaveText(["Todos los jugadores", "Ana Perez", "Zoe Diaz"]);
    await expect(page.locator("#jugador")).toHaveValue("");

    await page.locator("#go").click();
    await expect(page.locator("#status")).toHaveText("Pedido recibido (prueba).");
    expect(sent.pop()).toEqual({url: "/api/reports", body: {federation: "FPP", clubId: 74, category: "PRIMERA",
        branch: "MASCULINO", period: "SEASON", year: 2026}});

    // Con un jugador, su informe (sin «Últimos 5 partidos»).
    await page.locator("#jugador").selectOption("1");
    await expect(page.locator("#last5")).toBeDisabled();
    await page.locator("#go").click();
    await expect(page.locator("#status")).toHaveText("Pedido recibido (prueba).");
    expect(sent.pop()).toEqual({url: "/api/player/reports", body: {federation: "FPP", clubId: 74,
        category: "PRIMERA", branch: "MASCULINO", playerId: 1, year: 2026, period: "SEASON"}});

    // «Todas las categorías»: el club completo, sin jugadores.
    await page.locator("#categoria").selectOption("ALL");
    await expect(page.locator("#jugador")).toBeDisabled();
    await page.locator("#go").click();
    await expect(page.locator("#status")).toHaveText("Pedido recibido (prueba).");
    expect(sent.pop()).toEqual({url: "/api/reports", body: {federation: "FPP", clubId: 74, category: null,
        branch: null, period: "SEASON", year: 2026}});

    // Otro club limpia la categoría y el jugador, y vuelve a deshabilitar el botón.
    await page.locator("#club").selectOption("94");
    await expect(options(page, "categoria")).toHaveText(["Elegí la categoría", "Todas las categorías (informe del club)",
        "Juvenil Masculino"]);
    await expect(options(page, "jugador")).toHaveText(["Todos los jugadores"]);
    await expect(page.locator("#go")).toBeDisabled();
});
