package miniscanner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HotkeyTest {
    static final int F1 = 0x70;

    @Test
    void textoDeLasTeclas() {
        assertEquals("F1", Hotkey.keyText(0, F1));
        assertEquals("F12", Hotkey.keyText(0, 0x7B));
        assertEquals("Ctrl+Alt+Shift+K", Hotkey.keyText(Hotkey.MOD_CONTROL | Hotkey.MOD_ALT | Hotkey.MOD_SHIFT, 'K'));
        assertEquals("Num 5", Hotkey.keyText(0, 0x65));
        assertEquals("Pausa Inter", Hotkey.keyText(0, Hotkey.VK_PAUSE));
        assertTrue(Hotkey.supported(Hotkey.VK_PAUSE));
        assertEquals("F1 → congelar", new Hotkey(0, F1, Hotkey.Action.TOGGLE, null).toString());
        assertEquals("Ctrl+2 → 999", new Hotkey(Hotkey.MOD_CONTROL, '2', Hotkey.Action.SET, "999").toString());
    }

    @Test
    void teclasSoportadas() {
        assertTrue(Hotkey.supported('A'));
        assertTrue(Hotkey.supported('9'));
        assertTrue(Hotkey.supported(F1));
        assertFalse(Hotkey.supported(16), "Shift solo no es una tecla rápida");
        assertFalse(Hotkey.supported(0x87), "F24 tiene otro código en Java");
    }

    @Test
    void comboCabeEnElRangoDeRegisterHotKey() {
        int c = new Hotkey(Hotkey.MOD_CONTROL | Hotkey.MOD_ALT | Hotkey.MOD_SHIFT, 0x7B, Hotkey.Action.TOGGLE, null).combo();
        assertTrue(c > 0 && c <= 0xBFFF);
        assertEquals(c, Hotkey.combo(7, 0x7B));
        assertNotEquals(Hotkey.combo(0, F1), Hotkey.combo(Hotkey.MOD_SHIFT, F1));
    }

    @Test
    void formatoDeCheatEngine() {
        Hotkey h = new Hotkey(Hotkey.MOD_CONTROL | Hotkey.MOD_SHIFT, F1, Hotkey.Action.SET, "100");
        assertEquals(List.of(17, 16, F1), h.ceKeys());
        assertEquals(h, Hotkey.fromCe(h.ceKeys(), Hotkey.Action.SET, "100"));
        // Ctrl izquierdo (0xA2) también cuenta como Ctrl
        assertEquals(new Hotkey(Hotkey.MOD_CONTROL, 'G', Hotkey.Action.TOGGLE, null),
                Hotkey.fromCe(List.of(0xA2, (int) 'G'), Hotkey.Action.TOGGLE, "ignorado"));
        assertNull(Hotkey.fromCe(List.of(17), Hotkey.Action.TOGGLE, null), "sin tecla principal");
        assertNull(Hotkey.fromCe(List.of((int) 'A', (int) 'B'), Hotkey.Action.TOGGLE, null), "dos teclas principales");
        assertNull(Hotkey.fromCe(List.of(F1), null, null), "acción desconocida");
        assertEquals(Hotkey.Action.TOGGLE, Hotkey.Action.fromCt(" toggle activation "));
        assertNull(Hotkey.Action.fromCt("Increase Value"));
    }
}
