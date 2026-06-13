// src/main/java/com/stegocli/crypto/CryptoService.java
package com.stegocli.crypto;

import com.stegocli.exception.BadPasswordException;
import com.stegocli.exception.StegoException;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Authenticated encryption for message payloads.
 *
 * <p>
 * The user password is never used directly as a key. For each operation a fresh
 * random salt and
 * IV are generated; the salt feeds PBKDF2WithHmacSHA256
 * ({@value #PBKDF2_ITERATIONS} iterations) to
 * derive a 256-bit AES key, and the message is sealed with AES-256 in GCM mode.
 * GCM provides both
 * confidentiality and integrity: on decryption the authentication tag is
 * verified automatically, so
 * a wrong password (which derives a wrong key) surfaces as a tag failure rather
 * than as garbage
 * plaintext. That failure is mapped to {@link BadPasswordException}.
 *
 * <p>
 * This class is stateless apart from a {@link SecureRandom} and is safe to
 * reuse.
 */
public final class CryptoService {

    static final int SALT_LENGTH_BYTES = 16;
    static final int IV_LENGTH_BYTES = 12; // recommended GCM nonce length
    static final int KEY_LENGTH_BITS = 256;
    static final int PBKDF2_ITERATIONS = 65_536;
    static final int GCM_TAG_LENGTH_BITS = 128;

    private static final String KDF_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String CIPHER_TRANSFORM = "AES/GCM/NoPadding";

    private final SecureRandom random = new SecureRandom();

    /**
     * Encrypts {@code plaintext} under a key derived from {@code password}.
     *
     * @return a payload containing the salt, IV, and ciphertext (with appended GCM
     *         tag)
     */
    public EncryptedPayload encrypt(String plaintext, char[] password) {
        if (plaintext == null) {
            throw new StegoException("Plaintext must not be null");
        }
        byte[] salt = randomBytes(SALT_LENGTH_BYTES);
        byte[] iv = randomBytes(IV_LENGTH_BYTES);
        try {
            SecretKey key = deriveKey(password, salt);
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new EncryptedPayload(salt, iv, ciphertext);
        } catch (GeneralSecurityException e) {
            // Provider/availability/init failure — a broken JVM crypto config, not user
            // error.
            throw new StegoException("Encryption failed: " + e.getMessage(), e);
        }
    }

    /**
     * Decrypts a payload under a key re-derived from {@code password} and the
     * payload's own salt.
     *
     * @throws BadPasswordException if the GCM authentication tag does not verify
     */
    public String decrypt(EncryptedPayload payload, char[] password) {
        if (payload == null) {
            throw new StegoException("Payload must not be null");
        }
        try {
            SecretKey key = deriveKey(password, payload.salt());
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, payload.iv()));
            byte[] plaintext = cipher.doFinal(payload.ciphertext());
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new BadPasswordException(
                    "Decryption failed. Incorrect password or image has not been encoded.");
        } catch (GeneralSecurityException e) {
            throw new StegoException("Decryption failed: " + e.getMessage(), e);
        }
    }

    private SecretKey deriveKey(char[] password, byte[] salt) throws GeneralSecurityException {
        if (password == null || password.length == 0) {
            throw new StegoException("Password must not be empty");
        }
        PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(KDF_ALGORITHM);
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            try {
                // SecretKeySpec copies the bytes into its own array; we then wipe our copy.
                return new SecretKeySpec(keyBytes, "AES");
            } finally {
                Arrays.fill(keyBytes, (byte) 0);
            }
        } finally {
            spec.clearPassword();
        }
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }
}