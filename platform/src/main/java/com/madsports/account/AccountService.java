package com.madsports.account;

import com.madsports.account.UserToken.Kind;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
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
import java.util.Locale;
import java.util.Optional;

/**
 * Confirmación del email y recuperación de la clave con enlaces de un solo uso enviados por mail. El token va solo en
 * el enlace; en la base queda su hash. Para no revelar qué emails están registrados, pedir un enlace siempre
 * responde lo mismo, exista o no la cuenta.
 */
@Service
public class AccountService {

    static final Duration VERIFICATION_VALIDITY = Duration.ofHours(48);
    static final Duration RESET_VALIDITY = Duration.ofHours(1);
    /** Un mail del mismo tipo por minuto y por usuario, como mucho. */
    static final Duration MAIL_INTERVAL = Duration.ofMinutes(1);
    static final int MIN_PASSWORD_LENGTH = 8;

    private final UserAccountRepository users;
    private final UserTokenRepository tokens;
    private final PasswordEncoder encoder;
    private final Mailer mailer;
    private final Clock clock;
    private final PersistentTokenRepository remembered;
    private final String baseUrl;
    private final SecureRandom random = new SecureRandom();

    public AccountService(UserAccountRepository users, UserTokenRepository tokens, PasswordEncoder encoder,
                         Mailer mailer, Clock clock, PersistentTokenRepository remembered,
                         @Value("${madsports.mail.base-url}") String baseUrl) {
        this.users = users;
        this.tokens = tokens;
        this.encoder = encoder;
        this.mailer = mailer;
        this.clock = clock;
        this.remembered = remembered;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** Manda el enlace para confirmar el email (después del registro, o si lo pide de nuevo). */
    @Transactional
    public void sendVerification(UserAccount u) {
        if (u.isEmailVerified() || recentlySent(u, Kind.VERIFICATION)) {
            return;
        }
        String token = newToken(u, Kind.VERIFICATION, VERIFICATION_VALIDITY);
        Product p = u.getProduct();
        mailer.send(new MailContent(u.getEmail(), "Confirmá tu cuenta de " + p.displayName(),
                "Un paso más: confirmá tu email para empezar a usar " + p.displayName() + ".",
                "Tu cuenta", "Confirmá tu email", greetingOf(u),
                List.of("Gracias por sumarte a " + p.displayName() + ". Para activar tu cuenta (usuario " + u.getUsername()
                        + "), confirmá tu email con el botón de abajo.",
                        p == Product.METRICS ? "Después ya podés ingresar y registrar las estadísticas de tus partidos."
                                : "Después ya podés ingresar y ver tus informes de hockey sobre patines."),
                "Confirmar mi email", baseUrl + "/api/verify?token=" + token,
                "El enlace vale 48 horas. Si no creaste esta cuenta, ignorá este mail.", p));
    }

    /**
     * Vuelve a mandar la confirmación, si hay una cuenta sin confirmar con ese email (o ese usuario).
     *
     * @param producto el de la pantalla desde donde se pide (el email puede tener una cuenta en cada producto)
     */
    @Transactional
    public void resendVerification(String email, String username, Product product) {
        Optional<UserAccount> u = findByEmail(email, product);
        if (u.isEmpty() && username != null && !username.isBlank()) {
            u = users.findByUsername(username.trim().toLowerCase(java.util.Locale.ROOT));
        }
        u.filter(x -> !x.isEmailVerified()).ifPresent(this::sendVerification);
    }

    /** @return el producto de la cuenta si el enlace era válido y el email quedó confirmado; vacío si no */
    @Transactional
    public Optional<Product> verify(String token) {
        Optional<UserToken> t = isValid(token, Kind.VERIFICATION);
        t.ifPresent(x -> {
            x.use();
            x.getUser().verifyEmail();
        });
        return t.map(x -> x.getUser().getProduct());
    }

    /**
     * "Olvidé mi clave": manda el enlace para elegir una nueva, si existe una cuenta con ese email.
     *
     * @param producto el de la pantalla desde donde se pide (el email puede tener una cuenta en cada producto)
     */
    @Transactional
    public void forgotPassword(String email, Product product) {
        findByEmail(email, product).filter(u -> !recentlySent(u, Kind.PASSWORD_RESET)).ifPresent(u -> {
            String token = newToken(u, Kind.PASSWORD_RESET, RESET_VALIDITY);
            Product p = u.getProduct();
            mailer.send(new MailContent(u.getEmail(), "Elegí una clave nueva para " + p.displayName(),
                    "Pediste cambiar la clave de tu usuario de " + p.displayName() + ".",
                    "Tu cuenta", "Elegí una clave nueva", greetingOf(u),
                    List.of("Pediste cambiar la clave de tu usuario " + u.getUsername()
                            + ". Elegí una nueva con el botón de abajo."),
                    "Elegir clave nueva", baseUrl + "/reset-password.html?product=" + p.name().toLowerCase(Locale.ROOT)
                            + "&token=" + token,
                    "El enlace vale 1 hora y se puede usar una sola vez. Si no lo pediste, ignorá este mail: "
                            + "tu clave sigue siendo la misma.", p));
        });
    }

    /**
     * Cambia la clave con el enlace del mail. También confirma el email (el enlace llegó a esa casilla) y deja de
     * recordar los dispositivos donde se había ingresado con "Recordarme": hay que volver a ingresar con la clave nueva.
     *
     * @throws IllegalArgumentException si el enlace venció, ya se usó o la clave es corta
     */
    @Transactional
    public void resetPassword(String token, String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH || password.length() > 72) {
            throw new IllegalArgumentException("La clave lleva entre 8 y 72 caracteres.");
        }
        UserToken t = isValid(token, Kind.PASSWORD_RESET).orElseThrow(() -> new IllegalArgumentException(
                "El enlace venció o ya se usó. Pedí uno nuevo desde \"¿Olvidaste tu clave?\"."));
        t.use();
        t.getUser().changePassword(encoder.encode(password));
        t.getUser().verifyEmail();
        remembered.removeUserTokens(t.getUser().getUsername());
    }

    /** La cuenta con ese email en el producto; sin producto, la primera que haya (la más antigua). */
    private Optional<UserAccount> findByEmail(String email, Product product) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return product != null ? users.findByEmailIgnoreCaseAndProduct(email.trim(), product)
                : users.findByEmailIgnoreCaseOrderByIdAsc(email.trim()).stream().findFirst();
    }

    private boolean recentlySent(UserAccount u, Kind kind) {
        return tokens.existsByUserAndKindAndCreatedAtAfter(u, kind, clock.instant().minus(MAIL_INTERVAL));
    }

    private String newToken(UserAccount u, Kind kind, Duration validity) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        tokens.save(new UserToken(u, kind, hash(token), now, now.plus(validity)));
        return token;
    }

    private Optional<UserToken> isValid(String token, Kind kind) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return tokens.findByHashAndKind(hash(token.trim()), kind).filter(t -> t.isValid(clock.instant()));
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String greetingOf(UserAccount u) {
        return u.getName() != null ? u.getName() : u.getUsername();
    }
}
