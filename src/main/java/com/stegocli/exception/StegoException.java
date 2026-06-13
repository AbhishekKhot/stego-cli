package com.stegocli.exception;

/**
 * Base type for all StegoCLI failures.
 *
 * <p>
 * All project exceptions are unchecked so the service and processing layers
 * stay free of
 * {@code throws} clutter; a single handler at the CLI boundary maps each
 * concrete type to a
 * process exit code. Subclasses that map to exit code {@code 1} represent
 * user-correctable errors;
 * the base class and {@link ImageReadException} map to exit code {@code 2}
 * (internal/unexpected).
 */
public class StegoException extends RuntimeException {
    public StegoException(String message) {
        super(message);
    }

    public StegoException(String message, Throwable cause) {
        super(message, cause);
    }
}
