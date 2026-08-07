package org.teavm.classlib.java.util.concurrent.atomic;

/** Minimal TeaVM class-library mapping required by Gson's JSON tree writer. */
public final class TAtomicIntegerArray {
    private final int[] values;

    public TAtomicIntegerArray(int length) {
        values = new int[length];
    }

    public TAtomicIntegerArray(int[] array) {
        values = array.clone();
    }

    public int length() {
        return values.length;
    }

    public int get(int index) {
        return values[index];
    }

    public void set(int index, int value) {
        values[index] = value;
    }
}
