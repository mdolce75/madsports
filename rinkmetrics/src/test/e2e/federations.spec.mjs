// Textos que nombran las federaciones: la AMP aparece recién cuando está habilitada (el mismo interruptor que el registro).
import {expect, test} from "@playwright/test";

// Página, cómo nombra a la FPP y cómo a la AMP.
const PAGES = [
    ["/index.html", "Federación Porteña de Patín", "Asociación Mendocina de Patín"],
    ["/rinkreports/index.html", "Federación Porteña de Patín (FPP)", "Asociación Mendocina de Patín (AMP)"],
    ["/rinkreports/why.html", "la FPP", "la AMP"],
];

async function options(page, amp) {
    await page.route("**/api/**", route => route.fulfill({json: new URL(route.request().url()).pathname === "/api/session"
        ? {authenticated: false} : {club: true, amp, playerAmp: amp}}));
}

for (const [path, fpp, amp] of PAGES) {
    test(`${path}: sin la AMP habilitada nombra solo a la FPP`, async ({page}) => {
        await options(page, false);
        await page.goto(path);
        const text = page.locator("p:has(span[data-amp])");
        await expect(text).toContainText(fpp);
        await expect(text.locator("span[data-amp]")).toBeHidden();
        await expect(text).not.toContainText(amp, {useInnerText: true});
    });

    test(`${path}: con la AMP habilitada nombra a las dos`, async ({page}) => {
        await options(page, true);
        await page.goto(path);
        const text = page.locator("p:has(span[data-amp])");
        await expect(text.locator("span[data-amp]")).toBeVisible();
        await expect(text).toContainText(fpp);
        await expect(text).toContainText(amp);
    });
}
