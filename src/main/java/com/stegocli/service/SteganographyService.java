package com.stegocli.service;

import com.stegocli.crypto.CryptoService;
import com.stegocli.crypto.EncryptedPayload;
import com.stegocli.exception.InvalidInputException;
import com.stegocli.image.EncodingStrategy;
import com.stegocli.image.ImageProcessor;
import com.stegocli.image.StrategyRegistry;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Orchestrates the encode and decode use cases. It sequences the other services
 * but contains no
 * crypto, pixel, or I/O logic of its own, which keeps it small and easy to
 * unit-test with mocks.
 *
 * <p>
 * Any {@code StegoException} thrown by the layers below propagates untouched to
 * the CLI handler,
 * which maps it to an exit code.
 */
public final class SteganographyService {

    /**
     * Minimum accepted password length on encode (a light security floor; adjust to
     * taste).
     */
    static final int MIN_PASSWORD_LENGTH = 8;

    private final CryptoService crypto;
    private final StrategyRegistry registry;
    private final ImageProcessor processor;

    /** Production constructor — wires real collaborators. */
    public SteganographyService() {
        this(new CryptoService(), new StrategyRegistry(), new ImageProcessor());
    }

    /** Injectable constructor — used by tests to supply mocks. */
    public SteganographyService(CryptoService crypto, StrategyRegistry registry, ImageProcessor processor) {
        this.crypto = crypto;
        this.registry = registry;
        this.processor = processor;
    }

    public EncodeResult encode(EncodeRequest request) {
        validateEncode(request);
        long start = System.nanoTime();

        EncodingStrategy strategy = registry.resolve(request.algorithm());
        Path output = resolveOutput(request.input(), request.output());
        EncryptedPayload payload = crypto.encrypt(request.message(), request.password());
        long payloadBytes = processor.embed(request.input(), output, payload, strategy);

        return new EncodeResult(output, payloadBytes, millisSince(start));
    }

    public DecodeResult decode(DecodeRequest request) {
        validateDecode(request);
        long start = System.nanoTime();

        EncryptedPayload payload = processor.extract(request.input(), registry);
        String message = crypto.decrypt(payload, request.password());

        return new DecodeResult(message, millisSince(start));
    }

    // --- validation
    // -------------------------------------------------------------------------

    private void validateEncode(EncodeRequest req) {
        requireExistingFile(req.input());
        if (req.message() == null || req.message().isEmpty()) {
            throw new InvalidInputException("Message must not be empty.");
        }
        if (req.password() == null || req.password().length < MIN_PASSWORD_LENGTH) {
            throw new InvalidInputException(
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
    }

    private void validateDecode(DecodeRequest req) {
        requireExistingFile(req.input());
        if (req.password() == null || req.password().length == 0) {
            throw new InvalidInputException("Password must not be empty.");
        }
    }

    private void requireExistingFile(Path input) {
        if (input == null) {
            throw new InvalidInputException("No input file specified.");
        }
        if (!Files.isRegularFile(input)) {
            throw new InvalidInputException("Input file not found: " + input);
        }
    }

    // --- helpers
    // ----------------------------------------------------------------------------

    /**
     * Uses the caller's output path verbatim, or derives
     * {@code <input-stem>_encoded.png}.
     */
    private Path resolveOutput(Path input, Path output) {
        if (output != null) {
            return output;
        }
        String name = input.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = (dot > 0) ? name.substring(0, dot) : name;
        String derived = stem + "_encoded.png";
        Path parent = input.getParent();
        return (parent == null) ? Path.of(derived) : parent.resolve(derived);
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}