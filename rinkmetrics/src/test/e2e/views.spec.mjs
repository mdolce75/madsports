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
    // El reparto entre los jugadores, en tortas (una por métrica), en vez de la tabla.
    await expect(inf.locator(".inf-torta").first()).toContainText("Goles");
    await expect(inf.locator(".inf-tabla th", {hasText: "% Pérd."})).toHaveCount(0);
    for (const t of ["Top 5 · Goles", "Top 5 · Faltas cometidas", "Top 5 · Tarjetas", "Top 3 · Penales", "Top 3 · Libres directos"]) {
        await expect(inf).toContainText(t);
    }
    // Sin jugador elegido no hay fichas; al tocar un jugador aparece la suya.
    await expect(inf.locator(".ficha")).toHaveCount(0);
    await inf.locator(".inf-leyenda button.it").first().click();
    await expect(inf.locator(".ficha")).toHaveCount(1);
});

test("Informes: el de un partido y el de un período, del equipo y de un jugador", async ({page}) => {
    await page.goto("/coach-demo.html#reports");
    await expect(page.locator("#i-lista li")).toHaveCount(6);
    await page.locator("#i-lista li button").first().click();
    await expect(page).toHaveURL(/#report=\d+$/);
    await expect(page.locator("#i-informe h3")).toContainText("CLUB DEMO");
    await expect(page.locator("#i-informe .inf-eventos li")).toHaveCount(30);
    // Las mismas métricas que el de un período: también los rankings (sin PJ ni promedio, que en un partido sobran).
    for (const t of ["Top 5 · Goles", "Situaciones de gol (tiros)", "Top 5 · Tarjetas", "Top 3 · Penales"]) {
        await expect(page.locator("#i-informe")).toContainText(t);
    }
    await expect(page.locator("#i-informe .inf-tabla th", {hasText: "Por partido"})).toHaveCount(0);
    // Debajo de las tortas, las tablas por tiempo (solo en el de un partido).
    await expect(page.locator("#i-informe .inf-sub2", {hasText: "Primer tiempo"})).toHaveCount(1);
    await expect(page.locator("#i-informe .inf-sub2", {hasText: "Segundo tiempo"})).toHaveCount(1);
    await expect(page.locator("#i-informe .inf-sub2", {hasText: "Total del partido"})).toHaveCount(1);

    await page.locator("#i-tipo [data-kind='CHAMPIONSHIP']").click();
    await page.locator("#i-valor").selectOption("Apertura");
    await expect(page.locator("#i-informe h3")).toHaveText("Campeonato Apertura");
    await expect(page.locator("#i-informe")).toContainText("3 jugados");
    // Al tocar un jugador de la tabla, el informe queda solo de él.
    // La primera tabla es la de los jugadores (después vienen los rankings, donde también figura).
    await page.locator("#i-informe .inf-leyenda button.it", {hasText: "Santiago Herrera"}).first().click();
    await expect(page.locator("#i-informe")).toContainText("Estadísticas de Santiago Herrera");
    await expect(page.getByLabel("Jugador del informe")).toHaveValue("j108");

    // La demo no genera PDF: avisa.
    await page.getByRole("button", {name: "Exportar PDF"}).click();
    await expect(page.locator("#toast")).toContainText("La demo no genera PDF");
});

test("Equipo: los equipos salen de SIDGAD y sus jugadores se tildan o destildan, con el límite del plan", async ({page}) => {
    await page.goto("/coach-demo.html#team");
    await expect(page.locator("#eq-n")).toHaveText("1 de 3 equipos");
    // Sin carga de jugadores a mano ni equipos fuera de SIDGAD (eso es solo para otra federación).
    await expect(page.locator("#pl-form")).toBeHidden();
    await expect(page.locator("#eq-categoria option", {hasText: "Otro"})).toHaveCount(0);

    // Juvenil desde SIDGAD, sin Iván Luna.
    await page.locator("#eq-categoria").selectOption("JUVENIL|MASCULINO");
    await expect(page.locator("#eq-sidgad-n")).toHaveText("8 de 8 incluidos");
    await page.locator("#eq-jugadores label", {hasText: "Iván Luna"}).locator("input").uncheck();
    await page.locator("#eq-guardar").click();
    await expect(page.locator("#eq-status")).toContainText("Equipo creado: Juvenil Masculino, con 7 de los 8 jugadores de SIDGAD");
    await expect(page.locator("#eq-lista .eq-fila")).toHaveCount(2);

    // Sus jugadores, elegido el equipo nuevo: Iván destildado; se lo tilda y a Ramiro se lo destilda.
    await expect(page.locator("#pl-n")).toHaveText("7 de 8 juegan");
    const ivan = page.locator("#pl-lista li", {hasText: "Iván Luna"}).locator("input");
    await expect(ivan).not.toBeChecked();
    await ivan.check();
    await expect(page.locator("#pl-status")).toHaveText("Iván Luna juega en Juvenil Masculino.");
    await expect(page.locator("#pl-n")).toHaveText("8 de 8 juegan");
    await page.locator("#pl-lista li", {hasText: "Ramiro Ortiz"}).locator("input").uncheck();
    await expect(page.locator("#pl-status")).toHaveText("Ramiro Ortiz quedó afuera de Juvenil Masculino.");
    await expect(page.locator("#pl-n")).toHaveText("7 de 8 juegan");
    await expect(page.locator("#pl-lista li", {hasText: "Ramiro Ortiz"})).toHaveClass(/pendiente/);

    // Tercer equipo: el plan llega al máximo.
    await page.locator("#eq-categoria").selectOption("INFANTIL|FEMENINO");
    await expect(page.locator("#eq-sidgad-n")).toHaveText("5 de 5 incluidos");
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
        "Juvenil Masculino", "Infantil Femenino"]);
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

    // La lista queda en el equipo nuevo: Iván, destildado.
    await expect(page.locator("#pl-filtro option:checked")).toHaveText("Juvenil Masculino");
    const ivan = page.locator("#pl-lista li", {hasText: "Iván Luna"});
    await expect(ivan.locator("input")).not.toBeChecked();
    await expect(ivan).toHaveClass(/pendiente/);
    // Santiago Herrera ya estaba en Primera: no se duplica, juega en los dos equipos.
    await expect(page.locator("#pl-lista li", {hasText: "Santiago Herrera"}).locator("input")).toBeChecked();
    await page.locator("#pl-filtro").selectOption({label: "Primera Masculino"});
    await expect(page.locator("#pl-lista li", {hasText: "Santiago Herrera"})).toHaveCount(1);
    await expect(page.locator("#pl-lista li", {hasText: "Santiago Herrera"}).locator("input")).toBeChecked();

    // Tildarlo lo suma al equipo (y ya aparece para cargar sus partidos).
    await page.locator("#pl-filtro").selectOption({label: "Juvenil Masculino"});
    await ivan.locator("input").check();
    await expect(page.locator("#pl-status")).toHaveText("Iván Luna juega en Juvenil Masculino.");
    await expect(ivan).not.toHaveClass(/pendiente/);
});

// El DT de otra federación (sin SIDGAD): la demo con su sesión. Arma el equipo con un nombre y carga los jugadores a mano.
test("Equipo a mano: otra federación crea el equipo con un nombre y carga, corrige y da de baja a sus jugadores", async ({page}) => {
    await page.route("**/js/coach/demo.js", async route => {
        const body = (await (await route.fetch()).text())
            .replace('profiles: ["COACHFPP"]', 'profiles: ["COACHOTR"]').replace('federation: "FPP"', 'federation: "OTR"');
        await route.fulfill({body, contentType: "text/javascript"});
    });
    await page.goto("/coach-demo.html#team");
    await expect(page.locator("#eq-n")).toHaveText("1 de 3 equipos");
    await expect(page.locator("#eq-categoria-campo")).toBeHidden();
    await expect(page.locator("#pl-form")).toBeVisible();

    await page.locator("#eq-nombre").fill("Cuarta");
    await page.locator("#eq-guardar").click();
    await expect(page.locator("#eq-status")).toHaveText("Equipo creado: Cuarta.");
    await expect(page.locator("#pl-filtro option:checked")).toHaveText("Cuarta");
    await expect(page.locator("#pl-lista")).toContainText("Este equipo todavía no tiene jugadores");

    await page.locator("#pl-nombre").fill("Ana");
    await page.locator("#pl-apellido").fill("Gómez");
    await page.locator("#pl-numero").fill("5");
    await page.locator("#pl-guardar").click();
    await expect(page.locator("#pl-form-status")).toHaveText("Agregado: Ana Gómez en Cuarta.");
    await expect(page.locator("#pl-lista li")).toHaveCount(1);
    await expect(page.locator("#pl-lista li")).toContainText("#5");

    await page.getByRole("button", {name: "Editar a Ana Gómez"}).click();
    await expect(page.locator("#pl-nombre")).toHaveValue("Ana");
    await page.locator("#pl-nombre").fill("Ana María");
    await page.locator("#pl-guardar").click();
    await expect(page.locator("#pl-form-status")).toHaveText("Guardado: Ana María Gómez.");

    await page.getByRole("button", {name: "Dar de baja a Ana María Gómez"}).click();
    await expect(page.locator("#pl-status")).toHaveText("Ana María Gómez salió del plantel.");
    await expect(page.locator("#pl-lista")).toContainText("Este equipo todavía no tiene jugadores");
});
