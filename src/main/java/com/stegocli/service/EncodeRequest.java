package com.stegocli.service;

import java.nio.file.Path;

/**
 * Inputs for an encode operation.
 *
 * @param input     cover image path (any readable format)
 * @param output    desired output path, or {@code null} to derive
 *                  {@code <input-stem>_encoded.png}
 * @param message   plaintext secret to embed
 * @param password  encryption password (caller is responsible for zeroing it
 *                  afterwards)
 * @param algorithm algorithm flag, e.g. {@code "lsb1"} / {@code "lsb2"}
 */
public record EncodeRequest(Path input, Path output, String message, char[] password, String algorithm) {
}