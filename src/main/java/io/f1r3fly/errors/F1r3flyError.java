package io.f1r3fly.errors;

public class F1r3flyError extends Exception {
    public F1r3flyError(String message) {
        super(message);
    }

    public F1r3flyError(String message, Throwable cause) {
        super(message, cause);
    }
}
