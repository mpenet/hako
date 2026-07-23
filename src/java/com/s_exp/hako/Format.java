package com.s_exp.hako;

import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * Hako binary format — constants and static helpers.
 * See SPEC.md.
 */
public final class Format {

    public static final byte MAGIC_0 = 0x48; // 'H'
    public static final byte MAGIC_1 = 0x41; // 'A'
    public static final byte MAGIC_2 = 0x4B; // 'K'
    public static final byte MAGIC_3 = 0x4F; // 'O'
    public static final byte VERSION = 0x00;

    public static final int M_UINT = 0x00;
    public static final int M_SINT = 0x10;
    public static final int M_FLOAT = 0x20;
    public static final int M_BYTES = 0x30;
    public static final int M_STRING = 0x40;
    public static final int M_KW = 0x50;
    public static final int M_SYM = 0x60;
    public static final int M_VEC = 0x70;
    public static final int M_LIST = 0x80;
    public static final int M_SET = 0x90;
    public static final int M_MAP = 0xA0;
    public static final int M_SYMREF = 0xC0;
    public static final int M_BIGNUM = 0xD0;
    public static final int M_EXT = 0xE0;
    public static final int M_SPEC = 0xF0;

    public static final int TIER_INLINE_MAX = 11;
    public static final int TIER_U8 = 12;
    public static final int TIER_U16 = 13;
    public static final int TIER_U32 = 14;
    public static final int TIER_U64 = 15;

    public static final int FLOAT_F32 = 0;
    public static final int FLOAT_F64 = 1;

    public static final int SPEC_NIL = 0;
    public static final int SPEC_TRUE = 1;
    public static final int SPEC_FALSE = 2;
    public static final int SPEC_NAN = 3;
    public static final int SPEC_PINF = 4;
    public static final int SPEC_NINF = 5;
    public static final int SPEC_UUID = 6;
    public static final int SPEC_INST = 7;
    public static final int SPEC_CHAR = 8;

    public static final int BIG_BIGINT = 0;
    public static final int BIG_BIGDEC = 1;
    public static final int BIG_RATIO = 2;

    public static final int EXT_SORTED_SET = 0;
    public static final int EXT_SORTED_MAP = 1;
    public static final int EXT_QUEUE = 2;
    public static final int EXT_RECORD = 3;
    public static final int EXT_WITH_META = 4;
    public static final int EXT_PRIM_LONGS = 5;
    public static final int EXT_PRIM_DOUBLES = 6;
    public static final int EXT_USER_TAG = 15;

    public static final ValueLayout.OfShort LE_SHORT =
        ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    public static final ValueLayout.OfInt LE_INT =
        ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    public static final ValueLayout.OfLong LE_LONG =
        ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    public static final ValueLayout.OfFloat LE_FLOAT =
        ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    public static final ValueLayout.OfDouble LE_DOUBLE =
        ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private Format() {}

    public static int tag(int major, int low) {
        return major | (low & 0x0F);
    }

    public static int majorOf(int tagByte) {
        return tagByte & 0xF0;
    }

    public static int lowOf(int tagByte) {
        return tagByte & 0x0F;
    }

    public static int tierCode(long n) {
        if (Long.compareUnsigned(n, TIER_INLINE_MAX) <= 0) return (int) n;
        if (Long.compareUnsigned(n, 0xFFL) <= 0) return TIER_U8;
        if (Long.compareUnsigned(n, 0xFFFFL) <= 0) return TIER_U16;
        if (Long.compareUnsigned(n, 0xFFFFFFFFL) <= 0) return TIER_U32;
        return TIER_U64;
    }

    public static long zigZagEncode(long n) {
        return (n << 1) ^ (n >> 63);
    }

    public static long zigZagDecode(long z) {
        return (z >>> 1) ^ -(z & 1);
    }
}
