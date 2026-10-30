package com.madsports.source;

import java.io.IOException;
import java.io.UncheckedIOException;

/** La fuente de datos no respondió o respondió con error: corta el informe (salvo donde se sigue sin ese dato). */
public class SourceException extends UncheckedIOException {

    public SourceException(String message) {
        super(message, new IOException(message));
    }

    public SourceException(String message, Exception cause) {
        super(message, cause instanceof IOException io ? io : new IOException(cause));
    }
}
