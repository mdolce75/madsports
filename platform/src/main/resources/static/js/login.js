// Ingreso de cada producto (RinkMetrics en /rinkmetrics/login.html, RinkReports en /rinkreports/login.html): mismo formulario, cada
// uno manda su producto y solo entran las cuentas de ese producto. Va después de /js/app.js.

$("brand").outerHTML = brand();

const NOTICES = {
    noProfile: ["Tu usuario no tiene un perfil habilitado en esta web.", false],
    verified: ["Tu email quedó confirmado: ya podés ingresar.", true],
    "verification=invalid": ["El enlace para confirmar el email venció o ya se usó. Ingresá con tu usuario y clave "
        + "para pedir uno nuevo.", false],
    password: ["Tu clave quedó cambiada: ingresá con la nueva.", true],
    expired: ["Tu sesión venció: ingresá de nuevo para seguir donde estabas.", false]
};
for (const [k, [text, ok]] of Object.entries(NOTICES)) {
    if (location.search.includes(k)) {
        $("notice").hidden = false;
        $("notice").className = ok ? "notice ok" : "notice";
        $("notice").textContent = text;
    }
}

// Email sin confirmar: ofrece reenviar el mail.
function offerResend(username) {
    const link = document.createElement("a");
    link.href = "#";
    link.textContent = "Reenviar el mail de confirmación";
    link.addEventListener("click", async ev => {
        ev.preventDefault();
        try {
            const r = await postJson("/api/verification/resend", {username, product: product()});
            status(r.detail);
        } catch (e) {
            status(e.message, true);
        }
    });
    const p = document.createElement("p");
    p.className = "alt";
    p.append(link);
    $("status").append(p);
}

// La cuenta es del otro producto: el enlace a su ingreso.
function offerOtherLogin(url) {
    const link = document.createElement("a");
    link.href = url;
    link.textContent = "Ir a ese ingreso";
    const p = document.createElement("p");
    p.className = "alt";
    p.append(link);
    $("status").append(p);
}

// Si ya hay sesión de este producto, directo a su pantalla.
session().then(s => {
    if (s.authenticated && s.product === product() && !s.home.startsWith("/rinkmetrics/login.html")) {
        location.replace(returnTarget() || s.home);
    }
});

$("form").addEventListener("submit", async ev => {
    ev.preventDefault();
    if (!$("usuario").value.trim() || !$("clave").value) {
        status("Completá usuario y clave.", true);
        return;
    }
    $("go").disabled = true;
    status("Ingresando…");
    try {
        const res = await api("/api/login", {
            method: "POST",
            headers: {"Content-Type": "application/x-www-form-urlencoded"},
            body: new URLSearchParams(Object.assign({username: $("usuario").value.trim(), password: $("clave").value,
                product: product()}, $("recordarme").checked ? {rememberMe: "true"} : {}))
        });
        const body = await res.json().catch(() => ({}));
        if (!res.ok) {
            status(body.detail || "No se pudo ingresar.", true);
            if (body.code === "EMAIL_NOT_VERIFIED") offerResend($("usuario").value.trim());
            if (body.code === "OTHER_PRODUCT" && body.login) offerOtherLogin(body.login);
            $("go").disabled = false;
            return;
        }
        location.replace(returnTarget() || body.home);
    } catch (e) {
        status(e.message, true);
        $("go").disabled = false;
    }
});
