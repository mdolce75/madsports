// Generador de informes de RinkReports (/rinkreports/reports.html) con la API simulada: federación → club → categoría →
// jugador. Cada combo carga el siguiente y limpia los de abajo; con el club elegido ya se puede generar (el club
// completo, de entrada).
import {expect, test} from "@playwright/test";

const CATALOGS = {
    // Las categorías y los equipos de cada club en el catálogo son los que se ofrecen para comparar.
    FPP: {available: true, year: 2026, years: [2026], categories: [
        {key: "INFANTIL", label: "Infantil", branches: ["FEMENINO"]},
        {key: "JUVENIL", label: "Juvenil", branches: ["MASCULINO"]},
        {key: "PRIMERA", label: "Primera", branches: ["MASCULINO", "FEMENINO"]}], clubs: [
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

/**
 * La API con sesión de RinkReports; devuelve los pedidos de informe que se mandaron. Con {@code teamReady}, el informe
 * de una categoría se genera y queda listo, con ese próximo rival.
 */
async function api(page, {teamReady} = {}) {
    const sent = [];
    await page.route("**/api/**", route => {
        const url = new URL(route.request().url());
        const json = (body, status = 200) => route.fulfill({status, json: body});
        if (teamReady && url.pathname === "/api/reports" && route.request().method() === "POST") {
            sent.push({url: url.pathname, body: route.request().postDataJSON()});
            return json({id: "r1", status: "PENDING", message: "En cola"}, 202);
        }
        if (teamReady && url.pathname === "/api/reports/r1") {
            return json({id: "r1", status: "READY", message: "Informe listo", pdfUrl: "/api/reports/r1/pdf",
                htmlUrl: "/api/reports/r1/html", xlsxUrl: "/api/reports/r1/xlsx", nextOpponent: teamReady});
        }
        if (url.pathname === "/api/reports/r1/html") {
            return route.fulfill({status: 200, contentType: "text/html", body: "<h1>Informe de prueba</h1>"});
        }
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

test("el generador no compara: «Comparar equipos» es su propia pantalla", async ({page}) => {
    await api(page);
    await page.goto("/rinkreports/reports.html");
    await expect(page.locator("#rival")).toHaveCount(0);
    await expect(page.locator("nav.secciones a", {hasText: "Comparar equipos"}))
        .toHaveAttribute("href", "/rinkreports/compare.html");
});

test("comparar: solo equipos de la categoría elegida, de los clubes que la tienen", async ({page}) => {
    const sent = await api(page);
    await page.goto("/rinkreports/compare.html");
    await expect(page.locator("nav.secciones a", {hasText: "Comparar equipos"})).toHaveAttribute("aria-current", "page");
    await page.locator("#federacion").selectOption("FPP");
    await expect(options(page, "categoria")).toHaveText(["Elegí la categoría", "Juvenil Masculino", "Primera Masculino",
        "Infantil Femenino", "Primera Femenino"]);
    await expect(page.locator("#go")).toBeDisabled();

    // Primera Femenino: solo Vélez la tiene, no hay con quién.
    await page.locator("#categoria").selectOption("PRIMERA|FEMENINO");
    await expect(page.locator("#equipo-a")).toBeDisabled();
    await expect(options(page, "equipo-a")).toHaveText(["No hay dos equipos en la categoría"]);
    // Infantil Femenino: los equipos A y B de Vélez.
    await page.locator("#categoria").selectOption("INFANTIL|FEMENINO");
    await expect(options(page, "equipo-a")).toHaveText(["Elegí el equipo", "VELEZ A", "VELEZ B"]);

    // Primera Masculino: el rival se elige después y no repite el equipo.
    await page.locator("#categoria").selectOption("PRIMERA|MASCULINO");
    await expect(options(page, "equipo-a")).toHaveText(["Elegí el equipo", "ARGENTINOS JUNIORS", "VELEZ"]);
    await expect(page.locator("#equipo-b")).toBeDisabled();
    await page.locator("#equipo-a").selectOption("74|PRIMERA");
    await expect(options(page, "equipo-b")).toHaveText(["Elegí el rival", "ARGENTINOS JUNIORS"]);
    await page.locator("#equipo-b").selectOption("94|PRIMERA");
    await expect(page.locator("#go")).toBeEnabled();

    await page.locator("#go").click();
    await expect(page.locator("#status")).toHaveText("Pedido recibido (prueba).");
    expect(sent.pop()).toEqual({url: "/api/compare/reports", body: {federation: "FPP", year: 2026,
        branch: "MASCULINO", clubA: 74, categoryA: "PRIMERA", clubB: 94, categoryB: "PRIMERA"}});
});

test("desde el informe de una categoría, al comparativo con el próximo rival ya elegido", async ({page}) => {
    const sent = await api(page, {teamReady: "ARGENTINOS JUNIORS"});
    await page.goto("/rinkreports/reports.html");
    await page.locator("#federacion").selectOption("FPP");
    await page.locator("#club").selectOption("74");
    await page.locator("#categoria").selectOption("PRIMERA|MASCULINO");
    await page.locator("#go").click();

    const cta = page.locator(".rr-compare-cta");
    await expect(cta.getByRole("link")).toHaveText(["Comparar con otro equipo",
        "Comparar con el próximo rival: ARGENTINOS JUNIORS"]);
    await cta.getByRole("link", {name: "Comparar con el próximo rival: ARGENTINOS JUNIORS"}).click();
    await expect(page).toHaveURL(/\/rinkreports\/compare\.html\?/);
    await expect(page.locator("#categoria")).toHaveValue("PRIMERA|MASCULINO");
    await expect(page.locator("#equipo-a")).toHaveValue("74|PRIMERA");
    await expect(page.locator("#equipo-b")).toHaveValue("94|PRIMERA");
    await page.locator("#go").click();
    await expect(page.locator("#status")).toHaveText("Pedido recibido (prueba).");
    expect(sent.pop()).toEqual({url: "/api/compare/reports", body: {federation: "FPP", year: 2026,
        branch: "MASCULINO", clubA: 74, categoryA: "PRIMERA", clubB: 94, categoryB: "PRIMERA"}});
});

test("sin el próximo rival en la categoría, solo «Comparar con otro equipo»", async ({page}) => {
    await api(page, {teamReady: "HURACAN"});
    await page.goto("/rinkreports/reports.html");
    await page.locator("#federacion").selectOption("FPP");
    await page.locator("#club").selectOption("74");
    await page.locator("#categoria").selectOption("PRIMERA|MASCULINO");
    await page.locator("#go").click();

    const cta = page.locator(".rr-compare-cta");
    await expect(cta.getByRole("link")).toHaveText(["Comparar con otro equipo"]);
    await cta.getByRole("link").click();
    await expect(page.locator("#equipo-a")).toHaveValue("74|PRIMERA");
    await expect(page.locator("#equipo-b")).toHaveValue("");
});

test("la portada de RinkReports presenta el comparativo", async ({page}) => {
    await page.route("**/api/session**", route => route.fulfill({json: {authenticated: false}}));
    // El servidor de prueba no resuelve carpetas: en la app, /rinkreports/ es esta portada.
    await page.goto("/rinkreports/index.html#comparar");
    await expect(page.locator("#comparar h3")).toHaveText("Comparar equipos, cara a cara");
    await expect(page.locator("#comparar a", {hasText: "Comparar equipos"}))
        .toHaveAttribute("href", "/rinkreports/compare.html");
    await expect(page.locator("nav.secciones a", {hasText: "Comparar equipos"}))
        .toHaveAttribute("href", "/rinkreports/#comparar");
});
