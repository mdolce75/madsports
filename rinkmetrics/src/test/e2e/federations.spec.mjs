// Textos que nombran las federaciones: la FPP siempre; la AMP, solo si está habilitada (AMP_ENABLED).
import {expect, test} from "@playwright/test";

// Página, cómo nombra a la FPP y cómo a la AMP.
const PAGES = [
    ["/index.html", "Federación Porteña de Patín", "Asociación Mendocina de Patín"],
    ["/rinkreports/index.html", "Federación Porteña de Patín (FPP)", "Asociación Mendocina de Patín (AMP)"],
    ["/rinkreports/why.html", "la FPP", "la AMP"],
];

/** La API simulada: sin sesión, y la AMP habilitada o no. */
async function api(page, amp) {
    await page.route("**/api/**", route => route.fulfill({json: new URL(route.request().url()).pathname
        === "/api/signup/options" ? {club: true, amp} : {authenticated: false}}));
}

for (const [path, fpp, amp] of PAGES) {
    test(`${path}: con la AMP habilitada, nombra a las dos federaciones`, async ({page}) => {
        await api(page, true);
        await page.goto(path);
        await expect(page.locator("body")).toContainText(fpp, {useInnerText: true});
        await expect(page.locator("body")).toContainText(amp, {useInnerText: true});
    });

    test(`${path}: con la AMP apagada, solo la FPP`, async ({page}) => {
        await api(page, false);
        await page.goto(path);
        await expect(page.locator("body")).toContainText(fpp, {useInnerText: true});
        await expect(page.locator("body")).not.toContainText(amp, {useInnerText: true});
    });
}
