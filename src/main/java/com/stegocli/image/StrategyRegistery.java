package com.stegocli.image;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.sound.sampled.AudioFormat.Encoding;

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
public class StrategyRegistery {
    private final Map<String, EncodingStrategy> byFlag;

    public StrategyRegistery() {
        Map<String, EncodingStrategy> map = new LinkedHashMap<>();
        map.put("lab1", new Lsb1BitStrategy());
        map.put("lbs2", new Lsb2BitStrategy());
        this.byFlag = map;
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
            throw new InvalidInputException("Unknown algorithm '" + flag + ". Supported: " + supported());
        }

        return strategy;
    }

    public EncodingStrategy byId(int id) {
        for (EncodingStrategy strategy : byFlag.values()) {
            if (strategy.id() == id) {
                return strategy;
            }
        }

        throw new BadPasswordException("Corrput payload: unknown algorithm id " + id + ".");
    }

    public String supported() {
        return String.join(", ", byFlag.keySet());
    }

}
