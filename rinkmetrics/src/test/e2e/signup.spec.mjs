// Registro del DT (/rinkmetrics/signup.html) con la API simulada: los clubes de una federación nunca se mezclan con los
// de la otra. Con la AMP habilitada se elige la federación y el combo trae solo sus clubes; sin ella, solo los de la FPP.
// Y el de RinkReports, que no pide federación ni club.
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

// RinkReports: una sola cuenta, sin perfil, federación ni club (se eligen en cada informe). Solo usuario, clave, email
// y el plan Free.
test("Registro de RinkReports: usuario, clave, email y el plan Free, sin federación ni club", async ({page}) => {
    const sent = [];
    await page.route("**/api/**", route => {
        const url = new URL(route.request().url());
        switch (url.pathname) {
            case "/api/session": return route.fulfill({json: {authenticated: false}});
            case "/api/plans":
                expect(url.searchParams.get("profile")).toBe("REPORTS");
                return route.fulfill({json: [{code: "FREE", name: "Free", price: 0, reportsPerMonth: 0,
                    validUntil: "2026-12-31", available: true}]});
            case "/api/signup":
                sent.push(route.request().postDataJSON());
                return route.fulfill({status: 201, json: {username: "lucia", email: "lucia@example.com"}});
            default: return route.fulfill({json: {}});
        }
    });
    await page.goto("/rinkreports/signup.html");
    await expect(page.locator("input[name=federacion]")).toHaveCount(0);
    await expect(page.locator("#club")).toHaveCount(0);
    await expect(page.locator("#plans .plan")).toHaveCount(1);
    await expect(page.locator("#plans .plan")).toContainText("Informes sin límite");
    await expect(page.locator("input[name=plan][value=FREE]")).toBeChecked();

    await page.locator("#usuario").fill("lucia");
    await page.locator("#clave").fill("clave-segura");
    await page.locator("#email").fill("lucia@example.com");
    await page.locator("#go").click();
    await expect(page.locator(".notice.ok")).toContainText("Revisá tu correo");
    expect(sent).toEqual([{profile: "REPORTS", username: "lucia", password: "clave-segura",
        email: "lucia@example.com", subscription: "FREE"}]);
});
