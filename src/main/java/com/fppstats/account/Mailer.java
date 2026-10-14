package com.fppstats.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Envía mails por SMTP (sirve con cualquier proveedor: Resend, Brevo, Gmail...). Sin SMTP configurado
 * ({@code SMTP_HOST} vacío, como en desarrollo) el mail se escribe en el log, con su enlace, y no se envía.
 */
@Component
public class Mailer {

    private static final Logger log = LoggerFactory.getLogger(Mailer.class);

    private final ObjectProvider<JavaMailSender> sender;
    private final String host;
    private final String from;

    public Mailer(ObjectProvider<JavaMailSender> sender, @Value("${spring.mail.host:}") String host,
                  @Value("${fpp.mail.from}") String from) {
        this.sender = sender;
        this.host = host;
        this.from = from;
    }

    /** @return false si no se pudo enviar (el error queda en el log; el usuario puede pedir otro mail) */
    public boolean send(String to, String subject, String text) {
        JavaMailSender smtp = host == null || host.isBlank() ? null : sender.getIfAvailable();
        if (smtp == null) {
            log.info("Mail sin SMTP configurado (no se envía) para {}: {}\n{}", to, subject, text);
            return true;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(text);
            smtp.send(msg);
            return true;
        } catch (RuntimeException e) {
            log.error("No se pudo enviar el mail a {}: {}", to, e.getMessage());
            return false;
        }
    }
}
