// Ingreso de cada producto (RinkMetrics en /login.html, RinkReports en /rinkreports/login.html): mismo formulario, cada
// uno manda su producto y solo entran las cuentas de ese producto. Va después de /js/app.js.

$("brand").outerHTML = marca();

const AVISOS = {
    sinPerfil: ["Tu usuario no tiene un perfil habilitado en esta web.", false],
    verificado: ["Tu email quedó confirmado: ya podés ingresar.", true],
    "verificacion=invalida": ["El enlace para confirmar el email venció o ya se usó. Ingresá con tu usuario y clave "
        + "para pedir uno nuevo.", false],
    clave: ["Tu clave quedó cambiada: ingresá con la nueva.", true],
    vencida: ["Tu sesión venció: ingresá de nuevo para seguir donde estabas.", false]
};
for (const [k, [texto, ok]] of Object.entries(AVISOS)) {
    if (location.search.includes(k)) {
        $("notice").hidden = false;
        $("notice").className = ok ? "notice ok" : "notice";
        $("notice").textContent = texto;
    }
}

// Email sin confirmar: ofrece reenviar el mail.
function ofrecerReenvio(usuario) {
    const link = document.createElement("a");
    link.href = "#";
    link.textContent = "Reenviar el mail de confirmación";
    link.addEventListener("click", async ev => {
        ev.preventDefault();
        try {
            const r = await postJson("/api/verificacion/reenviar", {usuario, producto: producto()});
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
function ofrecerOtroIngreso(url) {
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
    if (s.autenticado && s.producto === producto() && !s.inicio.startsWith("/login.html")) {
        location.replace(volverA() || s.inicio);
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
            body: new URLSearchParams(Object.assign({usuario: $("usuario").value.trim(), clave: $("clave").value,
                producto: producto()}, $("recordarme").checked ? {recordarme: "true"} : {}))
        });
        const body = await res.json().catch(() => ({}));
        if (!res.ok) {
            status(body.detail || "No se pudo ingresar.", true);
            if (body.code === "EMAIL_NO_VERIFICADO") ofrecerReenvio($("usuario").value.trim());
            if (body.code === "OTRO_PRODUCTO" && body.ingreso) ofrecerOtroIngreso(body.ingreso);
            $("go").disabled = false;
            return;
        }
        location.replace(volverA() || body.inicio);
    } catch (e) {
        status(e.message, true);
        $("go").disabled = false;
    }
});
