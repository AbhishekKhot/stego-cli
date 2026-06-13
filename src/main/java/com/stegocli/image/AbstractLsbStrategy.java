package com.stegocli.image;

import com.stegocli.exception.StegoException;

/**
 * Shared LSB bit-packing for the concrete strategies. Subclasses supply only
 * {@link #id()} and
 * {@link #bitsPerChannel()}; this base implements the embed/extract loops
 * parameterised by that
 * bit depth.
 *
 * <p>
 * <strong>Bit order.</strong> Data is treated as an MSB-first bit stream: byte
 * 0 first, and
 * within a byte bit 7 down to bit 0. Each channel stores the next
 * {@code bitsPerChannel} bits in its
 * low bits, the first bit read being the most significant of the group. Because
 * a byte is 8 bits and
 * {@code bitsPerChannel} is 1 or 2, groups always divide evenly — there is
 * never a partial group.
 */
public abstract class AbstractLsbStrategy implements EncodingStrategy {
    private static final int CHANNEL_PER_PIXEL = 3; // R, G, B (alpha untouched)

    @Override
    public long capacityBytes(long channels) {
        if (channels <= 0) {
            return 0;
        }

        return channels * bitsPerChannel();
    }

    @Override
    public void embed(int[] pixels, byte[] data, int startChannel) {
        final int bpc = bitsPerChannel();
        final int lowMask = (1 << bpc) - 1;
        final int totalChannels = pixels.length * CHANNEL_PER_PIXEL;
        final int dataBits = data.length * 8;
        final int channelsNeed = (dataBits + bpc - 1) / bpc;

        if (startChannel <= 0 || startChannel + channelsNeed > totalChannels) {
            throw new StegoException(
                    "Not enough image channels to embed " + data.length + " bytes at channel offset " + startChannel);
        }

        int bitsPos = 0;
        int ch = startChannel;
        while (bitsPos < dataBits) {
            int value = 0;
            for (int j = 0; j < bpc && bitsPos < dataBits; j++) {
                int bit = (data[bitsPos >> 3] >> (7 - (bitsPos & 7))) & 1;
                value = (value << 1) | bit;
                bitsPos++;
            }
            int pixelIndex = ch / CHANNEL_PER_PIXEL;
            int shift = 16 - (ch % CHANNEL_PER_PIXEL);
            int argb = pixels[pixelIndex];
            int channelByte = (argb >> shift) & 0xFF;
            channelByte = (channelByte & ~lowMask) | (value & lowMask);
            pixels[pixelIndex] = (argb & (~0xFF << shift) | (channelByte << shift));
            ch++;
        }
    }

    @Override
    public byte[] extract(int[] pixels, int byteCount, int startChannel) {
        final int bpc = bitsPerChannel();
        final int lowMask = (1 << bpc) - 1;
        final int totalChannels = pixels.length * CHANNEL_PER_PIXEL;
        final int totalBits = byteCount * 8;
        final int channelNeeded = (totalBits + bpc - 1) / bpc;

        if (byteCount < 0 || startChannel < 0 || startChannel + channelNeeded > totalChannels) {
            throw new StegoException(
                    "Not enough image channels to extract " + byteCount + " bytes at channel offset " + startChannel);
        }

        byte[] out = new byte[byteCount];
        int bitPos = 0;
        int ch = startChannel;
        while (bitPos < totalBits) {
            int pixelIndex = ch / CHANNEL_PER_PIXEL;
            int shift = 16 - (ch % CHANNEL_PER_PIXEL);
            int value = (pixels[pixelIndex] >> shift) & lowMask;
            for (int j = 0; j < bpc && bitPos < totalBits; j++) {
                int bit = (value >> (bpc - 1 - j) & 1);
                out[bitPos >> 3] |= (bit << (7 - bitPos & 7));
                bitPos++;
            }
            ch++;
        }

        return out;
    }
}
