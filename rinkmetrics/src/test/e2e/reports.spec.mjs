// Generador de informes de RinkReports (/rinkreports/reports.html) con la API simulada: federación → club → categoría →
// jugador. Cada combo carga el siguiente y limpia los de abajo; con el club elegido ya se puede generar (el club
// completo, de entrada).
import {expect, test} from "@playwright/test";

const CATALOGS = {
    // Las categorías del catálogo son las que se ofrecen para comparar (equipos de la misma categoría y rama).
    FPP: {available: true, year: 2026, years: [2026], clubs: [
        {id: 74, code: "CAVS", name: "VELEZ", categories: [
            {key: "PRIMERA", label: "Primera", branches: ["MASCULINO", "FEMENINO"]},
            {key: "INFANTIL:A", label: "Infantil A", branches: ["FEMENINO"]},
            {key: "INFANTIL:B", label: "Infantil B", branches: ["FEMENINO"]}]},
        {id: 94, code: "AAAJ", name: "ARGENTINOS JUNIORS", categories: [
            {key: "PRIMERA", label: "Primera", branches: ["MASCULINO"]},
            {key: "JUVENIL", label: "Juvenil", branches: ["MASCULINO"]}]}]},
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
            case "/api/compare/reports":
                sent.push({url: url.pathname, body: route.request().postDataJSON()});
                return json({detail: "Pedido recibido (prueba)."}, 400);
            default: return json({});
        }
    });
    return sent;
}

const options = (page, id) => page.locator(`#${id} option`);

test("cascada: federación, club, categoría y jugador; con el club ya se genera", async ({page}) => {
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

    // Con el club ya se puede generar: de entrada, el club completo; después cada categoría por rama.
    await page.locator("#club").selectOption("74");
    await expect(options(page, "categoria")).toHaveText(["Todas las categorías (informe del club)",
        "Primera Masculino", "Primera Femenino", "Infantil B Femenino"]);
    await expect(page.locator("#categoria")).toHaveValue("ALL");
    await expect(page.locator("#jugador")).toBeDisabled();
    await expect(page.locator("#go")).toBeEnabled();

    // Con una categoría, su informe; de entrada, «Todos los jugadores» (el informe del equipo).
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

    // Otro club limpia la categoría y el jugador: vuelve al club completo.
    await page.locator("#club").selectOption("94");
    await expect(options(page, "categoria")).toHaveText(["Todas las categorías (informe del club)", "Juvenil Masculino"]);
    await expect(page.locator("#categoria")).toHaveValue("ALL");
    await expect(options(page, "jugador")).toHaveText(["Todos los jugadores"]);
    await expect(page.locator("#go")).toBeEnabled();
});

test("comparar con otro equipo de la misma categoría y rama", async ({page}) => {
    const sent = await api(page);
    await page.goto("/rinkreports/reports.html");
    await page.locator("#federacion").selectOption("FPP");
    await page.locator("#club").selectOption("74");
    // Sin categoría (club completo) no hay con quién comparar.
    await expect(page.locator("#rival")).toBeDisabled();

    // Primera Masculino: Argentinos tiene equipo; Primera Femenino, nadie más.
    await page.locator("#categoria").selectOption("PRIMERA|MASCULINO");
    await expect(options(page, "rival")).toHaveText(["Sin comparar", "ARGENTINOS JUNIORS"]);
    await expect(page.locator("#rival")).toBeEnabled();
    await page.locator("#categoria").selectOption("PRIMERA|FEMENINO");
    await expect(options(page, "rival")).toHaveText(["No hay otros equipos en la categoría"]);
    await expect(page.locator("#rival")).toBeDisabled();
    // Infantil B: el otro equipo del mismo club (Infantil A) también se puede comparar.
    await page.locator("#categoria").selectOption("INFANTIL:B|FEMENINO");
    await expect(options(page, "rival")).toHaveText(["Sin comparar", "VELEZ A"]);

    // Con un rival: sin período ni jugador, y el botón lo dice.
    await page.locator("#categoria").selectOption("PRIMERA|MASCULINO");
    await page.locator("#jugador").selectOption("1");
    await page.locator("#rival").selectOption("94|PRIMERA");
    await expect(page.locator("#jugador")).toHaveValue("");
    await expect(page.locator("#periodo")).toBeDisabled();
    await expect(page.locator("#compare-hint")).toBeVisible();
    await expect(page.locator("#go")).toHaveText("Generar comparativo");

    await page.locator("#go").click();
    await expect(page.locator("#status")).toHaveText("Pedido recibido (prueba).");
    expect(sent.pop()).toEqual({url: "/api/compare/reports", body: {federation: "FPP", year: 2026,
        branch: "MASCULINO", clubA: 74, categoryA: "PRIMERA", clubB: 94, categoryB: "PRIMERA"}});

    // Elegir un jugador saca el rival: vuelve el informe de jugador.
    await page.locator("#jugador").selectOption("2");
    await expect(page.locator("#rival")).toHaveValue("");
    await expect(page.locator("#periodo")).toBeEnabled();
    await expect(page.locator("#go")).toHaveText("Generar informe");
});
