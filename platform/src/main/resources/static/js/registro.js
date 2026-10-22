// Registro de cada producto (va después de /js/app.js).
// Cada producto tiene su registro: RinkReports (/rinkreports/registro.html) ofrece Jugador y Club; RinkMetrics
// (/registro.html), solo el DT. Las cuentas de un producto no ingresan al otro.
const SECCION = producto() === "REPORTS" ? "informes" : "dt";
$("brand").outerHTML = marca();
// Qué se puede registrar hoy: las cuentas de club y los jugadores de la AMP se habilitan por configuración.
let opc = {club: true, jugadorAmp: true};
if (SECCION === "dt") {
    document.querySelectorAll("input[name=rol]").forEach(r => {
        if (r.value === "DT") r.checked = true; else r.closest("label").remove();
    });
    $("perfil-field").hidden = true;
    document.querySelector("input[name=federacion]").closest(".field").hidden = true;
} else {
    // RinkReports: Jugador o Club (?opcion=club elige el club). La FPP queda elegida.
    document.querySelector("input[name=rol][value=DT]").closest("label").remove();
    const opcion = new URLSearchParams(location.search).get("opcion") === "club" ? "CLUB" : "PLAYER";
    document.querySelector(`input[name=rol][value=${opcion}]`).checked = true;
    document.querySelector("input[name=federacion][value=FPP]").checked = true;
    document.querySelectorAll(".profiles small").forEach(s => { s.dataset.texto = s.textContent; });
}

/** Habilita o apaga una opción ("Próximamente"), y si estaba elegida, elige otra. */
function habilitar(input, si, otra) {
    const label = input.closest("label");
    input.disabled = !si;
    label.classList.toggle("no-disponible", !si);
    label.querySelector("small").textContent = si ? label.querySelector("small").dataset.texto : "Próximamente.";
    if (!si && input.checked && otra) otra.checked = true;
}

/** RinkReports: las cuentas de club, y los jugadores de la AMP, solo si están habilitados. */
function reglas() {
    if (SECCION === "dt") return;
    habilitar(document.querySelector("input[name=rol][value=CLUB]"), opc.club,
        document.querySelector("input[name=rol][value=PLAYER]"));
    habilitar(document.querySelector("input[name=federacion][value=AMP]"),
        rol() !== "PLAYER" || opc.jugadorAmp, document.querySelector("input[name=federacion][value=FPP]"));
}

// Primero la federación y después el perfil: Club o Jugador de esa federación (CLUBAMP, PLAYERFPP...).
// Los clubes, las categorías y los jugadores son los de la federación elegida.
let clubFed = null;   // DT: la federación del club elegido
const fed = () => SECCION === "dt" ? clubFed : (document.querySelector("input[name=federacion]:checked") || {}).value;
const rol = () => document.querySelector("input[name=rol]:checked").value;
const isPlayer = () => rol() === "PLAYER";
const isDt = () => rol() === "DT";
const perfil = () => rol() + fed();
const ROL_NAME = {CLUB: "Club ", PLAYER: "Jugador ", DT: "DT "};
const rosters = {};
const catalogs = {};
const BRANCH_LABEL = {MASCULINO: "Masculino", FEMENINO: "Femenino"};
let catalog;

async function loadClubs() {
    const f = fed();
    const sel = $("club");
    sel.innerHTML = "";
    sel.add(new Option("Cargando clubes…", ""));
    sel.disabled = true;
    try {
        if (!catalogs[f]) {
            const res = await fetch("/api/catalogo?federacion=" + f);
            const body = await res.json();
            if (!res.ok) throw new Error(body.detail || res.statusText);
            catalogs[f] = body;
        }
        if (fed() !== f) return;
        catalog = catalogs[f];
        sel.innerHTML = "";
        if (!catalog.available) {
            sel.add(new Option(catalog.message, ""));
            return;
        }
        sel.add(new Option("Elegí tu club", ""));
        catalog.clubs.forEach(c => sel.add(new Option(`${c.name} (${c.code})`, c.id)));
        sel.disabled = false;
        $("go").disabled = false;
    } catch (e) {
        status("No se pudo cargar la lista de clubes: " + e.message, true);
    } finally {
        loadTeams();
    }
}

// Equipos del club: cada categoría en cada rama ("Infantil B Femenino"). Los usa el jugador.
function loadTeams() {
    const club = catalog && catalog.clubs.find(c => String(c.id) === $("club").value);
    for (const [id, empty] of [["equipo", "Elegí la categoría"]]) {
        const sel = $(id);
        sel.innerHTML = "";
        sel.disabled = true;
        if (!club) {
            sel.add(new Option("Elegí el club", ""));
            continue;
        }
        sel.add(new Option(empty, ""));
        ["MASCULINO", "FEMENINO"].forEach(b => club.categories.filter(c => c.branches.includes(b))
            .forEach(c => sel.add(new Option(`${c.label} ${BRANCH_LABEL[b]}`, `${c.key}|${b}`))));
        sel.disabled = false;
    }
    loadPlayers();
}

// Jugadores del equipo según las actas de sus partidos (se piden una vez por equipo).
async function loadPlayers() {
    const sel = $("jugador");
    const clubId = $("club").value;
    const team = $("equipo").value;
    sel.innerHTML = "";
    sel.disabled = true;
    if (!isPlayer()) return;
    if (!clubId || !team) {
        sel.add(new Option("Elegí la categoría", ""));
        return;
    }
    const [categoria, rama] = team.split("|");
    const key = `${fed()}|${clubId}|${team}`;
    sel.add(new Option("Leyendo las actas del equipo… (puede tardar unos segundos)", ""));
    status("");
    try {
        if (!rosters[key]) {
            const res = await fetch(`/api/registro/jugadores?clubId=${clubId}`
                + `&categoria=${encodeURIComponent(categoria)}&rama=${rama}&federacion=${fed()}`);
            const body = await res.json();
            if (!res.ok) throw new Error(body.detail || res.statusText);
            rosters[key] = body;
        }
        if ($("club").value !== clubId || $("equipo").value !== team) return;
        sel.innerHTML = "";
        if (rosters[key].length === 0) {
            sel.add(new Option("El equipo no tiene actas publicadas todavía", ""));
            return;
        }
        sel.add(new Option("Elegí tu nombre", ""));
        rosters[key].forEach(p => sel.add(new Option(p.name, p.id)));
        sel.disabled = false;
    } catch (e) {
        sel.innerHTML = "";
        sel.add(new Option("No se pudo cargar la lista", ""));
        status("No se pudo cargar la lista de jugadores: " + e.message, true);
    }
}

// Beneficios de cada perfil (los planes cambian en informes por mes y precio).
const BENEFICIOS = {
    CLUB: ["Informes de todos los equipos de tu club", "Análisis escrito con IA",
        "Goles, tarjetas y faltas por tramos de 5'", "Fortalezas y debilidades"],
    PLAYER: ["Tu informe en cada categoría en que jugás", "Goles, tarjetas, penales y libres",
        "Minuto a minuto y goles con uno menos", "Fortalezas y debilidades vs. tus compañeros"],
    DT: ["Métricas en vivo desde el celular", "Cada acción por jugador, con el minuto",
        "Pérdidas, recuperaciones, tiros y atajadas", "Resumen del partido por jugador"]
};
// "$ 45.900": pesos sin centavos (vienen redondeados hacia abajo a centenas).
const pesos = n => "$ " + Number(n).toLocaleString("es-AR", {maximumFractionDigits: 0});
const fecha = iso => iso ? iso.split("-").reverse().join("/") : "";

// Planes del perfil elegido: una cajita por plan, con su cintillo de ahorro.
async function loadPlans() {
    const box = $("plans");
    // Los planes del DT son los mismos en las dos federaciones.
    const planesDe = () => isDt() ? "DTFPP" : perfil();
    const p = planesDe();
    try {
        const res = await fetch("/api/suscripciones?perfil=" + p);
        const planes = await res.json();
        if (!res.ok) throw new Error(planes.detail || res.statusText);
        if (planesDe() !== p) return;
        box.innerHTML = "";
        if (planes.length) {
            $("plans-hint").textContent = `Precios en pesos al dólar oficial del día (BNA, venta: U$S 1 = `
                + `${pesos(planes[0].dolar)} al ${fecha(planes[0].dolarFecha)}). Podés cambiar de plan más adelante.`;
        }
        planes.forEach((plan, i) => {
            const card = document.createElement("label");
            card.className = "plan";
            card.innerHTML = `<input type="radio" name="plan">
                <span class="plan-name"></span>
                <span class="plan-price"><b></b> <small></small></span>
                <span class="plan-month"></span>
                <span class="plan-quota"></span>
                <ul></ul>`;
            card.querySelector("input").value = plan.codigo;
            // El plan elegido en la página de la sección (?plan=DT_FREE); si no, el primero que se puede elegir.
            // Los que todavía no se pueden elegir (el DT mensual) van con el cintillo "Próximamente".
            const elegibles = planes.filter(x => x.disponible !== false);
            const pedido = new URLSearchParams(location.search).get("plan");
            const elegido = elegibles.find(x => x.codigo === pedido) || elegibles[0];
            card.querySelector("input").checked = plan === elegido;
            if (plan.disponible === false) {
                card.classList.add("proximamente", "no-disponible");
                card.querySelector("input").disabled = true;
                const cinta = document.createElement("span");
                cinta.className = "cintillo";
                cinta.textContent = "Próximamente";
                card.prepend(cinta);
            }
            card.querySelector(".plan-name").textContent = plan.nombre.replace(/^(Club|Jugador|DT) /, "");
            const gratis = Number(plan.precio) === 0;
            // Los pagos de RinkReports que todavía no se venden: "Precio a definir".
            card.querySelector(".plan-price b").textContent = gratis ? "Gratis"
                : plan.precioADefinir ? "Precio a definir" : pesos(plan.precio);
            card.querySelector(".plan-price small").textContent = gratis || plan.precioADefinir ? "" : "por mes";
            card.querySelector(".plan-month").textContent = plan.vigenteHasta
                ? "Hasta el " + fecha(plan.vigenteHasta) : "Sin permanencia";
            card.querySelector(".plan-quota").textContent = plan.partidosPorMes > 0
                ? `${plan.equiposMax === 1 ? "1 equipo" : "Hasta " + plan.equiposMax + " equipos"} y ${plan.partidosPorMes} partidos por mes`
                : plan.informesPorMes > 0 ? `Hasta ${plan.informesPorMes} informes por mes` : "Informes sin límite";
            const ul = card.querySelector("ul");
            BENEFICIOS[p.startsWith("PLAYER") ? "PLAYER" : p.startsWith("DT") ? "DT" : "CLUB"].forEach(b => {
                const li = document.createElement("li");
                li.textContent = b;
                ul.append(li);
            });
            box.append(card);
        });
    } catch (e) {
        box.innerHTML = "";
        status("No se pudieron cargar los planes: " + e.message, true);
    }
}

let lastFed = null;
function toggleProfile() {
    reglas();
    if (!fed()) return;
    $("after-fed").hidden = false;
    $("fed-hint").hidden = true;
    document.querySelectorAll("input[name=rol]").forEach(r => {
        r.parentElement.querySelector(".rol-name").textContent = ROL_NAME[r.value] + fed();
    });
    $("player-fields").hidden = !isPlayer();
    $("dt-fields").hidden = !isDt();
    loadPlans();
    if (fed() !== lastFed) {
        lastFed = fed();
        loadClubs();
    } else {
        loadPlayers();
    }
}

/**
 * DT: un solo combo con los clubes de las dos federaciones (sin decir de cuál es cada uno). Al elegir el club se
 * toma su federación, que define el perfil (DTFPP o DTAMP).
 */
async function loadClubsDt() {
    const sel = $("club");
    sel.innerHTML = "";
    sel.add(new Option("Cargando clubes…", ""));
    sel.disabled = true;
    try {
        const clubes = [];
        for (const f of ["FPP", "AMP"]) {
            if (!catalogs[f]) {
                const res = await fetch("/api/catalogo?federacion=" + f);
                const body = await res.json();
                if (!res.ok) throw new Error(body.detail || res.statusText);
                catalogs[f] = body;
            }
            if (catalogs[f].available) catalogs[f].clubs.forEach(c => clubes.push({c, f}));
        }
        clubes.sort((a, b) => a.c.name.localeCompare(b.c.name, "es"));
        sel.innerHTML = "";
        sel.add(new Option(clubes.length ? "Elegí tu club" : "No hay clubes disponibles", ""));
        for (const {c, f} of clubes) {
            const o = new Option(`${c.name} (${c.code})`, c.id);
            o.dataset.fed = f;
            sel.add(o);
        }
        sel.disabled = !clubes.length;
        $("go").disabled = !clubes.length;
    } catch (e) {
        status("No se pudo cargar la lista de clubes: " + e.message, true);
    } finally {
        loadTeams();
    }
}

document.querySelectorAll("input[name=federacion], input[name=rol]")
    .forEach(r => r.addEventListener("change", toggleProfile));
$("club").addEventListener("change", () => {
    if (SECCION === "dt") {
        const o = $("club").selectedOptions[0];
        clubFed = o && o.dataset.fed ? o.dataset.fed : null;
        catalog = clubFed ? catalogs[clubFed] : null;
    }
    loadTeams();
});
if (SECCION === "dt") {
    $("after-fed").hidden = false;
    $("fed-hint").hidden = true;
    $("player-fields").hidden = true;
    $("dt-fields").hidden = false;
    loadPlans();
    loadClubsDt();
} else {
    // La FPP ya está elegida: clubes, categorías y planes, con lo que hoy se puede registrar.
    fetch("/api/registro/opciones").then(r => r.ok ? r.json() : opc).catch(() => opc)
        .then(o => { opc = o; toggleProfile(); });
}
$("equipo").addEventListener("change", loadPlayers);

$("form").addEventListener("submit", async ev => {
    ev.preventDefault();
    const data = {
        perfil: perfil(),
        usuario: $("usuario").value.trim(),
        clave: $("clave").value,
        email: $("email").value.trim(),
        clubId: Number($("club").value) || 0,
        suscripcion: (document.querySelector("input[name=plan]:checked") || {}).value
    };
    if (!data.suscripcion) {
        status("Elegí un plan de suscripción.", true);
        return;
    }
    const missing = ["usuario", "clave", "email"].filter(k => !data[k]);
    if (missing.length || !data.clubId) {
        status("Completá todos los campos obligatorios.", true);
        return;
    }
    if (data.clave.length < 8) {
        status("La clave lleva al menos 8 caracteres.", true);
        return;
    }
    if (isPlayer()) {
        [data.categoria, data.rama] = ($("equipo").value || "|").split("|");
        data.jugadorId = Number($("jugador").value) || null;
        if (!data.categoria) {
            status("Elegí la categoría en que jugás.", true);
            return;
        }
        if (!data.jugadorId) {
            status("Elegí tu nombre en la lista de jugadores.", true);
            return;
        }
    }
    $("go").disabled = true;
    status("Registrando…");
    try {
        const alta = await postJson("/api/registro", data);
        // Alta correcta: hay que confirmar el email antes de ingresar.
        $("form").innerHTML = "";
        const done = document.createElement("div");
        done.className = "notice ok";
        done.innerHTML = "<b>¡Listo! Revisá tu correo.</b><p></p><p class=\"alt\"><a href=\"" + ingreso() + "\">Ir a ingresar</a></p>";
        done.querySelector("p").textContent = `Te mandamos un mail a ${alta.email} con un enlace para confirmar `
            + "tu cuenta (vale 48 horas). Después de confirmarla, ya podés ingresar.";
        $("form").append(done);
    } catch (e) {
        status(e.message, true);
        $("go").disabled = false;
    }
});

// Deja la cookie CSRF.
session();
