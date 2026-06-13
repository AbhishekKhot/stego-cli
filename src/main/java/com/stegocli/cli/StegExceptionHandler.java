// src/main/java/com/stegocli/cli/StegExceptionHandler.java
package com.stegocli.cli;

import com.stegocli.exception.BadPasswordException;
import com.stegocli.exception.CapacityExceededException;
import com.stegocli.exception.InvalidInputException;
import com.stegocli.exception.StegoException;
import com.stegocli.exception.UnsupportedFormatException;
import picocli.CommandLine;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.ParseResult;

/**
 * Translates exceptions thrown while running a subcommand into a clean
 * {@code ERROR:} message and a
 * structured process exit code:
 *
 * <ul>
 * <li><b>1</b> — user-correctable errors: bad password, insufficient capacity,
 * unsupported format,
 * invalid input.</li>
 * <li><b>2</b> — internal/unexpected errors: unreadable image, base
 * {@link StegoException}, or any
 * non-StegoCLI exception.</li>
 * </ul>
 *
 * Commands therefore throw freely and never call {@code System.exit}
 * themselves.
 */
public final class StegExceptionHandler implements IExecutionExceptionHandler {

    @Override
    public int handleExecutionException(Exception ex, CommandLine cmd, ParseResult parseResult) {
        if (ex instanceof BadPasswordException
                || ex instanceof CapacityExceededException
                || ex instanceof UnsupportedFormatException
                || ex instanceof InvalidInputException) {
            cmd.getErr().println("ERROR: " + ex.getMessage());
            return 1;
        }
        if (ex instanceof StegoException) { // ImageReadException + base StegoException
            cmd.getErr().println("ERROR: " + ex.getMessage());
            return 2;
        }
        cmd.getErr().println("INTERNAL ERROR: " + ex);
        return 2;
    }
}