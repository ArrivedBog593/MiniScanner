package miniscanner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AddressExprTest {
    @Test
    void moduloMasOffset() {
        AddressExpr e = AddressExpr.parse("\"Juego.exe\"+1A2B", "");
        assertEquals("Juego.exe", e.module);
        assertEquals(0x1A2B, e.offset);
        assertFalse(e.isPointer());

        e = AddressExpr.parse("juego.exe + 0x10", "");
        assertEquals("juego.exe", e.module);
        assertEquals(0x10, e.offset);
    }

    @Test
    void direccionAbsoluta() {
        AddressExpr e = AddressExpr.parse("7FF6A1B2C3D0", null);
        assertNull(e.module);
        assertEquals(0x7FF6A1B2C3D0L, e.offset);
        // "hex+hex" también es absoluta
        assertEquals(0x1010, AddressExpr.parse("1000+10", "").offset);
        assertNull(AddressExpr.parse("1000+10", "").module);
    }

    @Test
    void soloModulo() {
        AddressExpr e = AddressExpr.parse("\"Juego.DLL\"", "");
        assertEquals("Juego.DLL", e.module);
        assertEquals(0, e.offset);
    }

    @Test
    void offsets() {
        AddressExpr e = AddressExpr.parse("Juego.exe+10", "10, 2C 0x8");
        assertArrayEquals(new long[]{0x10, 0x2C, 0x8}, e.offsets);
        assertTrue(e.isPointer());
        assertEquals("[\"Juego.exe\"+10] → 10 → 2C → 8", e.toString());
        assertEquals("10, 2C, 8", e.offsetsText());
    }

    @Test
    void invalidos() {
        assertThrows(IllegalArgumentException.class, () -> AddressExpr.parse("no es una dirección", ""));
        assertThrows(IllegalArgumentException.class, () -> AddressExpr.parse("Juego.exe+10", "1G"));
        assertThrows(IllegalArgumentException.class, () -> AddressExpr.parse("FFFFFFFFFFFFFFFFF", ""));
    }

    @Test
    void serializarIdaYVuelta() {
        for (AddressExpr e : new AddressExpr[]{
                new AddressExpr("Juego.exe", 0x1234, new long[]{0x10, 0x2C}),
                new AddressExpr(null, 0x7FF6A1B2C3D0L, new long[0]),
                new AddressExpr("a.dll", 0, new long[]{0})}) {
            AddressExpr back = AddressExpr.deserialize(e.serialize());
            assertEquals(e.module, back.module);
            assertEquals(e.offset, back.offset);
            assertArrayEquals(e.offsets, back.offsets);
        }
        assertThrows(IllegalArgumentException.class, () -> AddressExpr.deserialize("solo|dos"));
    }
}
