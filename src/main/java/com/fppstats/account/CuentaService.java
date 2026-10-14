package com.fppstats.account;

import com.fppstats.account.TokenUsuario.Tipo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Confirmación del email y recuperación de la clave con enlaces de un solo uso enviados por mail. El token va solo en
 * el enlace; en la base queda su hash. Para no revelar qué emails están registrados, pedir un enlace siempre
 * responde lo mismo, exista o no la cuenta.
 */
@Service
public class CuentaService {

    static final Duration VIGENCIA_VERIFICACION = Duration.ofHours(48);
    static final Duration VIGENCIA_RECUPERO = Duration.ofHours(1);
    /** Un mail del mismo tipo por minuto y por usuario, como mucho. */
    static final Duration ESPERA_ENTRE_MAILS = Duration.ofMinutes(1);
    static final int CLAVE_MINIMA = 8;

    private final UsuarioRepository usuarios;
    private final TokenUsuarioRepository tokens;
    private final PasswordEncoder encoder;
    private final Mailer mailer;
    private final Clock clock;
    private final String baseUrl;
    private final SecureRandom random = new SecureRandom();

    public CuentaService(UsuarioRepository usuarios, TokenUsuarioRepository tokens, PasswordEncoder encoder,
                         Mailer mailer, Clock clock, @Value("${fpp.mail.base-url}") String baseUrl) {
        this.usuarios = usuarios;
        this.tokens = tokens;
        this.encoder = encoder;
        this.mailer = mailer;
        this.clock = clock;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** Manda el enlace para confirmar el email (después del registro, o si lo pide de nuevo). */
    @Transactional
    public void enviarVerificacion(Usuario u) {
        if (u.isEmailVerificado() || recienEnviado(u, Tipo.VERIFICACION)) {
            return;
        }
        String token = nuevoToken(u, Tipo.VERIFICACION, VIGENCIA_VERIFICACION);
        mailer.send(new MailContent(u.getEmail(), "Confirmá tu cuenta de Mad Sports",
                "Un paso más: confirmá tu email para empezar a usar Mad Sports.",
                "Tu cuenta", "Confirmá tu email", saludo(u),
                List.of("Gracias por sumarte a Mad Sports. Para activar tu cuenta (usuario " + u.getUsuario()
                        + "), confirmá tu email con el botón de abajo.",
                        "Después ya podés ingresar y generar tus informes de hockey sobre patines."),
                "Confirmar mi email", baseUrl + "/api/verificar?token=" + token,
                "El enlace vale 48 horas. Si no creaste esta cuenta, ignorá este mail."));
    }

    /** Vuelve a mandar la confirmación, si hay una cuenta sin confirmar con ese email (o ese usuario). */
    @Transactional
    public void reenviarVerificacion(String email, String usuario) {
        Optional<Usuario> u = buscarPorEmail(email);
        if (u.isEmpty() && usuario != null && !usuario.isBlank()) {
            u = usuarios.findByUsuario(usuario.trim().toLowerCase(java.util.Locale.ROOT));
        }
        u.filter(x -> !x.isEmailVerificado()).ifPresent(this::enviarVerificacion);
    }

    /** @return true si el enlace era válido y el email quedó confirmado */
    @Transactional
    public boolean verificar(String token) {
        Optional<TokenUsuario> t = vigente(token, Tipo.VERIFICACION);
        t.ifPresent(x -> {
            x.usar();
            x.getUsuario().verificarEmail();
        });
        return t.isPresent();
    }

    /** "Olvidé mi clave": manda el enlace para elegir una nueva, si existe una cuenta con ese email. */
    @Transactional
    public void olvideClave(String email) {
        buscarPorEmail(email).filter(u -> !recienEnviado(u, Tipo.RECUPERO)).ifPresent(u -> {
            String token = nuevoToken(u, Tipo.RECUPERO, VIGENCIA_RECUPERO);
            mailer.send(new MailContent(u.getEmail(), "Elegí una clave nueva para Mad Sports",
                    "Pediste cambiar la clave de tu usuario de Mad Sports.",
                    "Tu cuenta", "Elegí una clave nueva", saludo(u),
                    List.of("Pediste cambiar la clave de tu usuario " + u.getUsuario()
                            + ". Elegí una nueva con el botón de abajo."),
                    "Elegir clave nueva", baseUrl + "/restablecer.html?token=" + token,
                    "El enlace vale 1 hora y se puede usar una sola vez. Si no lo pediste, ignorá este mail: "
                            + "tu clave sigue siendo la misma."));
        });
    }

    /**
     * Cambia la clave con el enlace del mail. También confirma el email (el enlace llegó a esa casilla).
     *
     * @throws IllegalArgumentException si el enlace venció, ya se usó o la clave es corta
     */
    @Transactional
    public void restablecerClave(String token, String clave) {
        if (clave == null || clave.length() < CLAVE_MINIMA || clave.length() > 72) {
            throw new IllegalArgumentException("La clave lleva entre 8 y 72 caracteres.");
        }
        TokenUsuario t = vigente(token, Tipo.RECUPERO).orElseThrow(() -> new IllegalArgumentException(
                "El enlace venció o ya se usó. Pedí uno nuevo desde \"¿Olvidaste tu clave?\"."));
        t.usar();
        t.getUsuario().cambiarClave(encoder.encode(clave));
        t.getUsuario().verificarEmail();
    }

    private Optional<Usuario> buscarPorEmail(String email) {
        return email == null || email.isBlank() ? Optional.empty() : usuarios.findByEmailIgnoreCase(email.trim());
    }

    private boolean recienEnviado(Usuario u, Tipo tipo) {
        return tokens.existsByUsuarioAndTipoAndCreadoAfter(u, tipo, clock.instant().minus(ESPERA_ENTRE_MAILS));
    }

    private String nuevoToken(Usuario u, Tipo tipo, Duration vigencia) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant ahora = clock.instant();
        tokens.save(new TokenUsuario(u, tipo, hash(token), ahora, ahora.plus(vigencia)));
        return token;
    }

    private Optional<TokenUsuario> vigente(String token, Tipo tipo) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return tokens.findByHashAndTipo(hash(token.trim()), tipo).filter(t -> t.vigente(clock.instant()));
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String saludo(Usuario u) {
        return u.getNombre() != null ? u.getNombre() : u.getUsuario();
    }
}
