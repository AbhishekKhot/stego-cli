// src/main/java/com/stegocli/cli/EncodeCommand.java
package com.stegocli.cli;

import com.stegocli.service.EncodeRequest;
import com.stegocli.service.EncodeResult;
import com.stegocli.service.SteganographyService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Callable;

/**
 * {@code steg encode} — hide an encrypted message inside a cover image.
 */
@Command(name = "encode", description = "Hide an encrypted message inside a cover image.")
public final class EncodeCommand implements Callable<Integer> {

    @Option(names = { "-i",
            "--input" }, required = true, description = "Path to cover image (any readable format: PNG, JPEG, BMP, GIF, ...).")
    Path input;

    @Option(names = { "-m", "--message" }, required = true, description = "Secret message to embed.")
    String message;

    @Option(names = { "-p",
            "--password" }, required = true, interactive = true, description = "Encryption password (prompted; input hidden).")
    char[] password;

    @Option(names = { "-o",
            "--output" }, description = "Output path; always written as lossless PNG (default: <input>_encoded.png).")
    Path output;

    @Option(names = { "-a",
            "--algorithm" }, defaultValue = "lsb1", description = "Encoding algorithm: lsb1 (default), lsb2.")
    String algorithm;

    @Option(names = { "-v", "--verbose" }, description = "Detailed processing logs.")
    boolean verbose;

    private final SteganographyService service;

    public EncodeCommand() {
        this(new SteganographyService());
    }

    EncodeCommand(SteganographyService service) {
        this.service = service;
    }

    @Override
    public Integer call() {
        try {
            EncodeResult result = service.encode(new EncodeRequest(input, output, message, password, algorithm));
            System.out.println("Encoded successfully.");
            System.out.println("  Output : " + result.output());
            System.out.println("  Payload: " + result.payloadBytes() + " bytes");
            System.out.println("  Time   : " + result.durationMillis() + " ms");
            return 0;
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0'); // zero the secret as soon as we are done with it
            }
        }
    }
}