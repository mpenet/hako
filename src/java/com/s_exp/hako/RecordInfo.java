package com.s_exp.hako;

import clojure.lang.Keyword;
import java.lang.invoke.MethodHandle;

/**
 * Cached reflection for one registered record class.
 *
 * `fieldKeywords` is populated for Clojure defrecords (keys used by
 * {@code (get x kw)}); `accessorMHs` is populated for Java records
 * (accessor MethodHandles adapted to {@code (Object) -> Object}).
 * The other array is null.
 */
public record RecordInfo(
    Class<?> klass,
    String className,
    int fieldCount,
    boolean javaRecord,
    Keyword[] fieldKeywords,
    MethodHandle[] accessorMHs,
    MethodHandle ctorMH
) {}
