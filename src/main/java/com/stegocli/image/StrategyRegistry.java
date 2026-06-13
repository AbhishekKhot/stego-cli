// src/main/java/com/stegocli/image/StrategyRegistry.java
package com.stegocli.image;

import com.stegocli.exception.BadPasswordException;
import com.stegocli.exception.InvalidInputException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Central lookup for the available {@link EncodingStrategy} implementations.
 *
 * <p>
 * Two resolution paths:
 * <ul>
 * <li><b>encode</b> — by the {@code --algorithm} CLI flag ({@code lsb1} /
 * {@code lsb2}).</li>
 * <li><b>decode</b> — by the numeric algorithm id read from the payload header,
 * so the image is
 * self-describing and {@code decode} needs no algorithm flag.</li>
 * </ul>
 *
 * Adding a new algorithm means registering it here once; nothing else changes.
 */
public final class StrategyRegistry {

    private final Map<String, EncodingStrategy> byFlag;

    public StrategyRegistry() {
        Map<String, EncodingStrategy> map = new LinkedHashMap<>();
        map.put("lsb1", new Lsb1BitStrategy());
        map.put("lsb2", new Lsb2BitStrategy());
        this.byFlag = Collections.unmodifiableMap(map);
    }

    /**
     * Resolves the {@code --algorithm} flag (encode path).
     *
     * @throws InvalidInputException if the flag is null or not a known algorithm
     */
    public EncodingStrategy resolve(String flag) {
        if (flag == null) {
            throw new InvalidInputException("No algorithm specified. Supported: " + supported());
        }
        EncodingStrategy strategy = byFlag.get(flag.toLowerCase(Locale.ROOT));
        if (strategy == null) {
            throw new InvalidInputException(
                    "Unknown algorithm '" + flag + "'. Supported: " + supported());
        }
        return strategy;
    }

    /**
     * Resolves the algorithm id stored in a decoded payload header (decode path).
     *
     * @throws BadPasswordException if no registered strategy has that id
     *                              (corrupt/foreign payload)
     */
    public EncodingStrategy byId(int id) {
        for (EncodingStrategy strategy : byFlag.values()) {
            if (strategy.id() == id) {
                return strategy;
            }
        }
        throw new BadPasswordException("Corrupt payload: unknown algorithm id " + id + ".");
    }

    /** Comma-separated list of supported flags, for help and error messages. */
    public String supported() {
        return String.join(", ", byFlag.keySet());
    }
}