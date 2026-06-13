// src/main/java/com/stegocli/payload/PayloadCodec.java
package com.stegocli.payload;

import com.stegocli.crypto.EncryptedPayload;
import com.stegocli.exception.BadPasswordException;
import com.stegocli.exception.StegoException;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Serialises the bytes that get embedded into an image, and parses them back
 * out.
 *
 * <p>
 * The embedded data is split into two parts that are written into the image
 * differently:
 *
 * <pre>
 *   HEADER (fixed 8 bytes, always embedded at 1 bit/channel)
 *     [0..1]  magic  = 'S','G'      identifies a StegoCLI payload
 *     [2]     version                payload format version
 *     [3]     algorithmId            1 = LSB-1, 2 = LSB-2  (how the BODY was embedded)
 *     [4..7]  bodyLength             big-endian int: number of body bytes that follow
 *
 *   BODY (bodyLength bytes, embedded at the algorithm's bit depth)
 *     [0..15]   salt                 16 bytes
 *     [16..27]  iv                   12 bytes
 *     [28..]    ciphertext + tag     remainder (AES-256-GCM output)
 * </pre>
 *
 * <p>
 * Keeping the header at a fixed 1-bit depth lets {@code decode} read it without
 * first knowing the
 * algorithm, then switch to the indicated depth for the body. The magic bytes
 * let {@code decode}
 * recognise an image that was never encoded and fail cleanly instead of
 * misreading random bits.
 *
 * <p>
 * This class is pure (no I/O, no image awareness) and therefore fully
 * unit-testable.
 */
public final class PayloadCodec {

    static final byte MAGIC_0 = (byte) 'S';
    static final byte MAGIC_1 = (byte) 'G';

    /** Current payload format version. */
    public static final byte VERSION = 1;

    /**
     * Fixed header size: magic(2) + version(1) + algorithmId(1) + bodyLength(4).
     */
    public static final int HEADER_BYTES = 8;

    // Body field sizes — must match CryptoService's salt/IV lengths.
    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 12;

    private PayloadCodec() {
    }

    /** A parsed, validated header. */
    public record Header(int version, int algorithmId, int bodyLength) {
    }

    /** Builds the fixed 8-byte header. */
    public static byte[] encodeHeader(int algorithmId, int bodyLength) {
        if (algorithmId < 0 || algorithmId > 0xFF) {
            throw new StegoException("algorithmId out of range: " + algorithmId);
        }
        if (bodyLength < 0) {
            throw new StegoException("bodyLength must be non-negative: " + bodyLength);
        }
        return ByteBuffer.allocate(HEADER_BYTES)
                .put(MAGIC_0)
                .put(MAGIC_1)
                .put(VERSION)
                .put((byte) algorithmId)
                .putInt(bodyLength)
                .array();
    }

    /**
     * Parses and validates the fixed 8-byte header.
     *
     * @throws BadPasswordException if the magic bytes are absent (image not encoded
     *                              by this tool)
     * @throws StegoException       if the version is not supported
     */
    public static Header decodeHeader(byte[] header) {
        if (header == null || header.length < HEADER_BYTES) {
            throw new StegoException("Header buffer too small: " + (header == null ? 0 : header.length));
        }
        ByteBuffer buf = ByteBuffer.wrap(header, 0, HEADER_BYTES);
        byte m0 = buf.get();
        byte m1 = buf.get();
        if (m0 != MAGIC_0 || m1 != MAGIC_1) {
            throw new BadPasswordException(
                    "No StegoCLI message found in this image "
                            + "(it may not have been encoded, or this is not the encoded output).");
        }
        int version = Byte.toUnsignedInt(buf.get());
        if (version != VERSION) {
            throw new StegoException("Unsupported payload version: " + version);
        }
        int algorithmId = Byte.toUnsignedInt(buf.get());
        int bodyLength = buf.getInt();
        if (bodyLength < 0) {
            throw new BadPasswordException("Corrupt payload header (negative body length).");
        }
        return new Header(version, algorithmId, bodyLength);
    }

    /** Concatenates {@code salt | iv | ciphertext} into the body byte array. */
    public static byte[] encodeBody(EncryptedPayload payload) {
        return ByteBuffer.allocate(
                payload.salt().length + payload.iv().length + payload.ciphertext().length)
                .put(payload.salt())
                .put(payload.iv())
                .put(payload.ciphertext())
                .array();
    }

    /**
     * Splits a body byte array back into {@code salt | iv | ciphertext}.
     *
     * @throws BadPasswordException if the body is too short to contain salt + IV +
     *                              a GCM tag
     */
    public static EncryptedPayload decodeBody(byte[] body) {
        // Minimum valid body = salt + iv + 16-byte GCM tag (ciphertext of an empty
        // message).
        if (body == null || body.length < SALT_LEN + IV_LEN + 16) {
            throw new BadPasswordException("Corrupt or truncated payload (body too short).");
        }
        byte[] salt = Arrays.copyOfRange(body, 0, SALT_LEN);
        byte[] iv = Arrays.copyOfRange(body, SALT_LEN, SALT_LEN + IV_LEN);
        byte[] ciphertext = Arrays.copyOfRange(body, SALT_LEN + IV_LEN, body.length);
        return new EncryptedPayload(salt, iv, ciphertext);
    }
}