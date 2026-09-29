package com.example.backupmanager;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class Compat {
    private Compat() {
    }

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @SafeVarargs
    static <T> List<T> listOf(T... values) {
        T[] copy = values.clone();
        for (T value : copy) Objects.requireNonNull(value);
        return Collections.unmodifiableList(Arrays.asList(copy));
    }

    @SafeVarargs
    static <T> Set<T> setOf(T... values) {
        LinkedHashSet<T> result = new LinkedHashSet<T>();
        for (T value : values) {
            if (!result.add(Objects.requireNonNull(value))) {
                throw new IllegalArgumentException("duplicate element");
            }
        }
        return Collections.unmodifiableSet(result);
    }

    @SuppressWarnings("unchecked")
    static <K, V> Map<K, V> mapOf(Object... entries) {
        if (entries.length % 2 != 0) throw new IllegalArgumentException("map entries must be key/value pairs");
        LinkedHashMap<K, V> result = new LinkedHashMap<K, V>();
        for (int index = 0; index < entries.length; index += 2) {
            K key = (K) Objects.requireNonNull(entries[index]);
            V value = (V) Objects.requireNonNull(entries[index + 1]);
            if (result.put(key, value) != null) throw new IllegalArgumentException("duplicate key");
        }
        return Collections.unmodifiableMap(result);
    }

    static String toHex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            result[index * 2] = digits[value >>> 4];
            result[index * 2 + 1] = digits[value & 0x0f];
        }
        return new String(result);
    }
}
