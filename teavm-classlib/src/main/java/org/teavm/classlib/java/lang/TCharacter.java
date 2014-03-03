/*
 *  Copyright 2014 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.classlib.java.lang;

import org.teavm.classlib.impl.charset.UTF16Helper;
import org.teavm.classlib.impl.unicode.UnicodeHelper;
import org.teavm.dependency.PluggableDependency;
import org.teavm.javascript.ni.GeneratedBy;
import org.teavm.javascript.ni.Rename;

/**
 *
 * @author Alexey Andreev
 */
public class TCharacter extends TObject {
    public static final int MIN_RADIX = 2;
    public static final int MAX_RADIX = 36;
    public static final char MIN_VALUE = '\0';
    public static final char MAX_VALUE = '\uFFFF';
    public static final TClass<TCharacter> TYPE = TClass.charClass();
    public static final byte UNASSIGNED = 0;
    public static final byte UPPERCASE_LETTER = 1;
    public static final byte LOWERCASE_LETTER = 2;
    public static final byte TITLECASE_LETTER = 3;
    public static final byte MODIFIER_LETTER = 4;
    public static final byte OTHER_LETTER = 5;
    public static final byte NON_SPACING_MARK = 6;
    public static final byte ENCLOSING_MARK = 7;
    public static final byte COMBINING_SPACING_MARK = 8;
    public static final byte DECIMAL_DIGIT_NUMBER = 9;
    public static final byte LETTER_NUMBER = 10;
    public static final byte OTHER_NUMBER = 11;
    public static final byte SPACE_SEPARATOR = 12;
    public static final byte LINE_SEPARATOR = 13;
    public static final byte PARAGRAPH_SEPARATOR = 14;
    public static final byte CONTROL = 15;
    public static final byte FORMAT = 16;
    public static final byte PRIVATE_USE = 17;
    public static final byte SURROGATE = 19;
    public static final byte DASH_PUNCTUATION = 20;
    public static final byte START_PUNCTUATION = 21;
    public static final byte END_PUNCTUATION = 22;
    public static final byte CONNECTOR_PUNCTUATION = 23;
    public static final byte OTHER_PUNCTUATION = 24;
    public static final byte MATH_SYMBOL = 25;
    public static final byte CURRENCY_SYMBOL = 26;
    public static final byte MODIFIER_SYMBOL = 27;
    public static final byte OTHER_SYMBOL = 28;
    public static final byte INITIAL_QUOTE_PUNCTUATION = 29;
    public static final byte FINAL_QUOTE_PUNCTUATION = 30;
    public static final byte DIRECTIONALITY_UNDEFINED = -1;
    public static final byte DIRECTIONALITY_LEFT_TO_RIGHT = 0;
    public static final byte DIRECTIONALITY_RIGHT_TO_LEFT = 1;
    public static final byte DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC = 2;
    public static final byte DIRECTIONALITY_EUROPEAN_NUMBER = 3;
    public static final byte DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR = 4;
    public static final byte DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR = 5;
    public static final byte DIRECTIONALITY_ARABIC_NUMBER = 6;
    public static final byte DIRECTIONALITY_COMMON_NUMBER_SEPARATOR = 7;
    public static final byte DIRECTIONALITY_NONSPACING_MARK = 8;
    public static final byte DIRECTIONALITY_BOUNDARY_NEUTRAL = 9;
    public static final byte DIRECTIONALITY_PARAGRAPH_SEPARATOR = 10;
    public static final byte DIRECTIONALITY_SEGMENT_SEPARATOR = 11;
    public static final byte DIRECTIONALITY_WHITESPACE = 12;
    public static final byte DIRECTIONALITY_OTHER_NEUTRALS = 13;
    public static final byte DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING = 14;
    public static final byte DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE = 15;
    public static final byte DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING = 16;
    public static final byte DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE = 17;
    public static final byte DIRECTIONALITY_POP_DIRECTIONAL_FORMAT = 18;
    public static final char MIN_HIGH_SURROGATE = '\uD800';
    public static final char MAX_HIGH_SURROGATE = '\uDBFF';
    public static final char MIN_LOW_SURROGATE  = '\uDC00';
    public static final char MAX_LOW_SURROGATE  = '\uDFFF';
    public static final char MIN_SURROGATE = MIN_HIGH_SURROGATE;
    public static final char MAX_SURROGATE = MAX_LOW_SURROGATE;
    public static final int MIN_SUPPLEMENTARY_CODE_POINT = 0x010000;
    public static final int MIN_CODE_POINT = 0x000000;
    public static final int MAX_CODE_POINT = 0X10FFFF;
    public static final int SIZE = 16;
    static final int ERROR = 0xFFFFFFFF;
    private static int[] digitMapping;
    private static byte[] classMapping;
    private char value;
    private static TCharacter[] characterCache = new TCharacter[128];

    public TCharacter(char value) {
        this.value = value;
    }

    public char charValue() {
        return value;
    }

    public static TCharacter valueOf(char value) {
        if (value < characterCache.length) {
            TCharacter result = characterCache[value];
            if (result == null) {
                result = new TCharacter(value);
                characterCache[value] = result;
            }
            return result;
        }
        return new TCharacter(value);
    }

    @Override
    @Rename("toString")
    public TString toString0() {
        return toString(value);
    }

    @Override
    public boolean equals(TObject other) {
        if (this == other) {
            return true;
        }
        return other instanceof TCharacter && ((TCharacter)other).value == value;
    }

    @Override
    public int hashCode() {
        return value;
    }

    public static TString toString(char c) {
        return new TString(new char[] { c });
    }

    public static boolean isValidCodePoint(int codePoint) {
        return codePoint >= 0 && codePoint <= MAX_CODE_POINT;
    }

    public static boolean isBmpCodePoint(int codePoint) {
        return codePoint > 0 && codePoint <= MAX_VALUE;
    }

    public static boolean isSupplementaryCodePoint(int codePoint) {
        return codePoint >= MIN_SUPPLEMENTARY_CODE_POINT && codePoint <= MAX_CODE_POINT;
    }

    public static boolean isHighSurrogate(char ch) {
        return UTF16Helper.isHighSurrogate(ch);
    }

    public static boolean isLowSurrogate(char ch) {
        return UTF16Helper.isLowSurrogate(ch);
    }

    public static boolean isSurrogate(char ch) {
        return isHighSurrogate(ch) || isLowSurrogate(ch);
    }

    public static boolean isSurrogatePair(char high, char low) {
        return isHighSurrogate(high) && isLowSurrogate(low);
    }

    public static int charCount(int codePoint) {
        return isSupplementaryCodePoint(codePoint) ? 2 : 1;
    }

    public static int toCodePoint(char high, char low) {
        return UTF16Helper.buildCodePoint(high, low);
    }

    public static int codePointAt(TCharSequence seq, int index) {
        if (index >= seq.length() - 1 || !isHighSurrogate(seq.charAt(index)) ||
                !isLowSurrogate(seq.charAt(index + 1))) {
            return seq.charAt(index);
        } else {
            return toCodePoint(seq.charAt(index), seq.charAt(index + 1));
        }
    }

    public static int codePointAt(char[] a, int index) {
        return codePointAt(a, index, a.length);
    }

    public static int codePointAt(char[] a, int index, int limit) {
        if (index >= limit - 1 || !isHighSurrogate(a[index]) || !isLowSurrogate(a[index + 1])) {
            return a[index];
        } else {
            return toCodePoint(a[index], a[index + 1]);
        }
    }

    public static int codePointBefore(TCharSequence seq, int index) {
        if (index == 1 || !UTF16Helper.isLowSurrogate(seq.charAt(index - 2)) ||
                !UTF16Helper.isHighSurrogate(seq.charAt(index - 2))) {
            return seq.charAt(index - 1);
        }
        return UTF16Helper.buildCodePoint(seq.charAt(index - 2), seq.charAt(index - 1));
    }

    public static int codePointBefore(char[] a, int index) {
        return codePointBefore(a, index, 0);
    }

    public static int codePointBefore(char[] a, int index, int start) {
        if (index <= start + 1 || !isLowSurrogate(a[index - 1]) || !isHighSurrogate(a[index - 2])) {
            return a[index];
        } else {
            return toCodePoint(a[index - 2], a[index - 1]);
        }
    }

    public static char highSurrogate(int codePoint) {
        return UTF16Helper.highSurrogate(codePoint);
    }

    public static char lowSurrogate(int codePoint) {
        return UTF16Helper.lowSurrogate(codePoint);
    }

    @GeneratedBy(CharacterNativeGenerator.class)
    public static native char toLowerCase(char ch);

    @GeneratedBy(CharacterNativeGenerator.class)
    public static native int toLowerCase(int ch);

    public static int digit(char ch, int radix) {
        return digit((int)ch, radix);
    }

    public static int digit(int codePoint, int radix) {
        if (radix < MIN_RADIX || radix > MAX_RADIX) {
            return -1;
        }
        int d = digit(codePoint);
        return d <= radix ? d : -1;
    }

    static int digit(int codePoint) {
        int[] digitMapping = getDigitMapping();
        int l = 0;
        int u = (digitMapping.length / 2) - 1;
        while (u >= l) {
            int idx = (l + u) / 2;
            int val = digitMapping[idx * 2];
            if (codePoint > val) {
                l = idx + 1;
            } else if (codePoint < val) {
                u = idx - 1;
            } else {
                return digitMapping[idx * 2 + 1];
            }
        }
        return -1;
    }

    public static char forDigit(int digit, int radix) {
        if (radix < MIN_RADIX || radix > MAX_RADIX || digit >= radix) {
            return '\0';
        }
        return digit < 10 ? (char)('0' + digit) : (char)('a' + digit - 10);
    }

    public static boolean isDigit(char ch) {
        return isDigit((int)ch);
    }

    public static boolean isDigit(int codePoint) {
        return digit(codePoint) >= 0;
    }

    private static int[] getDigitMapping() {
        if (digitMapping == null) {
            digitMapping = UnicodeHelper.decodeIntByte(obtainDigitMapping());
        }
        return digitMapping;
    }

    @GeneratedBy(CharacterNativeGenerator.class)
    @PluggableDependency(CharacterNativeGenerator.class)
    private static native String obtainDigitMapping();

    private static byte[] getClasses() {
        if (classMapping == null) {
            classMapping = UnicodeHelper.extractRle(obtainClasses());
        }
        return classMapping;
    }

    @GeneratedBy(CharacterNativeGenerator.class)
    @PluggableDependency(CharacterNativeGenerator.class)
    private static native String obtainClasses();

    public static int toChars(int codePoint, char[] dst, int dstIndex) {
        if (codePoint >= UTF16Helper.SUPPLEMENTARY_PLANE) {
            dst[dstIndex] = UTF16Helper.highSurrogate(codePoint);
            dst[dstIndex + 1] = UTF16Helper.lowSurrogate(codePoint);
            return 2;
        } else {
            dst[dstIndex] = (char)codePoint;
            return 1;
        }
    }

    public static char[] toChars(int codePoint) {
        if (codePoint >= UTF16Helper.SUPPLEMENTARY_PLANE) {
            return new char[] { UTF16Helper.highSurrogate(codePoint), UTF16Helper.lowSurrogate(codePoint) };
        } else {
            return new char[] { (char)codePoint };
        }
    }

    public static int codePointCount(TCharSequence seq, int beginIndex, int endIndex) {
        int count = endIndex;
        --endIndex;
        for (int i = beginIndex; i < endIndex; ++i) {
            if (UTF16Helper.isHighSurrogate(seq.charAt(i)) && UTF16Helper.isLowSurrogate(seq.charAt(i + 1))) {
                --count;
                ++i;
            }
        }
        return count;
    }

    public static int codePointCount(char[] a, int offset, int count) {
        int r = count;
        --count;
        for (int i = 0; i < count; ++i) {
            if (UTF16Helper.isHighSurrogate(a[offset]) && UTF16Helper.isLowSurrogate(a[offset + i + 1])) {
                --r;
                ++i;
            }
        }
        return r;
    }

    public static int offsetByCodePoints(TCharSequence seq, int index, int codePointOffset) {
        for (int i = 0; i < codePointOffset; ++i) {
            if (index < seq.length() - 1 && UTF16Helper.isHighSurrogate(seq.charAt(index)) &&
                    UTF16Helper.isLowSurrogate(seq.charAt(index + 1))) {
                index += 2;
            } else {
                index++;
            }
        }
        return index;
    }

    public static int offsetByCodePoints(char[] a, int start, int count, int index, int codePointOffset) {
        for (int i = 0; i < codePointOffset; ++i) {
            if (index < count - 1 && UTF16Helper.isHighSurrogate(a[index + start]) &&
                    UTF16Helper.isLowSurrogate(a[index + start + 1])) {
                index += 2;
            } else {
                index++;
            }
        }
        return index;
    }

    public static boolean isISOControl(char ch) {
        return isISOControl((int)ch);
    }

    public static boolean isISOControl(int codePoint) {
        return codePoint >= 0 && codePoint <= 0x1F || codePoint >= 0x7F && codePoint <= 0x9F;
    }

    public static int getType(char c) {
        return getType((int)c);
    }

    public static int getType(int codePoint) {
        return getClasses()[codePoint];
    }
}