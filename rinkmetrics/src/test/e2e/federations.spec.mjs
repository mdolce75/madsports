// Textos que nombran las federaciones: la FPP y la AMP, siempre las dos.
import {expect, test} from "@playwright/test";

// Página, cómo nombra a la FPP y cómo a la AMP.
const PAGES = [
    ["/index.html", "Federación Porteña de Patín", "Asociación Mendocina de Patín"],
    ["/rinkreports/index.html", "Federación Porteña de Patín (FPP)", "Asociación Mendocina de Patín (AMP)"],
    ["/rinkreports/why.html", "la FPP", "la AMP"],
];

for (const [path, fpp, amp] of PAGES) {
    test(`${path}: nombra a las dos federaciones`, async ({page}) => {
        await page.route("**/api/**", route => route.fulfill({json: {authenticated: false}}));
        await page.goto(path);
        await expect(page.locator("body")).toContainText(fpp);
        await expect(page.locator("body")).toContainText(amp);
    });
}
