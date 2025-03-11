package io.f1r3fly.errors;

public class Blake2Exception extends RuntimeException {
    public Blake2Exception(String message) {
        super(message);
    }

    public Blake2Exception(String message, Throwable cause) {
        super(message, cause);
    }
}
