package com.ledgerguard.common;

import java.util.LinkedHashMap;
import java.util.Map;

/** Ordered map literal that, unlike Map.of, allows null values (e.g. an optional description). */
public final class Maps {

    private Maps() {}

    public static Map<String, Object> of(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Expected key/value pairs");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
