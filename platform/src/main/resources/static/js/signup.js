// Registro de cada producto (va después de /js/app.js).
// Cada registro es de un solo perfil: RinkReports tiene el de Jugador (/rinkreports/signup.html?option=player) y el de
// Club (?option=club), y RinkMetrics (/rinkmetrics/signup.html), el del DT. No se elige el perfil: lo dice el enlace.
// Las cuentas de un producto no ingresan al otro.
const SECTION = product() === "REPORTS" ? "informes" : "dt";
$("brand").outerHTML = brand();
// Qué se puede registrar hoy: las cuentas de club, la AMP y sus jugadores se habilitan por configuración.
let signupOpts = {club: true, amp: false, playerAmp: false};
if (SECTION === "dt") {
    document.querySelectorAll("input[name=rol]").forEach(r => {
        if (r.value === "COACH") r.checked = true; else r.closest("label").remove();
    });
    $("perfil-field").hidden = true;
    document.querySelector("input[name=federacion]").closest(".field").hidden = true;
} else {
    // RinkReports: el registro del Jugador o el del Club (?option=club; sin opción, el del jugador). La FPP queda elegida.
    const chosenOption = new URLSearchParams(location.search).get("option") === "club" ? "CLUB" : "PLAYER";
    document.querySelectorAll("input[name=rol]").forEach(r => {
        if (r.value === chosenOption) r.checked = true; else r.closest("label").remove();
    });
    $("perfil-field").hidden = true;
    const club = chosenOption === "CLUB";
    $("reg-tag").textContent = club ? "RinkReports · Cuenta de club" : "RinkReports · Cuenta de jugador";
    $("reg-lead").innerHTML = club
        ? "Tu club, para el informe de todas sus categorías: por rama, por torneo o de la temporada completa. "
            + '<a href="/rinkreports/sample.html?kind=club">Mirá un ejemplo</a> (datos ficticios).'
        : "Tu club, la categoría en que jugás y tu nombre en la planilla, para tu informe de rendimiento. "
            + '<a href="/rinkreports/sample.html?kind=player">Mirá un ejemplo</a> (datos ficticios).';
    document.title = (club ? "Cuenta de club" : "Cuenta de jugador") + " · RinkReports";
    document.querySelector("input[name=federacion][value=FPP]").checked = true;
    document.querySelectorAll(".profiles small").forEach(s => { s.dataset.text = s.textContent; });
}

/** Habilita o apaga una opción ("Próximamente"), y si estaba elegida, elige otra. */
function enable(input, si, other) {
    const label = input.closest("label");
    input.disabled = !si;
    label.classList.toggle("no-disponible", !si);
    label.querySelector("small").textContent = si ? label.querySelector("small").dataset.text : "Próximamente.";
    if (!si && input.checked && other) other.checked = true;
}

/** El registro de club, si las cuentas de club todavía no se habilitaron: avisa y no deja enviarlo. */
function clubAvailable() {
    if (role() !== "CLUB" || signupOpts.club) return;
    $("go").disabled = true;
    status("Las cuentas de club estarán disponibles próximamente.", true);
}

/** RinkReports: las cuentas de club, y los jugadores de la AMP, solo si están habilitados. */
function rules() {
    if (SECTION === "dt") return;
    // Sin la AMP (oculta hasta nuevo aviso) la federación es la FPP y no hay nada que elegir.
    if (!signupOpts.amp) {
        document.querySelector("input[name=federacion][value=AMP]")?.closest("label").remove();
        document.querySelector("input[name=federacion]").closest(".field").hidden = true;
        return clubAvailable();
    }
    clubAvailable();
    enable(document.querySelector("input[name=federacion][value=AMP]"),
        role() !== "PLAYER" || signupOpts.playerAmp, document.querySelector("input[name=federacion][value=FPP]"));
}

// Primero la federación y después el perfil: Club o Jugador de esa federación (CLUBAMP, PLAYERFPP...).
// Los clubes, las categorías y los jugadores son los de la federación elegida.
let clubFed = null;   // DT: la federación del club elegido
const fed = () => SECTION === "dt" ? clubFed : (document.querySelector("input[name=federacion]:checked") || {}).value;
const role = () => document.querySelector("input[name=rol]:checked").value;
const isPlayer = () => role() === "PLAYER";
const isDt = () => role() === "COACH";
const profile = () => role() + fed();
const ROLE_NAME = {CLUB: "Club ", PLAYER: "Jugador ", COACH: "DT "};
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
            const res = await fetch("/api/catalog?federation=" + f);
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
        $("go").disabled = role() === "CLUB" && !signupOpts.club;
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
    const [category, branch] = team.split("|");
    const key = `${fed()}|${clubId}|${team}`;
    sel.add(new Option("Leyendo las actas del equipo… (puede tardar unos segundos)", ""));
    status("");
    try {
        if (!rosters[key]) {
            const res = await fetch(`/api/signup/players?clubId=${clubId}`
                + `&category=${encodeURIComponent(category)}&branch=${branch}&federation=${fed()}`);
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
const BENEFITS = {
    CLUB: ["Informes de todos los equipos de tu club", "Análisis escrito con IA",
        "Goles, tarjetas y faltas por tramos de 5'", "Fortalezas y debilidades"],
    PLAYER: ["Tu informe en cada categoría en que jugás", "Goles, tarjetas, penales y libres",
        "Minuto a minuto y goles con uno menos", "Fortalezas y debilidades vs. tus compañeros"],
    COACH: ["Métricas en vivo desde el celular", "Cada acción por jugador, con el minuto",
        "Pérdidas, recuperaciones, tiros y atajadas", "Resumen del partido por jugador"]
};
// "$ 45.900": pesos sin centavos (vienen redondeados hacia abajo a centenas).
const pesos = n => "$ " + Number(n).toLocaleString("es-AR", {maximumFractionDigits: 0});
const date = iso => iso ? iso.split("-").reverse().join("/") : "";

// Planes del perfil elegido: una cajita por plan, con su cintillo de ahorro.
async function loadPlans() {
    const box = $("plans");
    // Los planes del DT son los mismos en las dos federaciones.
    const plansOf = () => isDt() ? "COACHFPP" : profile();
    const p = plansOf();
    try {
        const res = await fetch("/api/plans?profile=" + p);
        const plans = await res.json();
        if (!res.ok) throw new Error(plans.detail || res.statusText);
        if (plansOf() !== p) return;
        box.innerHTML = "";
        if (plans.length) {
            $("plans-hint").textContent = `Precios en pesos al dólar oficial del día (BNA, venta: U$S 1 = `
                + `${pesos(plans[0].dollar)} al ${date(plans[0].dollarDate)}). Podés cambiar de plan más adelante.`;
        }
        plans.forEach((plan, i) => {
            const card = document.createElement("label");
            card.className = "plan";
            card.innerHTML = `<input type="radio" name="plan">
                <span class="plan-name"></span>
                <span class="plan-price"><b></b> <small></small></span>
                <span class="plan-month"></span>
                <span class="plan-quota"></span>
                <ul></ul>`;
            card.querySelector("input").value = plan.code;
            // El plan se elige acá: de entrada, el primero que se puede elegir (el free). Los que todavía no se
            // pueden elegir (los mensuales) van con el cintillo "Próximamente".
            const chosen = plans.find(x => x.available !== false);
            card.querySelector("input").checked = plan === chosen;
            if (plan.available === false) {
                card.classList.add("proximamente", "no-disponible");
                card.querySelector("input").disabled = true;
                const ribbon = document.createElement("span");
                ribbon.className = "cintillo";
                ribbon.textContent = "Próximamente";
                card.prepend(ribbon);
            }
            card.querySelector(".plan-name").textContent = plan.name.replace(/^(Club|Jugador|DT) /, "");
            const free = Number(plan.price) === 0;
            // Los pagos de RinkReports que todavía no se venden: "Precio a definir".
            card.querySelector(".plan-price b").textContent = free ? "Gratis"
                : plan.priceToBeDefined ? "Precio a definir" : pesos(plan.price);
            card.querySelector(".plan-price small").textContent = free || plan.priceToBeDefined ? "" : "por mes";
            card.querySelector(".plan-month").textContent = plan.validUntil
                ? "Hasta el " + date(plan.validUntil) : "Sin permanencia";
            card.querySelector(".plan-quota").textContent = plan.matchesPerMonth > 0
                ? `${plan.maxTeams === 1 ? "1 equipo" : "Hasta " + plan.maxTeams + " equipos"} y ${plan.matchesPerMonth} partidos por mes`
                : plan.reportsPerMonth > 0 ? `Hasta ${plan.reportsPerMonth} informes por mes` : "Informes sin límite";
            const ul = card.querySelector("ul");
            BENEFITS[p.startsWith("PLAYER") ? "PLAYER" : p.startsWith("COACH") ? "COACH" : "CLUB"].forEach(b => {
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
    rules();
    if (!fed()) return;
    $("after-fed").hidden = false;
    $("fed-hint").hidden = true;
    document.querySelectorAll("input[name=rol]").forEach(r => {
        r.parentElement.querySelector(".rol-name").textContent = ROLE_NAME[r.value] + fed();
    });
    $("player-fields").hidden = !isPlayer();
    $("coach-fields").hidden = !isDt();
    loadPlans();
    if (fed() !== lastFed) {
        lastFed = fed();
        loadClubs();
    } else {
        loadPlayers();
    }
}

/**
 * DT: un solo combo con los clubes de las federaciones ofrecidas (sin decir de cuál es cada uno; la AMP, oculta hasta
 * nuevo aviso). Al elegir el club se toma su federación, que define el perfil (COACHFPP o COACHAMP).
 */
async function loadCoachClubs() {
    const sel = $("club");
    sel.innerHTML = "";
    sel.add(new Option("Cargando clubes…", ""));
    sel.disabled = true;
    try {
        const clubList = [];
        for (const f of signupOpts.amp ? ["FPP", "AMP"] : ["FPP"]) {
            if (!catalogs[f]) {
                const res = await fetch("/api/catalog?federation=" + f);
                const body = await res.json();
                if (!res.ok) throw new Error(body.detail || res.statusText);
                catalogs[f] = body;
            }
            if (catalogs[f].available) catalogs[f].clubs.forEach(c => clubList.push({c, f}));
        }
        clubList.sort((a, b) => a.c.name.localeCompare(b.c.name, "es"));
        sel.innerHTML = "";
        sel.add(new Option(clubList.length ? "Elegí tu club" : "No hay clubes disponibles", ""));
        for (const {c, f} of clubList) {
            const o = new Option(`${c.name} (${c.code})`, c.id);
            o.dataset.fed = f;
            sel.add(o);
        }
        sel.disabled = !clubList.length;
        $("go").disabled = !clubList.length;
    } catch (e) {
        status("No se pudo cargar la lista de clubes: " + e.message, true);
    } finally {
        loadTeams();
    }
}

document.querySelectorAll("input[name=federacion], input[name=rol]")
    .forEach(r => r.addEventListener("change", toggleProfile));
$("club").addEventListener("change", () => {
    if (SECTION === "dt") {
        const o = $("club").selectedOptions[0];
        clubFed = o && o.dataset.fed ? o.dataset.fed : null;
        catalog = clubFed ? catalogs[clubFed] : null;
    }
    loadTeams();
});
if (SECTION === "dt") {
    $("after-fed").hidden = false;
    $("fed-hint").hidden = true;
    $("player-fields").hidden = true;
    $("coach-fields").hidden = false;
    loadPlans();
    fetch("/api/signup/options").then(r => r.ok ? r.json() : signupOpts).catch(() => signupOpts)
        .then(o => { signupOpts = o; loadCoachClubs(); });
} else {
    // La FPP ya está elegida: clubes, categorías y planes, con lo que hoy se puede registrar.
    fetch("/api/signup/options").then(r => r.ok ? r.json() : signupOpts).catch(() => signupOpts)
        .then(o => { signupOpts = o; toggleProfile(); });
}
$("equipo").addEventListener("change", loadPlayers);

$("form").addEventListener("submit", async ev => {
    ev.preventDefault();
    const data = {
        profile: profile(),
        username: $("usuario").value.trim(),
        password: $("clave").value,
        email: $("email").value.trim(),
        clubId: Number($("club").value) || 0,
        subscription: (document.querySelector("input[name=plan]:checked") || {}).value
    };
    if (!data.subscription) {
        status("Elegí un plan de suscripción.", true);
        return;
    }
    const missing = ["username", "password", "email"].filter(k => !data[k]);
    if (missing.length || !data.clubId) {
        status("Completá todos los campos obligatorios.", true);
        return;
    }
    if (data.password.length < 8) {
        status("La clave lleva al menos 8 caracteres.", true);
        return;
    }
    if (isPlayer()) {
        [data.category, data.branch] = ($("equipo").value || "|").split("|");
        data.playerId = Number($("jugador").value) || null;
        if (!data.category) {
            status("Elegí la categoría en que jugás.", true);
            return;
        }
        if (!data.playerId) {
            status("Elegí tu nombre en la lista de jugadores.", true);
            return;
        }
    }
    $("go").disabled = true;
    status("Registrando…");
    try {
        const account = await postJson("/api/signup", data);
        // Alta correcta: hay que confirmar el email antes de ingresar.
        $("form").innerHTML = "";
        const done = document.createElement("div");
        done.className = "notice ok";
        done.innerHTML = "<b>¡Listo! Revisá tu correo.</b><p></p><p class=\"alt\"><a href=\"" + login() + "\">Ir a ingresar</a></p>";
        done.querySelector("p").textContent = `Te mandamos un mail a ${account.email} con un enlace para confirmar `
            + "tu cuenta (vale 48 horas). Después de confirmarla, ya podés ingresar.";
        $("form").append(done);
    } catch (e) {
        status(e.message, true);
        $("go").disabled = false;
    }
});

// Deja la cookie CSRF.
session();
