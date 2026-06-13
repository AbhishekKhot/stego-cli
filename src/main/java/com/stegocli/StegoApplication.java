// src/main/java/com/stegocli/StegoApplication.java
package com.stegocli;

import com.stegocli.cli.DecodeCommand;
import com.stegocli.cli.EncodeCommand;
import com.stegocli.cli.StegExceptionHandler;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Root command and process entry point.
 *
 * <p>
 * Wires the {@code encode} and {@code decode} subcommands. Running {@code steg}
 * with no
 * subcommand prints usage. Exit-code mapping for typed exceptions will be
 * installed here via a
 * dedicated execution-exception handler once the exception hierarchy is wired
 * up.
 */
@Command(name = "steg", mixinStandardHelpOptions = true, version = "StegoCLI 1.0", description = "Hide AES-256-GCM-encrypted text inside images (any format in, lossless PNG out).", subcommands = {
        EncodeCommand.class, DecodeCommand.class })
public final class StegoApplication implements Runnable {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new StegoApplication())
                .setExecutionExceptionHandler(new StegExceptionHandler())
                .execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        // No subcommand provided -> show usage.
        CommandLine.usage(this, System.out);
    }
}