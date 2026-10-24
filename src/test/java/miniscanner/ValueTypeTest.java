package miniscanner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ValueTypeTest {
    @Test
    void parseEnteros() {
        assertEquals(1234, ValueType.INT4.parse(" 1,234 "));
        assertEquals(-1, ValueType.INT4.parse("4294967295"));
        assertEquals(-1, ValueType.INT2.parse("65535"));
        assertEquals(255, ValueType.BYTE.parse("-1"));
        assertEquals(200, ValueType.BYTE.parse("200"));
        assertEquals(Long.MIN_VALUE, ValueType.INT8.parse("-9223372036854775808"));
    }

    @Test
    void parseFueraDeRango() {
        assertThrows(NumberFormatException.class, () -> ValueType.BYTE.parse("256"));
        assertThrows(NumberFormatException.class, () -> ValueType.BYTE.parse("-129"));
        assertThrows(NumberFormatException.class, () -> ValueType.INT2.parse("65536"));
        assertThrows(NumberFormatException.class, () -> ValueType.INT4.parse("4294967296"));
        assertThrows(NumberFormatException.class, () -> ValueType.INT4.parse("abc"));
    }

    @Test
    void parseFloatsConPuntoOComa() {
        assertEquals(1.5, ValueType.FLOAT.parse("1.5"));
        assertEquals(1.5, ValueType.DOUBLE.parse("1,5"));
        assertThrows(NumberFormatException.class, () -> ValueType.DOUBLE.parse("1.000,5"));
    }

    @Test
    void formatNoDependeDelIdioma() {
        java.util.Locale old = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("es-ES"));
            assertEquals("1.50", ValueType.FLOAT.format(1.5));
            assertEquals(1.5, ValueType.FLOAT.parse(ValueType.FLOAT.format(1.5)));
        } finally {
            java.util.Locale.setDefault(old);
        }
        assertEquals("-7", ValueType.INT4.format(-7));
    }

    @Test
    void encodeDecodeIdaYVuelta() {
        for (ValueType t : ValueType.values()) {
            double v = t == ValueType.BYTE ? 200 : t.isFloat ? 3.25 : -12345;
            assertEquals(v, t.decode(t.encode(v)), t.name());
            assertEquals(t.size, t.encode(v).length);
        }
    }

    @Test
    void nombresDeCheatEngine() {
        assertEquals(ValueType.INT4, ValueType.fromCt("4 Bytes"));
        assertEquals(ValueType.DOUBLE, ValueType.fromCt(" double "));
        assertNull(ValueType.fromCt("String"));
        assertNull(ValueType.fromCt(null));
    }

    @Test
    void toleranciaSegunDecimales() {
        assertEquals(0.5, ValueType.tolFor("100"), 1e-12);
        assertEquals(0.05, ValueType.tolFor("1.5"), 1e-12);
        assertEquals(0.005, ValueType.tolFor("1,25"), 1e-12);
    }
}
