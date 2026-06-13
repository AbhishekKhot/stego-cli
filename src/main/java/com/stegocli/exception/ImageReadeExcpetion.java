package com.stegocli.exception;

/**
 * Thrown when a file exists and has a supported extension but cannot be decoded
 * as an image
 * (corrupt, truncated, or mislabelled). Maps to exit code 2
 * (internal/unexpected).
 */
public class ImageReadeExcpetion extends StegoException {
    public ImageReadeExcpetion(String message) {
        super(message);
    }

    public ImageReadeExcpetion(String message, Throwable cause) {
        super(message, cause);
    }
}
