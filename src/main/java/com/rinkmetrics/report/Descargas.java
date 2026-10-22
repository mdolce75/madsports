package com.rinkmetrics.report;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

/** Las respuestas con un informe: para ver en pantalla (HTML) o para bajar (PDF o Excel). */
public final class Descargas {

    /** Tipo de los archivos de Excel (.xlsx). */
    public static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private Descargas() {
    }

    /** El informe para ver en pantalla (la página lo muestra dentro de un marco). */
    public static ResponseEntity<String> html(String html) {
        return ResponseEntity.ok().contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8)).body(html);
    }

    /** Un archivo para bajar (PDF o Excel); {@code inline} lo abre el navegador en lugar de descargarlo. */
    public static ResponseEntity<byte[]> file(byte[] body, MediaType type, String fileName, boolean inline) {
        ContentDisposition.Builder disposition = inline ? ContentDisposition.inline() : ContentDisposition.attachment();
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.filename(fileName).build().toString())
                .body(body);
    }
}
