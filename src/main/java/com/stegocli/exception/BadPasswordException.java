package com.stegocli.exception;

/**
 * Thrown when AES-GCM decryption fails its authentication-tag check — meaning
 * the password is wrong
 * or the image was never encoded (the extracted bytes are not a valid payload).
 * Maps to exit code 1.
 */
public class BadPasswordException extends StegoException {
    public BadPasswordException(String message) {
        super(message);
    }
}
