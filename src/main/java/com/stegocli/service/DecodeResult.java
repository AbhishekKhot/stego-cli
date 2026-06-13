package com.stegocli.service;

/**
 * Outcome of a successful decode.
 *
 * @param message        the recovered plaintext
 * @param durationMillis wall-clock time for the operation
 */
public record DecodeResult(String message, long durationMillis) {
}