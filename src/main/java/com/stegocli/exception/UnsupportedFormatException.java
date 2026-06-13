package com.stegocli.exception;

/**
 * Thrown when an input file is not one of the supported lossless formats (PNG
 * or BMP).
 * Maps to exit code 1.
 */
public class UnsupportedFormatException extends StegoException {
    public UnsupportedFormatException(String message) {
        super(message);
    }
}
