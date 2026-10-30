// Registro de RinkReports (va después de /js/app.js y /js/reports.js): usuario, clave, email y plan. Una sola cuenta,
// sin club ni perfil: la federación, el club, la categoría y el jugador se eligen en cada informe.
const date = iso => iso ? iso.split("-").reverse().join("/") : "";

// Los planes del usuario de RinkReports (hoy, solo el Free): uno por cajita, elegido el primero disponible.
async function loadPlans() {
    const box = $("plans");
    try {
        const res = await fetch("/api/plans?profile=REPORTS");
        const plans = await res.json();
        if (!res.ok) throw new Error(plans.detail || res.statusText);
        box.innerHTML = "";
        const chosen = plans.find(p => p.available !== false);
        for (const plan of plans) {
            const card = document.createElement("label");
            card.className = "plan";
            card.innerHTML = `<input type="radio" name="plan">
                <span class="plan-name"></span>
                <span class="plan-price"><b></b></span>
                <span class="plan-month"></span>
                <span class="plan-quota"></span>`;
            const input = card.querySelector("input");
            input.value = plan.code;
            input.checked = plan === chosen;
            input.disabled = plan.available === false;
            card.querySelector(".plan-name").textContent = plan.name;
            card.querySelector(".plan-price b").textContent = Number(plan.price) === 0 ? "Gratis" : "Precio a definir";
            card.querySelector(".plan-month").textContent = plan.validUntil ? "Hasta el " + date(plan.validUntil) : "";
            card.querySelector(".plan-quota").textContent = plan.reportsPerMonth > 0
                ? `Hasta ${plan.reportsPerMonth} informes por mes` : "Informes sin límite";
            box.append(card);
        }
        if (!plans.length) box.innerHTML = '<p class="hint">Por ahora no hay planes disponibles.</p>';
    } catch (e) {
        box.innerHTML = "";
        status("No se pudieron cargar los planes: " + e.message, true);
    }
}

passwordToggle("clave", "clave-toggle");

$("form").addEventListener("submit", async ev => {
    ev.preventDefault();
    const data = {
        profile: "REPORTS",
        username: $("usuario").value.trim(),
        password: $("clave").value,
        email: $("email").value.trim(),
        subscription: (document.querySelector("input[name=plan]:checked") || {}).value
    };
    if (!data.subscription) {
        status("Elegí un plan.", true);
        return;
    }
    if (!data.username || !data.password || !data.email) {
        status("Completá todos los campos obligatorios.", true);
        return;
    }
    if (data.password.length < 8) {
        status("La clave lleva al menos 8 caracteres.", true);
        return;
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

loadPlans();
// Deja la cookie CSRF.
session();
