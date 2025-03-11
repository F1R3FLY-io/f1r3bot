package io.f1r3fly.errors;

import org.jetbrains.annotations.NotNull;

public class F1r3flyDeployError extends F1r3flyError {
    public F1r3flyDeployError(String rawRho, String message) {
        super("Failed to deploy Rholang expression: '%s'. Error: %s".formatted(truncateRhoIfTooLong(rawRho), message));
    }

    private static @NotNull String truncateRhoIfTooLong(String rawRho) {
        return rawRho.length() > 100 ? rawRho.substring(0, 100) + "..." : rawRho;
    }

    public F1r3flyDeployError(String rawRho, String message, Throwable cause) {
        super("Failed to deploy Rholang expression: '%s'. Error: %s".formatted(truncateRhoIfTooLong(rawRho), message), cause);
    }
}
