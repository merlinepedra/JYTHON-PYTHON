// Copyright (c) Jython Developers
package org.python.core.stringlib;

import org.python.core.Py;
import org.python.core.PyException;

public class InternalFormat {

    /**
     * Create a {@link Spec} object by parsing a format specification.
     *
     * @param text to parse
     * @return parsed equivalent to text
     */
    public static Spec fromText(String text) {
        Parser parser = new Parser(text);
        return parser.parse();
    }

    /**
     * A class that provides the base for implementations of type-specific formatting. In a limited
     * way, it acts like a StringBuilder to which text and one or more numbers may be appended,
     * formatted according to the format specifier supplied at construction. These are ephemeral
     * objects that are not, on their own, thread safe.
     */
    public static class Formatter implements Appendable {

        /** The specification according to which we format any number supplied to the method. */
        protected final Spec spec;
        /** The (partial) result. */
        protected StringBuilder result;

        /** The number we are working on floats at the end of the result, and starts here. */
        protected int start;
        /** If it contains no sign, this length is zero, and 1 otherwise. */
        protected int lenSign;
        /** The length of the whole part (to left of the decimal point or exponent) */
        protected int lenWhole;

        /**
         * Construct the formatter from a specification and initial buffer capacity. A reference is
         * held to this specification, but it will not be modified by the actions of this class.
         *
         * @param spec parsed conversion specification
         * @param width of buffer initially
         */
        public Formatter(Spec spec, int width) {
            this.spec = spec;
            result = new StringBuilder(width);
        }

        /**
         * Current (possibly final) result of the formatting, as a <code>String</code>.
         *
         * @return formatted result
         */
        public String getResult() {
            return result.toString();
        }

        /*
         * Implement Appendable interface by delegation to the result buffer.
         *
         * @see java.lang.Appendable#append(char)
         */
        @Override
        public Formatter append(char c) {
            result.append(c);
            return this;
        }

        @Override
        public Formatter append(CharSequence csq) {
            result.append(csq);
            return this;
        }

        @Override
        public Formatter append(CharSequence csq, int start, int end) //
                throws IndexOutOfBoundsException {
            result.append(csq, start, end);
            return this;
        }

        /**
         * Clear the instance variables describing the latest object in {@link #result}, ready to
         * receive a new number
         */
        public void setStart() {
            // Mark the end of the buffer as the start of the current object and reset all.
            start = result.length();
            // Clear the variable describing the latest number in result.
            reset();
        }

        /**
         * Clear the instance variables describing the latest object in {@link #result}, ready to
         * receive a new one.
         */
        protected void reset() {
            // Clear the variable describing the latest object in result.
            lenSign = lenWhole = 0;
        }

        /**
         * Supports {@link #toString()} by returning the lengths of the successive sections in the
         * result buffer, used for navigation relative to {@link #start}. The <code>toString</code>
         * method shows a '|' character between each section when it prints out the buffer. Override
         * this when you define more lengths in the subclass.
         *
         * @return
         */
        protected int[] sectionLengths() {
            return new int[] {lenSign, lenWhole};
        }

        /**
         * {@inheritDoc}
         * <p>
         * Overridden to provide a debugging view in which the actual text is shown divided up by
         * the <code>len*</code> member variables. If the dividers don't look right, those variables
         * have not remained consistent with the text.
         */
        @Override
        public String toString() {
            if (result == null) {
                return ("[]");
            } else {
                StringBuilder buf = new StringBuilder(result.length() + 20);
                buf.append(result);
                try {
                    int p = start;
                    buf.insert(p++, '[');
                    for (int len : sectionLengths()) {
                        p += len;
                        buf.insert(p++, '|');
                    }
                    buf.setCharAt(p - 1, ']');
                } catch (IndexOutOfBoundsException e) {
                    // Some length took us beyond the end of the result buffer. Pass.
                }
                return buf.toString();
            }
        }

        /**
         * Insert grouping characters (conventionally commas) into the whole part of the number.
         * {@link #lenWhole} will increase correspondingly.
         *
         * @param groupSize normally 3.
         * @param comma or some other character to use as a separator.
         */
        protected void groupDigits(int groupSize, char comma) {

            // Work out how many commas (or whatever) it takes to group the whole-number part.
            int commasNeeded = (lenWhole - 1) / groupSize;

            if (commasNeeded > 0) {
                // Index *just after* the current last digit of the whole part of the number.
                int from = start + lenSign + lenWhole;
                // Open a space into which the whole part will expand.
                makeSpaceAt(from, commasNeeded);
                // Index *just after* the end of that space.
                int to = from + commasNeeded;
                // The whole part will be longer by the number of commas to be inserted.
                lenWhole += commasNeeded;

                /*
                 * Now working from high to low, copy all the digits that have to move. Each pass
                 * copies one group and inserts a comma, which makes the to-pointer move one place
                 * extra. The to-pointer descends upon the from-pointer from the right.
                 */
                while (to > from) {
                    // Copy a group
                    for (int i = 0; i < groupSize; i++) {
                        result.setCharAt(--to, result.charAt(--from));
                    }
                    // Write the comma that precedes it.
                    result.setCharAt(--to, comma);
                }
            }
        }

        /**
         * Make a space in {@link #result} of a certain size and position. On return, the segment
         * lengths are likely to be invalid until the caller adjusts them corresponding to the
         * insertion. There is no guarantee what the opened space contains.
         *
         * @param pos at which to make the space
         * @param size of the space
         */
        protected void makeSpaceAt(int pos, int size) {
            int n = result.length();
            if (pos < n) {
                // Space is not at the end: must copy what's to the right of pos.
                String tail = result.substring(pos);
                result.setLength(n + size);
                result.replace(pos + size, n + size, tail);
            } else {
                // Space is at the end.
                result.setLength(n + size);
            }
        }

        /**
         * Convert letters in the representation of the current number (in {@link #result}) to upper
         * case.
         */
        protected void uppercase() {
            int end = result.length();
            for (int i = start; i < end; i++) {
                char c = result.charAt(i);
                result.setCharAt(i, Character.toUpperCase(c));
            }
        }

        /**
         * Pad the result so far (defined as the entire contents of {@link #result}) using the
         * alignment, target width and fill character defined in {@link #spec}. The action of
         * padding will increase the overall length of the result to the target width, if that is
         * greater than the current length.
         * <p>
         * When the padding method has decided that that it needs to add n padding characters, it
         * will affect {@link #start} or {@link #lenSign} as follows.
         * <table border style>
         * <tr>
         * <th>align</th>
         * <th>meaning</th>
         * <th>start</th>
         * <th>lenSign</th>
         * <th>result.length()</th>
         * </tr>
         * <tr>
         * <th>&lt;</th>
         * <td>left-aligned</td>
         * <td>+0</td>
         * <td>+0</td>
         * <td>+n</td>
         * </tr>
         * <tr>
         * <th>></th>
         * <td>right-aligned</td>
         * <td>+n</td>
         * <td>+0</td>
         * <td>+n</td>
         * </tr>
         * <tr>
         * <th>^</th>
         * <td>centred</td>
         * <td>+(n/2)</td>
         * <td>+0</td>
         * <td>+n</td>
         * </tr>
         * <tr>
         * <th>=</th>
         * <td>pad after sign</td>
         * <td>+0</td>
         * <td>+n</td>
         * <td>+n</td>
         * </tr>
         * </table>
         * Note that we may have converted more than one value into the result buffer (for example
         * when formatting a complex number). The pointer <code>start</code> is at the start of the
         * last number converted. Padding with zeros, and the "pad after sign" mode, will produce a
         * result you probably don't want. It is up to the client to disallow this (which
         * <code>complex</code> does).
         *
         * @param value to pad
         * @return this object
         */
        public Formatter pad() {

            // We'll need this many pad characters (if>0). Note Spec.UNDEFINED<0.
            int n = spec.width - result.length();
            if (n > 0) {

                char align = spec.getAlign('>'); // Right for numbers (wrong for strings)
                char fill = spec.getFill(' ');

                // Start by assuming padding is all leading ('>' case or '=')
                int leading = n;

                // Split the total padding according to the alignment
                if (align == '^') {
                    // Half the padding before
                    leading = n / 2;
                } else if (align == '<') {
                    // All the padding after
                    leading = 0;
                }

                // All padding that is not leading is trailing
                int trailing = n - leading;

                // Insert the leading space
                if (leading > 0) {
                    int pos;
                    if (align == '=') {
                        // Incorporate into the (latest) whole part
                        pos = start + lenSign;
                        lenWhole += leading;
                    } else {
                        // Insert at the very beginning (not start) by default.
                        pos = 0;
                        start += leading;
                    }
                    makeSpaceAt(pos, leading);
                    for (int i = 0; i < leading; i++) {
                        result.setCharAt(pos + i, fill);
                    }
                }

                // Append the trailing space
                for (int i = 0; i < trailing; i++) {
                    result.append(fill);
                }

                // Check for special case
                if (align == '=' && fill == '0' && spec.grouping) {
                    // We must extend the grouping separator into the padding
                    zeroPadAfterSignWithGroupingFixup(3, ',');
                }
            }

            return this;
        }

        /**
         * Fix-up the zero-padding of the last formatted number in {@link #result()} in the special
         * case where a sign-aware padding (<code>{@link #spec}.align='='</code>) was requested, the
         * fill character is <code>'0'</code>, and the digits are to be grouped. In these exact
         * circumstances, the grouping, which must already have been applied to the (whole part)
         * number itself, has to be extended into the zero-padding.
         *
         * <pre>
         * &gt;&gt;&gt; format(-12e8, " =30,.3f")
         * '-            1,200,000,000.000'
         * &gt;&gt;&gt; format(-12e8, "*=30,.3f")
         * '-************1,200,000,000.000'
         * &gt;&gt;&gt; format(-12e8, "*&gt;30,.3f")
         * '************-1,200,000,000.000'
         * &gt;&gt;&gt; format(-12e8, "0&gt;30,.3f")
         * '000000000000-1,200,000,000.000'
         * &gt;&gt;&gt; format(-12e8, "0=30,.3f")
         * '-0,000,000,001,200,000,000.000'
         * </pre>
         *
         * The padding has increased the overall length of the result to the target width. About one
         * in three call to this method adds one to the width, because the whole part cannot start
         * with a comma.
         *
         * <pre>
         * &gt;&gt;&gt; format(-12e8, " =30,.4f")
         * '-           1,200,000,000.0000'
         * &gt;&gt;&gt; format(-12e8, "0=30,.4f")
         * '-<b>0</b>,000,000,001,200,000,000.0000'
         * </pre>
         *
         * Insert grouping characters (conventionally commas) into the whole part of the number.
         * {@link #lenWhole} will increase correspondingly.
         *
         * @param groupSize normally 3.
         * @param comma or some other character to use as a separator.
         */
        protected void zeroPadAfterSignWithGroupingFixup(int groupSize, char comma) {
            /*
             * Suppose the format call was format(-12e8, "0=30,.3f"). At this point, we have
             * something like this in result: .. [-|0000000000001,200,000,000|.|000||]
             *
             * All we need do is over-write some of the zeros with the separator comma, in the
             * portion marked as the whole-part: [-|0,000,000,001,200,000,000|.|000||]
             */

            // First digit of the whole-part.
            int firstZero = start + lenSign;
            // One beyond last digit of the whole-part.
            int p = firstZero + lenWhole;
            // Step back down the result array visiting the commas. (Easiest to do all of them.)
            int step = groupSize + 1;
            for (p = p - step; p >= firstZero; p -= step) {
                result.setCharAt(p, comma);
            }

            // Sometimes the last write was exactly at the first padding zero.
            if (p + step == firstZero) {
                /*
                 * Suppose the format call was format(-12e8, "0=30,.4f"). At the beginning, we had
                 * something like this in result: . [-|000000000001,200,000,000|.|0000||]
                 *
                 * And now, result looks like this: [-|0000,000,001,200,000,000|.|0000||] in which
                 * the first zero is wrong as it stands, nor can it just be over-written with a
                 * comma. We have to insert another zero, even though this makes the result longer
                 * than we were given.
                 */
                result.insert(firstZero, '0');
                lenWhole += 1;
            }
        }

        /**
         * Convenience method returning a {@link Py#ValueError} reporting:
         * <p>
         * <code>"Unknown format code '"+code+"' for object of type '"+forType+"'"</code>
         *
         * @param code the presentation type
         * @param forType the type it was found applied to
         * @return exception to throw
         */
        public static PyException unknownFormat(char code, String forType) {
            String msg = "Unknown format code '" + code + "' for object of type '" + forType + "'";
            return Py.ValueError(msg);
        }

        /**
         * Convenience method returning a {@link Py#ValueError} reporting that alternate form is not
         * allowed in a format specifier for the named type.
         *
         * @param forType the type it was found applied to
         * @return exception to throw
         */
        public static PyException alternateFormNotAllowed(String forType) {
            return notAllowed("Alternate form (#)", forType);
        }

        /**
         * Convenience method returning a {@link Py#ValueError} reporting that the given alignment
         * flag is not allowed in a format specifier for the named type.
         *
         * @param align type of alignment
         * @param forType the type it was found applied to
         * @return exception to throw
         */
        public static PyException alignmentNotAllowed(char align, String forType) {
            return notAllowed("'" + align + "' alignment flag", forType);
        }

        /**
         * Convenience method returning a {@link Py#ValueError} reporting that zero padding is not
         * allowed in a format specifier for the named type.
         *
         * @param forType the type it was found applied to
         * @return exception to throw
         */
        public static PyException zeroPaddingNotAllowed(String forType) {
            return notAllowed("Zero padding", forType);
        }

        /**
         * Convenience method returning a {@link Py#ValueError} reporting that some format specifier
         * feature is not allowed for the named type.
         *
         * @param particularOutrage committed in the present case
         * @param forType the type it where it is an outrage
         * @return exception to throw
         */
        protected static PyException notAllowed(String particularOutrage, String forType) {
            String msg = particularOutrage + " is not allowed in " + forType + " format specifier";
            return Py.ValueError(msg);
        }

    }

    /**
     * Parsed PEP-3101 format specification of a single field, encapsulating the format for use by
     * formatting methods. This class holds the several attributes that might be decoded from a
     * format specifier. Each attribute has a reserved value used to indicate "unspecified".
     * <code>Spec</code> objects may be merged such that one <code>Spec</code> provides values,
     * during the construction of a new <code>Spec</code>, for attributes that are unspecified in a
     * primary source.
     * <p>
     * This structure is returned by factory method {@link #fromText(CharSequence)}, and having
     * public final members is freely accessed by formatters such as {@link FloatBuilder}, and the
     * __format__ methods of client object types.
     * <p>
     * The fields correspond to the elements of a format specification. The grammar of a format
     * specification is:
     *
     * <pre>
     * [[fill]align][sign][#][0][width][,][.precision][type]
     * </pre>
     *
     * A typical idiom is:
     *
     * <pre>
     *     private static final InternalFormatSpec FLOAT_DEFAULT = InternalFormatSpec.from(">");
     *     ...
     *         InternalFormatSpec spec = InternalFormatSpec.from(specString, FLOAT_DEFAULT);
     *         ... // Validation of spec.type, and other attributes, for this type.
     *         FloatBuilder buf = new FloatBuilder(spec);
     *         buf.format(value);
     *         String result = buf.getResult();
     *
     * </pre>
     */
    public static class Spec {

        /** The fill character specified, or '\uffff' if unspecified. */
        public final char fill;
        /**
         * Alignment indicator is one of {<code>'&lt;', '^', '>', '='</code>, or '\uffff' if
         * unspecified.
         */
        public final char align;
        /**
         * Sign-handling flag, one of <code>'+'</code>, <code>'-'</code>, or <code>' '</code>, or
         * '\uffff' if unspecified.
         */
        public final char sign;
        /** The alternative format flag '#' was given. */
        public final boolean alternate;
        /** Width to which to pad the result, or -1 if unspecified. */
        public final int width;
        /** Insert the grouping separator (which in Python always indicates a group-size of 3). */
        public final boolean grouping;
        /** Precision decoded from the format, or -1 if unspecified. */
        public final int precision;
        /** Type key from the format, or '\uffff' if unspecified. */
        public final char type;

        /** Non-character code point used to represent "no value" in <code>char</code> attributes. */
        public static final char NONE = '\uffff';
        /** Negative value used to represent "no value" in <code>int</code> attributes. */
        public static final int UNSPECIFIED = -1;

        /**
         * Test to see if an attribute has been specified.
         *
         * @param c attribute
         * @return true only if the attribute is not equal to {@link #NONE}
         */
        public static final boolean specified(char c) {
            return c != NONE;
        }

        /**
         * Test to see if an attribute has been specified.
         *
         * @param value of attribute
         * @return true only if the attribute is >=0 (meaning that it has been specified).
         */
        public static final boolean specified(int value) {
            return value >= 0;
        }

        /**
         * Constructor to set all the fields in the format specifier.
         *
         * <pre>
         * [[fill]align][sign][#][0][width][,][.precision][type]
         * </pre>
         *
         * @param fill fill character (or {@link #NONE}
         * @param align alignment indicator, one of {<code>'&lt;', '^', '>', '='</code>
         * @param sign policy, one of <code>'+'</code>, <code>'-'</code>, or <code>' '</code>.
         * @param alternate true to request alternate formatting mode (<code>'#'</code> flag).
         * @param width of field after padding or -1 to default
         * @param grouping true to request comma-separated groups
         * @param precision (e.g. decimal places) or -1 to default
         * @param type indicator character
         */
        public Spec(char fill, char align, char sign, boolean alternate, int width,
                boolean grouping, int precision, char type) {
            this.fill = fill;
            this.align = align;
            this.sign = sign;
            this.alternate = alternate;
            this.width = width;
            this.grouping = grouping;
            this.precision = precision;
            this.type = type;
        }

        /**
         * Return a format specifier (text) equivalent to the value of this Spec.
         */
        @Override
        public String toString() {
            StringBuilder buf = new StringBuilder();
            if (specified(fill)) {
                buf.append(fill);
            }
            if (specified(align)) {
                buf.append(align);
            }
            if (specified(sign)) {
                buf.append(sign);
            }
            if (alternate) {
                buf.append('#');
            }
            if (specified(width)) {
                buf.append(width);
            }
            if (grouping) {
                buf.append(',');
            }
            if (specified(precision)) {
                buf.append('.').append(precision);
            }
            if (specified(type)) {
                buf.append(type);
            }
            return buf.toString();
        }

        /**
         * Return a merged <code>Spec</code> object, in which any attribute of this object, that is
         * specified (or <code>true</code>) has the same value in the result, and any attribute of
         * this object that is unspecified (or <code>false</code>) has the value that attribute
         * takes in the other object. This the second object supplies default values. (These
         * defaults may also be unspecified.) The use of this method is to allow a <code>Spec</code>
         * constructed from text to record exactly, and only, what was in the textual specification,
         * while the __format__ method of a client object supplies its type-specific defaults. Thus
         * "20" means "<20s" to a <code>str</code>, ">20.12" to a <code>float</code> and ">20.12g"
         * to a <code>complex</code>.
         *
         * @param defaults to merge where this object does not specify the attribute.
         * @return a new Spec object.
         */
        public Spec withDefaults(Spec other) {
            return new Spec(//
                    specified(fill) ? fill : other.fill, //
                    specified(align) ? align : other.align, //
                    specified(sign) ? sign : other.sign, //
                    alternate || other.alternate, //
                    specified(width) ? width : other.width, //
                    grouping || other.grouping, //
                    specified(precision) ? precision : other.precision, //
                    specified(type) ? type : other.type //
            );
        }

        /**
         * Defaults applicable to most numeric types. Equivalent to " >"
         */
        public static final Spec NUMERIC = new Spec(' ', '>', Spec.NONE, false, Spec.UNSPECIFIED,
                false, Spec.UNSPECIFIED, Spec.NONE);

        /**
         * Constructor offering just precision and type.
         *
         * <pre>
         * [.precision][type]
         * </pre>
         *
         * @param precision (e.g. decimal places)
         * @param type indicator character
         */
        public Spec(int width, int precision, char type) {
            this(' ', '>', Spec.NONE, false, UNSPECIFIED, false, precision, type);
        }

        /** The alignment from the parsed format specification, or default. */
        public char getFill(char defaultFill) {
            return specified(fill) ? fill : defaultFill;
        }

        /** The alignment from the parsed format specification, or default. */
        public char getAlign(char defaultAlign) {
            return specified(align) ? align : defaultAlign;
        }

        /** The precision from the parsed format specification, or default. */
        public int getPrecision(int defaultPrecision) {
            return specified(precision) ? precision : defaultPrecision;
        }

        /** The type code from the parsed format specification, or default supplied. */
        public char getType(char defaultType) {
            return specified(type) ? type : defaultType;
        }

    }

    /**
     * Parser for PEP-3101 field format specifications. This class provides a {@link #parse()}
     * method that translates the format specification into an <code>Spec</code> object.
     */
    private static class Parser {

        private String spec;
        private int ptr;

        /**
         * Constructor simply holds the specification string ahead of the {@link #parse()}
         * operation.
         *
         * @param spec format specifier to parse (e.g. "&lt;+12.3f")
         */
        Parser(String spec) {
            this.spec = spec;
            this.ptr = 0;
        }

        /**
         * Parse the specification with which this object was initialised into an {@link Spec},
         * which is an object encapsulating the format for use by formatting methods. This parser
         * deals only with the format specifiers themselves, as accepted by the
         * <code>__format__</code> method of a type, or the <code>format()</code> built-in, not
         * format strings in general as accepted by <code>str.format()</code>.
         *
         * @return the <code>Spec</code> equivalent to the string given.
         */
        /*
         * This method is the equivalent of CPython's parse_internal_render_format_spec() in
         * ~/Objects/stringlib/formatter.h, but we deal with defaults another way.
         */
        Spec parse() {

            char fill = Spec.NONE, align = Spec.NONE;
            char sign = Spec.NONE, type = Spec.NONE;
            boolean alternate = false, grouping = false;
            int width = Spec.UNSPECIFIED, precision = Spec.UNSPECIFIED;

            // Scan [[fill]align] ...
            if (isAlign()) {
                // First is alignment. fill not specified.
                align = spec.charAt(ptr++);
            } else {
                // Peek ahead
                ptr += 1;
                if (isAlign()) {
                    // Second character is alignment, so first is fill
                    fill = spec.charAt(0);
                    align = spec.charAt(ptr++);
                } else {
                    // Second character is not alignment. We are still at square zero.
                    ptr = 0;
                }
            }

            // Scan [sign] ...
            if (isAt("+- ")) {
                sign = spec.charAt(ptr++);
            }

            // Scan [#] ...
            alternate = scanPast('#');

            // Scan [0] ...
            if (scanPast('0')) {
                // Accept 0 here as equivalent to zero-fill but only not set already.
                if (!Spec.specified(fill)) {
                    fill = '0';
                    if (!Spec.specified(align)) {
                        // Also accept it as equivalent to "=" aligment but only not set already.
                        align = '=';
                    }
                }
            }

            // Scan [width]
            if (isDigit()) {
                width = scanInteger();
            }

            // Scan [,][.precision][type]
            grouping = scanPast(',');

            // Scan [.precision]
            if (scanPast('.')) {
                if (isDigit()) {
                    precision = scanInteger();
                } else {
                    throw new IllegalArgumentException("Format specifier missing precision");
                }
            }

            // Scan [type]
            if (ptr < spec.length()) {
                type = spec.charAt(ptr++);
            }

            // If we haven't reached the end, something is wrong
            if (ptr != spec.length()) {
                throw new IllegalArgumentException("Invalid conversion specification");
            }

            // Restrict grouping to known formats. (Mirrors CPython, but misplaced?)
            if (grouping && "defgEG%F\0".indexOf(type) == -1) {
                throw new IllegalArgumentException("Cannot specify ',' with '" + type + "'.");
            }

            // Create a specification
            return new Spec(fill, align, sign, alternate, width, grouping, precision, type);
        }

        /** Test that the next character is exactly the one specified, and advance past it if it is. */
        private boolean scanPast(char c) {
            if (ptr < spec.length() && spec.charAt(ptr) == c) {
                ptr++;
                return true;
            } else {
                return false;
            }
        }

        /** Test that the next character is one of a specified set. */
        private boolean isAt(String chars) {
            return ptr < spec.length() && (chars.indexOf(spec.charAt(ptr)) >= 0);
        }

        /** Test that the next character is one of the alignment characters. */
        private boolean isAlign() {
            return ptr < spec.length() && ("<^>=".indexOf(spec.charAt(ptr)) >= 0);
        }

        /** Test that the next character is a digit. */
        private boolean isDigit() {
            return ptr < spec.length() && Character.isDigit(spec.charAt(ptr));
        }

        /** The current character is a digit (maybe a sign). Scan the integer, */
        private int scanInteger() {
            int p = ptr++;
            while (isDigit()) {
                ptr++;
            }
            return Integer.parseInt(spec.substring(p, ptr));
        }

    }

}