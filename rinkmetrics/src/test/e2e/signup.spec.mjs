// Registro del DT (/rinkmetrics/signup.html) y de RinkReports con la API simulada: los clubes de una federación nunca se mezclan con los
// de la otra. Con la AMP habilitada se elige la federación y el combo trae solo sus clubes; sin ella, solo los de la FPP.
import {expect, test} from "@playwright/test";

const CATALOGS = {
    FPP: {available: true, year: 2026, years: [2026], clubs: [
        {id: 74, code: "CAVS", name: "VELEZ", categories: []},
        {id: 94, code: "AAAJ", name: "ARGENTINOS JUNIORS", categories: []}]},
    AMP: {available: true, year: 2026, years: [2026], clubs: [
        {id: 25, code: "SM", name: "SAN MARTIN", categories: []},
        {id: 15, code: "GC", name: "GODOY CRUZ", categories: []}]},
};

/** La API del registro, con la AMP (y sus jugadores) habilitada o no; devuelve qué catálogos se pidieron. */
async function api(page, amp, playerAmp = amp) {
    const asked = [];
    await page.route("**/api/**", route => {
        const url = new URL(route.request().url());
        const json = body => route.fulfill({json: body});
        switch (url.pathname) {
            case "/api/session": return json({authenticated: false});
            case "/api/signup/options": return json({club: true, amp, playerAmp});
            case "/api/plans": return json([]);
            case "/api/catalog": {
                const f = url.searchParams.get("federation");
                asked.push(f);
                return json(CATALOGS[f]);
            }
            case "/api/catalog/club": {
                const f = url.searchParams.get("federation");
                return json(CATALOGS[f].clubs.find(c => String(c.id) === url.searchParams.get("clubId")));
            }
            default: return json({});
        }
    });
    return asked;
}

const clubs = page => page.locator("#club option:not([value=''])");

test("DT con la AMP habilitada: elige la federación y ve solo los clubes de esa federación", async ({page}) => {
    const asked = await api(page, true);
    await page.goto("/rinkmetrics/signup.html");
    const amp = page.locator("input[name=federacion][value=AMP]");
    await expect(amp).toBeVisible();
    // Antes de elegirla, ningún club.
    await expect(page.locator("#club")).toBeDisabled();
    await expect(clubs(page)).toHaveCount(0);

    await amp.check();
    await expect(clubs(page)).toHaveText(["SAN MARTIN (SM)", "GODOY CRUZ (GC)"]);

    await page.locator("input[name=federacion][value=FPP]").check();
    await expect(clubs(page)).toHaveText(["VELEZ (CAVS)", "ARGENTINOS JUNIORS (AAAJ)"]);
    expect(asked).toEqual(["AMP", "FPP"]);
});

test("DT sin la AMP: elige entre la FPP y Otra, y con la FPP ve solo sus clubes", async ({page}) => {
    const asked = await api(page, false);
    await page.goto("/rinkmetrics/signup.html");
    await expect(page.locator("input[name=federacion][value=AMP]")).toHaveCount(0);
    await expect(page.locator("input[name=federacion]:checked")).toHaveCount(0);
    await page.locator("input[name=federacion][value=FPP]").check();
    await expect(clubs(page)).toHaveText(["VELEZ (CAVS)", "ARGENTINOS JUNIORS (AAAJ)"]);
    expect(asked).toEqual(["FPP"]);
});

test("DT de otra federación: escribe el nombre de su club, sin catálogo de SIDGAD", async ({page}) => {
    const asked = await api(page, true);
    let sent;
    await page.route("**/api/signup", route => {
        sent = route.request().postDataJSON();
        return route.fulfill({status: 201, json: {username: sent.username, email: sent.email}});
    });
    await page.route("**/api/plans*", route => route.fulfill({json: [{code: "COACH_FREE", name: "DT free", price: 0,
        maxTeams: 3, matchesPerMonth: 5, dollar: 1000, dollarDate: "2026-10-28"}]}));
    await page.goto("/rinkmetrics/signup.html");
    await page.locator("input[name=federacion][value=OTR]").check();
    await expect(page.locator("#club")).toBeHidden();
    await expect(page.locator("#club-name")).toBeVisible();
    await page.fill("#usuario", "profe.otra");
    await page.fill("#clave", "clave-segura");
    await page.fill("#email", "profe@example.com");
    await page.locator("#go").click();
    await expect(page.locator("#status")).toHaveText("Completá todos los campos obligatorios.");
    await page.fill("#club-name", "Club Atlético Tandil");
    await page.locator("#go").click();
    await expect(page.locator(".notice.ok")).toBeVisible();
    expect(sent).toMatchObject({profile: "COACHOTR", clubId: 0, clubName: "Club Atlético Tandil", subscription: "COACH_FREE"});
    expect(asked).toEqual([]);
});

// RinkReports (club y jugador): con la AMP no hay federación elegida de antemano, para que nadie de Mendoza quede en la
// FPP sin darse cuenta; los clubes son solo los de la elegida.
test("Club de RinkReports con la AMP: elige la federación (ninguna marcada) y ve solo sus clubes", async ({page}) => {
    const asked = await api(page, true);
    await page.goto("/rinkreports/signup.html?option=club");
    await expect(page.locator("input[name=federacion]:checked")).toHaveCount(0);
    await expect(page.locator("#after-fed")).toBeHidden();
    await expect(page.locator("#fed-hint")).toHaveText("Elegí tu federación para ver sus clubes y los planes.");

    await page.locator("input[name=federacion][value=AMP]").check();
    await expect(page.locator("#after-fed")).toBeVisible();
    await expect(clubs(page)).toHaveText(["SAN MARTIN (SM)", "GODOY CRUZ (GC)"]);
    await page.locator("input[name=federacion][value=FPP]").check();
    await expect(clubs(page)).toHaveText(["VELEZ (CAVS)", "ARGENTINOS JUNIORS (AAAJ)"]);
    expect(asked).toEqual(["AMP", "FPP"]);
});

test("Jugador de RinkReports con la AMP pero sin jugadores AMP: la FPP sin preguntar y la AMP 'Próximamente'", async ({page}) => {
    await api(page, true, false);
    await page.goto("/rinkreports/signup.html?option=player");
    await expect(page.locator("input[name=federacion][value=FPP]")).toBeChecked();
    await expect(page.locator("input[name=federacion][value=AMP]")).toBeDisabled();
    await expect(page.locator("label:has(input[value=AMP]) small")).toHaveText("Próximamente.");
    await expect(clubs(page)).toHaveText(["VELEZ (CAVS)", "ARGENTINOS JUNIORS (AAAJ)"]);
});

test("RinkReports sin la AMP: la FPP sin preguntar, como hoy", async ({page}) => {
    const asked = await api(page, false);
    await page.goto("/rinkreports/signup.html?option=club");
    await expect(page.locator("input[name=federacion][value=AMP]")).toHaveCount(0);
    await expect(clubs(page)).toHaveText(["VELEZ (CAVS)", "ARGENTINOS JUNIORS (AAAJ)"]);
    expect(asked).toEqual(["FPP"]);
});
