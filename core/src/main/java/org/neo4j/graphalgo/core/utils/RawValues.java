package org.neo4j.graphalgo.core.utils;

import org.neo4j.graphdb.Direction;

/**
 * TODO: find suitable name or move
 *
 * @author mknblch
 */
public class RawValues {

    public static final IdCombiner OUTGOING = RawValues::combineIntInt;
    public static final IdCombiner INCOMING = RawValues::combineReverseIntInt;
    public static final IdCombiner BOTH = RawValues::combineSorted;

    /**
     * shifts head into the most significant 4 bytes of the long
     * and places the tail in the least significant bytes
     *
     * @param head an arbitrary int value
     * @param tail an arbitrary int value
     * @return combination of head and tail
     */
    public static long combineIntInt(int head, int tail) {
        return ((long) head << 32) | (long) tail & 0xFFFFFFFFL;
    }

    /**
     * shifts tail into the most significant 4 bytes of the long
     * and places the head in the least significant bytes
     *
     * @param head an arbitrary int value
     * @param tail an arbitrary int value
     * @return combination of head and tail
     */
    public static long combineReverseIntInt(int head, int tail) {
        return ((long) tail << 32) | (long) head & 0xFFFFFFFFL;
    }

    public static long combineSorted(int head, int tail) {
        return head <= tail
                ? combineIntInt(head, tail)
                : combineIntInt(tail, head);
    }

    public static long combineIntInt(Direction direction, int head, int tail) {
        switch (direction) {
            case OUTGOING:
                return combineIntInt(head, tail);
            case INCOMING:
                return combineReverseIntInt(head, tail);
            case BOTH:
                return combineSorted(head, tail);
            default:
                throw new IllegalArgumentException("Unkown direction: " + direction);
        }
    }

    public static IdCombiner combiner(Direction direction) {
        switch (direction) {
            case OUTGOING:
                return OUTGOING;
            case INCOMING:
                return INCOMING;
            case BOTH:
                return BOTH;
            default:
                throw new IllegalArgumentException("Unkown direction: " + direction);
        }
    }

    /**
     * get the head value
     *
     * @param combinedValue a value built of 2 ints
     * @return the most significant 4 bytes as int
     */
    public static int getHead(long combinedValue) {
        return (int) (combinedValue >> 32);
    }

    /**
     * get the tail value
     *
     * @param combinedValue a value built of 2 ints
     * @return the least significant 4 bytes as int
     */
    public static int getTail(long combinedValue) {
        return (int) combinedValue;
    }

    /**
     * convert property value to double
     *
     * @param value                the value object
     * @param propertyDefaultValue default value if property cant be converted
     * @return double representation of value
     */
    public static double extractValue(Object value, double propertyDefaultValue) {
        if (value instanceof Number) {
            Number number = (Number) value;
            return number.doubleValue();
        }
        if (value instanceof String) {
            String s = (String) value;
            if (!s.isEmpty()) {
                return Double.parseDouble(s);
            }
        }
        if (value instanceof Boolean) {
            if ((Boolean) value) {
                return 1d;
            }
        }
        // TODO: arrays

        return propertyDefaultValue;
    }
}
