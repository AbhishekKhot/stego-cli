package com.stegocli.image;

/**
 * Strategy for embedding and extracting raw bytes in the least-significant bits
 * of an image's
 * colour channels.
 *
 * <p>
 * A <em>channel</em> is a single R/G/B slot. Channels are visited R, G, B per
 * pixel, pixels in
 * row-major order, so channel index {@code ch} maps to pixel {@code ch / 3} and
 * component
 * {@code ch % 3} (0 = red, 1 = green, 2 = blue). The alpha channel is never
 * touched.
 *
 * <p>
 * Implementations are pure bit-twiddling: they know nothing about the payload
 * format. Framing
 * (magic, version, algorithm id, length, salt/iv/ciphertext) lives entirely in
 * {@code com.stegocli.payload.PayloadCodec}. The {@code startChannel} parameter
 * lets two different
 * runs coexist in one image — StegoCLI always writes its fixed header with
 * LSB-1 starting at channel
 * 0, then writes the body with the chosen strategy starting just after the
 * header.
 */
public interface EncodingStrategy {
    /** Numeric id stored in the payload header (1 = LSB-1, 2 = LSB-2). */
    int id();

    /** Number of payload bits stored per colour channel. */
    int bitsPerChannel();

    /**
     * Maximum bytes that {@code channels} channels can hold at this strategy's bit
     * depth.
     */
    long capacityBytes(long channels);

    /**
     * Writes every bit of {@code data} into channel LSBs, beginning at
     * {@code startChannel}.
     * Only the low {@link #bitsPerChannel()} bits of each touched channel are
     * modified.
     */
    void embed(int[] pixels, byte[] data, int startChannel);

    /**
     * Reads {@code byteCount} bytes from channel LSBs, beginning at
     * {@code startChannel}. Inverse of
     * {@link #embed}.
     */
    byte[] extract(int[] pixels, int byteCount, int startChannel);
}
