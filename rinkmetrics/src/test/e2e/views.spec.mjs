// Las vistas del menú en la demo: Control panel, Partidos, Estadísticas, Informes y el ABM de Equipo.
import {expect, test} from "@playwright/test";

test.beforeEach(({page}) => {
    page.on("dialog", d => d.accept());
});

test("Control panel: totales, estados y próximos partidos", async ({page}) => {
    await page.goto("/coach-demo.html");
    await expect(page).toHaveURL(/#dashboard$/);
    await expect(page.locator("#d-partidos")).toHaveText("9");
    await expect(page.locator("#d-proximos li")).toHaveCount(2);
    await expect(page.locator("#d-proximos")).toContainText("Club Social Sur");
});

test("Partidos: crear uno, abrirlo y eliminarlo", async ({page}) => {
    await page.goto("/coach-demo.html#matches");
    await expect(page.locator("#mis li")).toHaveCount(9);
    await page.locator("#rival").fill("Ferro Carril");
    await page.locator("#torneo").fill("Clausura");
    await page.locator("#crear").click();

    await expect(page).toHaveURL(/#match=10$/);
    await expect(page.locator("#m-rival")).toHaveText("Ferro Carril");
    await expect(page.locator("#m-tiempo")).toHaveText("1T");

    await page.goto("/coach-demo.html#matches");
    await expect(page.locator("#mis li")).toHaveCount(10);
    await page.getByRole("button", {name: /^Eliminar el partido contra Ferro Carril/}).click();
    await expect(page.locator("#mis li")).toHaveCount(9);
    await expect(page.locator("#mis")).not.toContainText("Ferro Carril");
});

test("Informes del período: estadísticas del equipo, rankings y la ficha de cada jugador al tocarlo", async ({page}) => {
    await page.goto("/coach-demo.html#reports");
    await page.locator("#i-tipo [data-kind='YEAR']").click();
    const inf = page.locator("#i-informe");
    await expect(inf.locator(".inf-kpis .coach-kpi")).toHaveCount(14);
    for (const t of ["Top 5 · Goles", "Top 5 · Faltas cometidas", "Top 5 · Tarjetas", "Top 3 · Penales", "Top 3 · Libres directos"]) {
        await expect(inf).toContainText(t);
    }
    // Sin jugador elegido no hay fichas; al tocar un jugador aparece la suya.
    await expect(inf.locator(".ficha")).toHaveCount(0);
    await inf.locator(".inf-tabla tbody tr.clic").first().click();
    await expect(inf.locator(".ficha")).toHaveCount(1);
});

test("Informes: el de un partido y el de un período, del equipo y de un jugador", async ({page}) => {
    await page.goto("/coach-demo.html#reports");
    await expect(page.locator("#i-lista li")).toHaveCount(6);
    await page.locator("#i-lista li button").first().click();
    await expect(page).toHaveURL(/#report=\d+$/);
    await expect(page.locator("#i-informe h3")).toContainText("CLUB DEMO");
    await expect(page.locator("#i-informe .inf-eventos li")).toHaveCount(30);

    await page.locator("#i-tipo [data-kind='CHAMPIONSHIP']").click();
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
    await page.goto("/coach-demo.html#team");
    await expect(page.locator("#eq-n")).toHaveText("1 de 3 equipos");
    await page.locator("#eq-categoria").selectOption("otro");
    await page.locator("#eq-nombre").fill("Reserva");
    await page.locator("#eq-guardar").click();
    await expect(page.locator("#eq-status")).toHaveText("Equipo creado: Reserva. Ahora cargá sus jugadores.");
    await expect(page.locator("#eq-lista .eq-fila")).toHaveCount(2);
    await page.locator("#eq-categoria").selectOption("otro");
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
    await page.locator("#eq-categoria").selectOption("otro");
    await page.locator("#eq-nombre").fill("Infantil");
    await page.locator("#eq-guardar").click();
    await expect(page.locator("#eq-n")).toHaveText("3 de 3 equipos");
    await expect(page.locator("#eq-nombre")).toBeDisabled();
});

test("al volver a la pestaña se ven los equipos creados en otro dispositivo", async ({page}) => {
    await page.goto("/coach-demo.html#reports");
    await expect(page.locator("#i-lista li").first()).toBeVisible();
    await expect(page.locator("#i-equipo")).toBeHidden();   // con un solo equipo, el combo no se muestra
    // Otro dispositivo crea un equipo (en la demo, la misma API); la pestaña vuelve a estar visible.
    await page.evaluate(() => fetch("/api/coach/teams", {method: "POST", headers: {"Content-Type": "application/json"},
        body: JSON.stringify({name: "Reserva"})}));
    await page.evaluate(() => document.dispatchEvent(new Event("visibilitychange")));
    await expect(page.locator("#i-equipo")).toBeVisible();
    await expect(page.locator("#i-equipo option")).toHaveText(["Primera Masculino", "Reserva"]);
});

test("Equipo desde una categoría del club: su plantel de SIDGAD, sin los destildados, que se suman después", async ({page}) => {
    await page.goto("/coach-demo.html#team");
    await expect(page.locator("#eq-categoria option")).toHaveText(["Elegí la categoría", "Primera Masculino",
        "Juvenil Masculino", "Infantil Femenino", "Otro: nombre libre, sin jugadores de SIDGAD"]);
    // Sin categoría, no se crea.
    await page.locator("#eq-guardar").click();
    await expect(page.locator("#eq-status")).toContainText("Elegí la categoría de tu club");

    await page.locator("#eq-categoria").selectOption({label: "Juvenil Masculino"});
    await expect(page.locator("#eq-nombre")).toHaveValue("Juvenil Masculino");
    await expect(page.locator("#eq-jugadores input")).toHaveCount(8);
    await expect(page.locator("#eq-sidgad-n")).toHaveText("8 de 8 incluidos");
    await page.locator("#eq-jugadores label", {hasText: "Iván Luna"}).locator("input").uncheck();
    await expect(page.locator("#eq-sidgad-n")).toHaveText("7 de 8 incluidos");
    await page.locator("#eq-guardar").click();
    await expect(page.locator("#eq-status")).toHaveText("Equipo creado: Juvenil Masculino, con 7 de los 8 jugadores de "
        + "SIDGAD (1 no incluido: está en la lista para sumarlo después).");
    await expect(page.locator("#eq-lista .eq-fila", {hasText: "Juvenil Masculino"})).toContainText("SIDGAD: Juvenil Masculino");

    // La lista queda en el equipo nuevo: Iván figura como no incluido, con "Incluir".
    await expect(page.locator("#pl-filtro option:checked")).toHaveText("Juvenil Masculino");
    const ivan = page.locator("#pl-lista li", {hasText: "Iván Luna"});
    await expect(ivan).toContainText("No incluido");
    // Santiago Herrera ya estaba en Primera: no se duplica, suma el equipo.
    await expect(page.locator("#pl-lista li", {hasText: "Santiago Herrera"})).toContainText("Primera Masculino · Juvenil Masculino");

    // No aparece para cargar un partido del equipo hasta incluirlo.
    await page.getByRole("button", {name: "Incluir a Iván Luna en Juvenil Masculino"}).click();
    await expect(page.locator("#pl-status")).toHaveText("Iván Luna quedó incluido en Juvenil Masculino.");
    await expect(ivan).not.toContainText("No incluido");
});
