package com.stegocli.exception;

public class CapacityExceededException extends StegoException {
    private final long requiredBytes;
    private final long availableBytes;

    public CapacityExceededException(long requiredBytes, long availableBytes) {
        super(String.format("Image too small to hold the payload. Required: %d bytes, available: %d bytes.",
                requiredBytes, availableBytes));
        this.requiredBytes = requiredBytes;
        this.availableBytes = availableBytes;
    }

    public long requiredBytes() {
        return requiredBytes;
    }

    public long availableBytes() {
        return availableBytes;
    }
}
