package com.stegocli.service;

import java.nio.file.Path;

/**
 * Inputs for a decode operation.
 *
 * @param input    encoded PNG path
 * @param password decryption password (caller is responsible for zeroing it
 *                 afterwards)
 */
public record DecodeRequest(Path input, char[] password) {
}