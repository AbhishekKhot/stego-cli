// src/main/java/com/stegocli/service/EncodeResult.java
package com.stegocli.service;

import java.nio.file.Path;

/**
 * Outcome of a successful encode.
 *
 * @param output         path the stego PNG was written to
 * @param payloadBytes   number of body bytes embedded (salt + IV + ciphertext)
 * @param durationMillis wall-clock time for the operation
 */
public record EncodeResult(Path output, long payloadBytes, long durationMillis) {
}