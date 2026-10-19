// Las vistas del menú en la demo: Control panel, Partidos, Estadísticas, Informes y el ABM de Equipo.
import {expect, test} from "@playwright/test";

test.beforeEach(({page}) => {
    page.on("dialog", d => d.accept());
});

test("Control panel: totales, estados y próximos partidos", async ({page}) => {
    await page.goto("/dt-demo.html");
    await expect(page).toHaveURL(/#dashboard$/);
    await expect(page.locator("#d-partidos")).toHaveText("9");
    await expect(page.locator("#d-proximos li")).toHaveCount(2);
    await expect(page.locator("#d-proximos")).toContainText("Club Social Sur");
});

test("Partidos: crear uno, abrirlo y eliminarlo", async ({page}) => {
    await page.goto("/dt-demo.html#partidos");
    await expect(page.locator("#mis li")).toHaveCount(9);
    await page.locator("#rival").fill("Ferro Carril");
    await page.locator("#torneo").fill("Clausura");
    await page.locator("#crear").click();

    await expect(page).toHaveURL(/#partido=10$/);
    await expect(page.locator("#m-rival")).toHaveText("Ferro Carril");
    await expect(page.locator("#m-tiempo")).toHaveText("1T");

    await page.goto("/dt-demo.html#partidos");
    await expect(page.locator("#mis li")).toHaveCount(10);
    await page.getByRole("button", {name: /^Eliminar el partido contra Ferro Carril/}).click();
    await expect(page.locator("#mis li")).toHaveCount(9);
    await expect(page.locator("#mis")).not.toContainText("Ferro Carril");
});

test("Estadísticas: equipo con rankings y fichas de los jugadores", async ({page}) => {
    await page.goto("/dt-demo.html#estadisticas-equipo");
    await expect(page.locator("#e-balance .dt-kpi")).toHaveCount(6);
    await expect(page.locator("#e-balance")).toContainText("Jugados");
    await expect(page.locator("#e-kpis .dt-kpi")).toHaveCount(14);
    await expect(page.locator("#e-goleadores li").first()).toContainText("1");

    await page.goto("/dt-demo.html#estadisticas-jugadores");
    await expect(page.locator("#fichas .ficha")).toHaveCount(10);
    await expect(page.locator("#fichas .ficha.arquero")).toHaveCount(2);
});

test("Informes: el de un partido y el de un período, del equipo y de un jugador", async ({page}) => {
    await page.goto("/dt-demo.html#informes");
    await expect(page.locator("#i-lista li")).toHaveCount(6);
    await page.locator("#i-lista li button").first().click();
    await expect(page).toHaveURL(/#informe=\d+$/);
    await expect(page.locator("#i-informe h3")).toContainText("CLUB DEMO");
    await expect(page.locator("#i-informe .inf-eventos li")).toHaveCount(30);

    await page.locator("#i-tipo [data-tipo='CAMPEONATO']").click();
    await page.locator("#i-valor").selectOption("Apertura");
    await expect(page.locator("#i-informe h3")).toHaveText("Campeonato Apertura");
    await expect(page.locator("#i-informe")).toContainText("3 jugados");
    // Al tocar un jugador de la tabla, el informe queda solo de él.
    await page.locator("#i-informe .inf-tabla tbody tr", {hasText: "Santiago Herrera"}).click();
    await expect(page.locator("#i-informe")).toContainText("Estadísticas de Santiago Herrera");
    await expect(page.getByLabel("Jugador del informe")).toHaveValue("j108");

    // La demo no genera PDF: avisa.
    await page.getByRole("button", {name: "Exportar PDF"}).click();
    await expect(page.locator("#toast")).toContainText("La demo no genera PDF");
});

test("Equipo: ABM de equipos y de jugadores, con el límite del plan", async ({page}) => {
    await page.goto("/dt-demo.html#equipo");
    await expect(page.locator("#eq-n")).toHaveText("1 de 3 equipos");
    await page.locator("#eq-nombre").fill("Reserva");
    await page.locator("#eq-guardar").click();
    await expect(page.locator("#eq-status")).toHaveText("Equipo creado: Reserva. Ahora cargá sus jugadores.");
    await expect(page.locator("#eq-lista .eq-fila")).toHaveCount(2);
    await page.locator("#eq-nombre").fill("Reserva");
    await page.locator("#eq-guardar").click();
    await expect(page.locator("#eq-status")).toHaveText("Ya tenés un equipo llamado Reserva.");

    // Jugador nuevo en la Reserva, con un número que ya usa otro de ese equipo.
    await page.locator("#pl-nombre").fill("Juan");
    await page.locator("#pl-apellido").fill("Gómez");
    await page.locator("#pl-numero").fill("4");
    await page.locator("#pl-equipos label", {hasText: "Reserva"}).locator("input").check();
    await page.locator("#pl-guardar").click();
    await expect(page.locator("#pl-status")).toHaveText("Agregado: Juan Gómez.");
    await page.locator("#pl-nombre").fill("Pedro");
    await page.locator("#pl-apellido").fill("Luna");
    await page.locator("#pl-numero").fill("4");
    await page.locator("#pl-equipos label", {hasText: "Reserva"}).locator("input").check();
    await page.locator("#pl-guardar").click();
    await expect(page.locator("#pl-status")).toHaveText("El número 4 ya es de Juan Gómez en ese equipo.");

    await page.getByRole("button", {name: "Editar a Juan Gómez"}).click();
    await page.locator("#pl-numero").fill("44");
    await page.locator("#pl-guardar").click();
    await expect(page.locator("#pl-status")).toHaveText("Guardado: Juan Gómez.");
    await expect(page.locator("#pl-lista li", {hasText: "Juan Gómez"})).toContainText("#44");

    await page.getByRole("button", {name: "Dar de baja a Juan Gómez"}).click();
    await expect(page.locator("#pl-status")).toHaveText("Juan Gómez salió del plantel.");
    await expect(page.locator("#pl-lista")).not.toContainText("Juan Gómez");

    // Tercer equipo: el plan mensual llega al máximo.
    await page.locator("#eq-nombre").fill("Infantil");
    await page.locator("#eq-guardar").click();
    await expect(page.locator("#eq-n")).toHaveText("3 de 3 equipos");
    await expect(page.locator("#eq-nombre")).toBeDisabled();
});

test("al volver a la pestaña se ven los equipos creados en otro dispositivo", async ({page}) => {
    await page.goto("/dt-demo.html#estadisticas-equipo");
    await expect(page.locator("#e-kpis .dt-kpi")).toHaveCount(14);
    await expect(page.locator("#e-equipo")).toBeHidden();   // con un solo equipo, el combo no se muestra
    // Otro dispositivo crea un equipo (en la demo, la misma API); la pestaña vuelve a estar visible.
    await page.evaluate(() => fetch("/api/dt/equipos", {method: "POST", headers: {"Content-Type": "application/json"},
        body: JSON.stringify({nombre: "Reserva"})}));
    await page.evaluate(() => document.dispatchEvent(new Event("visibilitychange")));
    await expect(page.locator("#e-equipo")).toBeVisible();
    await expect(page.locator("#e-equipo option")).toHaveText(["Primera Masculino", "Reserva"]);
});
