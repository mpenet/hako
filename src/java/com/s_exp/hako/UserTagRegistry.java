package com.s_exp.hako;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM-global registry of classes that have a user-tag registered.
 * Backs the Writer's hot-path "is this class a user-tag?" check.
 *
 * <p>Populated by the Clojure-side {@code register-user-tag!} at
 * registration time. The lookup uses a {@link ClassValue} so a class
 * that has been checked once resolves in a single field read on
 * subsequent encodes — same intrinsic cost as a type-tag branch.
 */
public final class UserTagRegistry {

    private UserTagRegistry() {}

    private static final Set<Class<?>> CLASSES = ConcurrentHashMap.newKeySet();

    private static final ClassValue<Boolean> CACHE = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            return CLASSES.contains(type);
        }
    };

    /**
     * Mark {@code klass} as user-tag-registered. Invalidates any
     * previously cached lookup for the same class.
     */
    public static void add(Class<?> klass) {
        CLASSES.add(klass);
        CACHE.remove(klass);
    }

    /**
     * Fast lookup — {@code true} iff {@code klass} has a user-tag
     * registration. First call per class hits the underlying
     * {@link ConcurrentHashMap} (~5 ns); subsequent calls resolve
     * against a {@link ClassValue} slot (~1 ns).
     */
    public static boolean has(Class<?> klass) {
        return CACHE.get(klass).booleanValue();
    }
}
