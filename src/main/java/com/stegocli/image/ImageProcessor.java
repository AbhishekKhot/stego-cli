// src/main/java/com/stegocli/image/ImageProcessor.java
package com.stegocli.image;

import com.stegocli.crypto.EncryptedPayload;
import com.stegocli.exception.BadPasswordException;
import com.stegocli.exception.CapacityExceededException;
import com.stegocli.exception.ImageReadeExcpetion;
import com.stegocli.exception.StegoException;
import com.stegocli.exception.UnsupportedFormatException;
import com.stegocli.payload.PayloadCodec;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Reads a cover image of any decodable format, embeds an encrypted payload into
 * pixel LSBs, and
 * writes the result as a lossless PNG — and performs the reverse for decoding.
 *
 * <p>
 * The fixed 8-byte header is always embedded with {@link Lsb1BitStrategy} in
 * the first
 * {@link #HEADER_CHANNELS} channels, so {@code decode} can read it without
 * knowing the body's bit
 * depth. The body (salt + IV + ciphertext) is embedded with the chosen strategy
 * in the channels
 * after the header.
 *
 * <p>
 * The output is always built on a truecolor canvas ({@code TYPE_INT_RGB}, or
 * {@code TYPE_INT_ARGB}
 * when the source has alpha) so that {@code setRGB} stores pixel values exactly
 * — this avoids palette
 * quantisation that would otherwise corrupt the LSBs for indexed inputs like
 * GIF. PNG is lossless, so
 * the embedded bits survive the write/read cycle intact. This class is
 * stateless and thread-safe.
 */
public final class ImageProcessor {

    private static final EncodingStrategy HEADER_STRATEGY = new Lsb1BitStrategy();
    private static final int HEADER_CHANNELS = PayloadCodec.HEADER_BYTES * 8; // 64 channels @ 1 bpc
    private static final int CHANNELS_PER_PIXEL = 3;

    /**
     * Embeds {@code payload} into {@code input} and writes the stego image to
     * {@code output} as PNG.
     *
     * @return the number of body bytes embedded (salt + IV + ciphertext)
     * @throws CapacityExceededException if the image is too small to hold the
     *                                   payload
     */
    public long embed(Path input, Path output, EncryptedPayload payload, EncodingStrategy strategy) {
        BufferedImage source = readImage(input);
        int width = source.getWidth();
        int height = source.getHeight();
        int[] pixels = source.getRGB(0, 0, width, height, null, 0, width);
        long totalChannels = (long) width * height * CHANNELS_PER_PIXEL;

        byte[] body = PayloadCodec.encodeBody(payload);
        byte[] header = PayloadCodec.encodeHeader(strategy.id(), body.length);

        long bodyCapacity = strategy.capacityBytes(totalChannels - HEADER_CHANNELS);
        if (body.length > bodyCapacity) {
            throw new CapacityExceededException(body.length, bodyCapacity);
        }

        HEADER_STRATEGY.embed(pixels, header, 0); // 8 bytes @ 1 bpc, channels [0, 64)
        strategy.embed(pixels, body, HEADER_CHANNELS); // body @ strategy bpc, from channel 64

        writePng(pixels, width, height, source.getColorModel().hasAlpha(), output);
        return body.length;
    }

    /**
     * Extracts and returns the encrypted payload embedded in {@code input}.
     *
     * @throws BadPasswordException if the image contains no StegoCLI payload or it
     *                              is corrupt
     */
    public EncryptedPayload extract(Path input, StrategyRegistry registry) {
        BufferedImage source = readImage(input);
        int width = source.getWidth();
        int height = source.getHeight();
        long totalChannels = (long) width * height * CHANNELS_PER_PIXEL;

        if (totalChannels < HEADER_CHANNELS) {
            throw new BadPasswordException(
                    "No StegoCLI message found in this image (it is too small to contain one).");
        }

        int[] pixels = source.getRGB(0, 0, width, height, null, 0, width);

        byte[] headerBytes = HEADER_STRATEGY.extract(pixels, PayloadCodec.HEADER_BYTES, 0);
        PayloadCodec.Header header = PayloadCodec.decodeHeader(headerBytes); // validates magic/version

        EncodingStrategy strategy = registry.byId(header.algorithmId());
        long bodyCapacity = strategy.capacityBytes(totalChannels - HEADER_CHANNELS);
        if (header.bodyLength() > bodyCapacity) {
            throw new BadPasswordException("Corrupt payload: declared length exceeds image capacity.");
        }

        byte[] body = strategy.extract(pixels, header.bodyLength(), HEADER_CHANNELS);
        return PayloadCodec.decodeBody(body);
    }

    private BufferedImage readImage(Path input) {
        try {
            BufferedImage image = ImageIO.read(input.toFile());
            if (image == null) {
                throw new UnsupportedFormatException(
                        "Unsupported or unrecognised image format: " + input.getFileName());
            }
            return image;
        } catch (IOException e) {
            throw new ImageReadeExcpetion("Could not read image: " + input.getFileName(), e);
        }
    }

    private void writePng(int[] pixels, int width, int height, boolean hasAlpha, Path output) {
        int type = hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage canvas = new BufferedImage(width, height, type);
        canvas.setRGB(0, 0, width, height, pixels, 0, width);
        try {
            if (!ImageIO.write(canvas, "png", output.toFile())) {
                throw new StegoException("No PNG writer is available to produce: " + output);
            }
        } catch (IOException e) {
            throw new StegoException("Failed to write output PNG: " + output, e);
        }
    }
}