package com.stegocli.crypto;

/**
 * Immutable carrier for an encrypted message: the random {@code salt} used for
 * key derivation, the
 * random {@code iv} (GCM nonce), and the {@code ciphertext} (which includes the
 * trailing 16-byte
 * GCM authentication tag).
 *
 * <p>
 * All three are needed to decrypt, and none is secret — they are serialised
 * into the embedded
 * payload alongside the ciphertext. Array components are referenced directly
 * (no defensive copy);
 * instances are treated as effectively immutable and are never shared across
 * mutating callers.
 */
public record EncryptedPayload(byte[] salt, byte[] iv, byte[] ciphertext) {
    public EncryptedPayload {
        if (salt == null || iv == null || ciphertext == null) {
            throw new IllegalArgumentException("salt, iv, and ciphertext must all be non-null");
        }
    }
}
