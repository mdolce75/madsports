// Métricas DT en React (/dt-app/?demo), mientras convive con la pantalla actual: el Control panel, el menú y el paso a
// las vistas que todavía están en dt.html.
import {expect, test} from "@playwright/test";

test("el Control panel de la demo, con el menú de Métricas DT", async ({page}) => {
    const errores = [];
    page.on("pageerror", e => errores.push(e.message));
    await page.goto("/dt-app/?demo");

    await expect(page).toHaveURL(/#dashboard$/);
    await expect(page.getByRole("heading", {name: "Dashboard general"})).toBeVisible();
    await expect(page.getByTestId("d-partidos")).toHaveText("9");
    await expect(page.getByRole("list", {name: "Próximos partidos"}).getByRole("listitem")).toHaveCount(2);
    await expect(page.getByRole("note")).toContainText("Nada se guarda");
    await expect(page.locator(".dash-box svg.grafico")).toHaveCount(2);
    expect(errores).toEqual([]);
});

test("desde el panel se llega a las vistas que siguen en la pantalla actual", async ({page}) => {
    await page.goto("/dt-app/?demo");
    await page.getByRole("button", {name: "Estadísticas"}).click();
    await page.getByRole("link", {name: "Equipo"}).first().click();
    await expect(page.getByRole("heading", {name: "Estadísticas del equipo"})).toBeVisible();

    await page.getByRole("link", {name: "Abrir en la pantalla actual"}).click();
    await expect(page).toHaveURL(/\/dt-demo\.html#estadisticas-equipo$/);
    await expect(page.locator("#e-kpis .dt-kpi")).toHaveCount(14);
});

test("un próximo partido abre su carga", async ({page}) => {
    await page.goto("/dt-app/?demo");
    await page.getByRole("button", {name: /vs\. Club Social Sur/}).click();
    await expect(page).toHaveURL(/#partido=8$/);
    await expect(page.getByRole("navigation", {name: "Métricas DT"})).toHaveCount(0);
});
