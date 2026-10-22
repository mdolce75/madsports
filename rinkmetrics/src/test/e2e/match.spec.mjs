// Carga en vivo, en la demo: elegir jugador y acción, deshacer, cargar sin señal y terminar el partido.
import {expect, test} from "@playwright/test";

/** Abre el partido de ejemplo (en juego, 2 - 1) con el jugador #9 elegido. */
async function openMatch(page) {
    page.on("dialog", d => d.accept());
    await page.goto("/coach-demo.html#match=1");
    await expect(page.locator("#m-gf")).toHaveText("2");
    await expect(page.locator("#m-gc")).toHaveText("1");
    // La demo elige al #10 sola si nadie eligió a otro: tocar al #9 alcanza (si llega antes, ya no lo pisa).
    await page.getByRole("button", {name: /^9 Agustín Paz/}).click();
    await expect(page.locator("#sel")).toHaveText("Agustín Paz (#9)");
}

test("cargar un gol, verlo en el marcador y en las últimas acciones, y deshacerlo", async ({page}) => {
    await openMatch(page);
    await page.getByRole("button", {name: "Tiro al arco: Gol", exact: true}).click();

    await expect(page.locator("#m-gf")).toHaveText("3");
    await expect(page.locator("#log li").first()).toContainText("Tiro al arco: Gol");
    await expect(page.locator("#log li").first()).toContainText("Agustín Paz");
    await expect(page.locator("#toast")).toContainText("Tiro al arco: Gol · 9 Paz");

    await page.locator("#toast-undo").click();
    await expect(page.locator("#m-gf")).toHaveText("2");
    await expect(page.locator("#log li").first()).not.toContainText("Agustín Paz");
});

test("las acciones del arco van al arquero en cancha aunque haya otro jugador elegido", async ({page}) => {
    await openMatch(page);
    await expect(page.locator("#ar")).toHaveText("Martín Acosta (#1)");
    await page.getByRole("button", {name: "Gol recibido", exact: true}).click();
    await expect(page.locator("#m-gc")).toHaveText("2");
    await expect(page.locator("#log li").first()).toContainText("Martín Acosta");
});

test("sin señal, las acciones quedan en el teléfono y se envían cuando vuelve la conexión", async ({page, context}) => {
    await openMatch(page);
    await expect(page.locator("#m-sync")).toHaveText("Todo enviado");

    await context.setOffline(true);
    await expect(page.locator("#m-sync")).toContainText("Sin señal");
    await page.getByRole("button", {name: "Recuperación en ataque", exact: true}).click();
    await page.getByRole("button", {name: "Falta recibida", exact: true}).click();
    await expect(page.locator("#m-sync")).toHaveText("Sin señal · Sin enviar: 2");
    await expect(page.locator("#log li").first()).toContainText("sin enviar");
    // El reloj, en cambio, necesita conexión.
    await page.getByRole("button", {name: "Pausar"}).click();

    await context.setOffline(false);
    await expect(page.locator("#m-sync")).toHaveText("Todo enviado");
    await expect(page.locator("#log li").first()).not.toContainText("sin enviar");
    await expect(page.locator("#log li").first()).toContainText("Falta recibida");
});

test("al terminar el partido se abre su informe", async ({page}) => {
    await openMatch(page);
    await page.getByRole("button", {name: "Fin del 1T"}).click();
    await expect(page.locator("#m-tiempo")).toHaveText("ET");
    await page.getByRole("button", {name: "Iniciar 2T"}).click();
    await expect(page.locator("#m-tiempo")).toHaveText("2T");
    await page.getByRole("button", {name: "Fin del partido"}).click();

    await expect(page.locator("#p-resumen")).toBeVisible();
    await expect(page.locator("#informe-vivo h3")).toHaveText("CLUB DEMO 2 - 1 Deportivo Oeste");
    await expect(page.locator("#informe-vivo")).toContainText("Estadísticas del equipo");
    await expect(page.locator("#informe-vivo .inf-eventos li")).toHaveCount(11);

    // Informe de un jugador: solo sus eventos.
    await page.getByLabel("Jugador del informe").selectOption({label: "1 · Martín Acosta"});
    await expect(page.locator("#informe-vivo")).toContainText("Estadísticas de Martín Acosta");
    await expect(page.locator("#informe-vivo .inf-eventos li")).toHaveCount(3);

    await page.getByRole("button", {name: "Cerrar partido"}).last().click();
    await expect(page.locator("#m-tiempo")).toHaveText("FIN");
    await expect(page.getByRole("button", {name: "Reabrir partido"})).toBeVisible();
});
