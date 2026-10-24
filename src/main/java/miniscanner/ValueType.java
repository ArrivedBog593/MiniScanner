package miniscanner;

import java.nio.ByteBuffer;
import java.util.Locale;

/** Tipos de dato, con los mismos nombres que usa Cheat Engine en sus tablas (.CT). */
enum ValueType {
    BYTE("Byte (0-255)", "Byte", 1, false),
    INT2("Entero (2 bytes)", "2 Bytes", 2, false),
    INT4("Entero (4 bytes)", "4 Bytes", 4, false),
    INT8("Entero (8 bytes)", "8 Bytes", 8, false),
    FLOAT("Float", "Float", 4, true),
    DOUBLE("Double", "Double", 8, true);

    final String label, ctName;
    final int size;
    final boolean isFloat;

    ValueType(String label, String ctName, int size, boolean isFloat) {
        this.label = label; this.ctName = ctName; this.size = size; this.isFloat = isFloat;
    }

    int align() { return Math.min(size, 4); }

    double get(ByteBuffer b, int off) {
        return switch (this) {
            case BYTE -> b.get(off) & 0xFF;
            case INT2 -> b.getShort(off);
            case INT4 -> b.getInt(off);
            case INT8 -> b.getLong(off);
            case FLOAT -> b.getFloat(off);
            case DOUBLE -> b.getDouble(off);
        };
    }

    double decode(byte[] raw) { return get(ByteBuffer.wrap(raw).order(Win.LE), 0); }

    byte[] encode(double v) {
        ByteBuffer b = ByteBuffer.allocate(size).order(Win.LE);
        switch (this) {
            case BYTE -> b.put((byte) (long) v);
            case INT2 -> b.putShort((short) (long) v);
            case INT4 -> b.putInt((int) (long) v);
            case INT8 -> b.putLong((long) v);
            case FLOAT -> b.putFloat((float) v);
            case DOUBLE -> b.putDouble(v);
        }
        return b.array();
    }

    double parse(String s) {
        s = s.trim();
        // En floats la coma es decimal ("1,5" = 1.5); "1.000,5" falla en vez de dar un número equivocado
        if (isFloat) return Double.parseDouble(s.replace(',', '.'));
        return fromLong(Long.parseLong(s.replace(",", "")));
    }

    /**
     * Valida el rango de un entero y lo deja como lo devuelve get(), para que las comparaciones
     * coincidan: Byte sin signo ("-1" = 255) y 2/4 bytes con signo ("4294967295" = -1).
     */
    double fromLong(long v) {
        boolean ok = switch (this) {
            case BYTE -> v >= -128 && v <= 255;
            case INT2 -> v >= Short.MIN_VALUE && v <= 0xFFFF;
            case INT4 -> v >= Integer.MIN_VALUE && v <= 0xFFFFFFFFL;
            default -> true;
        };
        if (!ok) throw new NumberFormatException("Fuera de rango para " + label);
        return switch (this) {
            case BYTE -> v & 0xFF;
            case INT2 -> (short) v;
            case INT4 -> (int) v;
            default -> v;
        };
    }

    /** Siempre con punto decimal, sin importar el idioma de Windows, para que parse() lo lea igual. */
    String format(double v) { return isFloat ? String.format(Locale.ROOT, "%.2f", v) : String.valueOf((long) v); }

    static ValueType fromCt(String s) {
        if (s == null) return null;
        for (ValueType t : values()) if (t.ctName.equalsIgnoreCase(s.trim())) return t;
        return null;
    }

    @Override public String toString() { return label; }
}

interface Progress { void update(long done, long total); }
