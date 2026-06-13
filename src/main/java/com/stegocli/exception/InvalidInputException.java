package com.stegocli.exception;

/**
 * Thrown for invalid user input that is not a format problem — e.g. an empty
 * message, a missing
 * file, a password below the minimum length, or an unknown {@code --algorithm}
 * value.
 * Maps to exit code 1.
 */
public class InvalidInputException extends StegoException {
    public InvalidInputException(String message) {
        super(message);
    }
}
