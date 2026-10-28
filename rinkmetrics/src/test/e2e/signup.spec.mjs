// Registro del DT (/rinkmetrics/signup.html) con la API simulada: los clubes de una federación nunca se mezclan con los
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

/** La API del registro, con la AMP habilitada o no; devuelve qué catálogos se pidieron. */
async function api(page, amp) {
    const asked = [];
    await page.route("**/api/**", route => {
        const url = new URL(route.request().url());
        const json = body => route.fulfill({json: body});
        switch (url.pathname) {
            case "/api/session": return json({authenticated: false});
            case "/api/signup/options": return json({club: true, amp, playerAmp: amp});
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

test("DT sin la AMP: no elige federación y ve solo los clubes de la FPP", async ({page}) => {
    const asked = await api(page, false);
    await page.goto("/rinkmetrics/signup.html");
    await expect(clubs(page)).toHaveText(["VELEZ (CAVS)", "ARGENTINOS JUNIORS (AAAJ)"]);
    await expect(page.locator("input[name=federacion][value=AMP]")).toBeHidden();
    expect(asked).toEqual(["FPP"]);
});
