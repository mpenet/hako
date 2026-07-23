package com.s_exp.hako;

import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM-global registry of record classes. Populated by the Clojure
 * `s-exp.hako.ext/register-record!` fn.
 */
public final class RecordRegistry {

    private static final ConcurrentHashMap<String, RecordInfo> BY_NAME = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, RecordInfo> BY_CLASS = new ConcurrentHashMap<>();

    private RecordRegistry() {}

    public static void put(RecordInfo info) {
        BY_NAME.put(info.className(), info);
        BY_CLASS.put(info.klass(), info);
    }

    public static RecordInfo byName(String name)   { return BY_NAME.get(name); }
    public static RecordInfo byClass(Class<?> k)   { return BY_CLASS.get(k); }
    public static boolean has(Class<?> k)          { return BY_CLASS.containsKey(k); }
}
