// src/main/java/com/stegocli/cli/DecodeCommand.java
package com.stegocli.cli;

import com.stegocli.service.DecodeRequest;
import com.stegocli.service.DecodeResult;
import com.stegocli.service.SteganographyService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Callable;

/**
 * {@code steg decode} — extract and decrypt a hidden message from an encoded
 * image.
 *
 * <p>
 * The recovered message is written to stdout with no decoration so it can be
 * piped or redirected;
 * timing and any verbose detail go to stderr.
 */
@Command(name = "decode", description = "Extract and decrypt a hidden message from an encoded image.")
public final class DecodeCommand implements Callable<Integer> {

    @Option(names = { "-i",
            "--input" }, required = true, description = "Path to the encoded PNG image produced by 'encode'.")
    Path input;

    @Option(names = { "-p",
            "--password" }, required = true, interactive = true, description = "Decryption password (prompted; input hidden).")
    char[] password;

    @Option(names = { "-v", "--verbose" }, description = "Detailed processing logs.")
    boolean verbose;

    private final SteganographyService service;

    public DecodeCommand() {
        this(new SteganographyService());
    }

    DecodeCommand(SteganographyService service) {
        this.service = service;
    }

    @Override
    public Integer call() {
        try {
            DecodeResult result = service.decode(new DecodeRequest(input, password));
            System.out.println(result.message()); // clean stdout: pipeable
            if (verbose) {
                System.err.println("Decoded in " + result.durationMillis() + " ms");
            }
            return 0;
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }
}