package com.fppstats.account;

import java.util.List;

/**
 * Un mail de la cuenta (confirmar el email, elegir una clave nueva): se arma en HTML con la identidad de la web
 * ({@code templates/mail/cuenta.html}) y en texto plano para los clientes que no muestran HTML.
 *
 * @param preheader  resumen que muestran las bandejas de entrada junto al asunto
 * @param tag        etiqueta naranja ("Tu cuenta")
 * @param greeting   a quién se saluda ("Hola {greeting}:")
 * @param paragraphs cuerpo del mail
 * @param button     texto del botón, que abre {@code url}
 * @param note       aclaración al pie (vencimiento, "si no lo pediste...")
 */
public record MailContent(String to, String subject, String preheader, String tag, String title, String greeting,
                          List<String> paragraphs, String button, String url, String note) {

    /** Versión en texto plano, con el enlace completo. */
    public String text() {
        StringBuilder sb = new StringBuilder("Hola ").append(greeting).append(":\n\n");
        paragraphs.forEach(p -> sb.append(p).append("\n\n"));
        sb.append(button).append(": ").append(url).append("\n\n");
        sb.append(note).append("\n\n");
        sb.append("Mad Sports · Estadísticas de hockey sobre patines\n");
        return sb.toString();
    }
}
