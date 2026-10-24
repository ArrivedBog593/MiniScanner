package miniscanner;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Tecla rápida global de una entrada de la tabla de trucos: funciona aunque el juego tenga el foco.
 * La combinación se guarda como la entiende Windows: modificadores MOD_* y código de tecla virtual.
 *
 * @param value el valor a escribir con {@link Action#SET} (null con TOGGLE)
 */
record Hotkey(int mods, int vk, Action action, String value) {
    static final int MOD_ALT = 1, MOD_CONTROL = 2, MOD_SHIFT = 4;
    /** La tecla Pausa (Pause/Break): mismo código en Java y en Windows. */
    static final int VK_PAUSE = 0x13;
    private static final int VK_SHIFT = 16, VK_CONTROL = 17, VK_MENU = 18;

    /** Lo que hace la tecla, con el nombre que usa Cheat Engine en sus tablas. */
    enum Action {
        TOGGLE("Toggle Activation"), SET("Set Value");

        final String ctName;

        Action(String ctName) { this.ctName = ctName; }

        static Action fromCt(String s) {
            for (Action a : values()) if (s != null && a.ctName.equalsIgnoreCase(s.trim())) return a;
            return null;
        }
    }

    /** Teclas permitidas: letras, números, numpad, F1-F12 y Pausa, que tienen el mismo código en Java y en Windows. */
    static boolean supported(int vk) {
        return vk >= 'A' && vk <= 'Z' || vk >= '0' && vk <= '9' || vk >= 0x60 && vk <= 0x69 || vk >= 0x70 && vk <= 0x7B
                || vk == VK_PAUSE;
    }

    static int modsOf(KeyEvent e) {
        int m = 0;
        if (e.isControlDown()) m |= MOD_CONTROL;
        if (e.isAltDown()) m |= MOD_ALT;
        if (e.isShiftDown()) m |= MOD_SHIFT;
        return m;
    }

    /** Número único de la combinación de teclas (sin la acción); también sirve de id para RegisterHotKey. */
    int combo() { return combo(mods, vk); }

    static int combo(int mods, int vk) { return mods << 8 | vk; }

    static String keyText(int mods, int vk) {
        StringBuilder sb = new StringBuilder();
        if ((mods & MOD_CONTROL) != 0) sb.append("Ctrl+");
        if ((mods & MOD_ALT) != 0) sb.append("Alt+");
        if ((mods & MOD_SHIFT) != 0) sb.append("Shift+");
        if (vk >= 0x70 && vk <= 0x7B) sb.append('F').append(vk - 0x6F);
        else if (vk >= 0x60 && vk <= 0x69) sb.append("Num ").append(vk - 0x60);
        else if (vk == VK_PAUSE) sb.append("Pausa Inter"); // así se llama en los teclados en español (Pause/Break)
        else sb.append((char) vk);
        return sb.toString();
    }

    String keyText() { return keyText(mods, vk); }

    @Override public String toString() { return keyText() + (action == Action.TOGGLE ? " → congelar" : " → " + value); }

    // ------------------------------------------------------------ formato .CT
    /** Teclas como las guarda Cheat Engine: primero los modificadores como teclas (Ctrl = 17...) y al final la tecla. */
    List<Integer> ceKeys() {
        List<Integer> out = new ArrayList<>();
        if ((mods & MOD_CONTROL) != 0) out.add(VK_CONTROL);
        if ((mods & MOD_ALT) != 0) out.add(VK_MENU);
        if ((mods & MOD_SHIFT) != 0) out.add(VK_SHIFT);
        out.add(vk);
        return out;
    }

    /** Lo contrario de ceKeys(). Devuelve null si la combinación usa teclas o acciones que no soportamos. */
    static Hotkey fromCe(List<Integer> keys, Action action, String value) {
        if (action == null) return null;
        int mods = 0, vk = 0;
        for (int k : keys) {
            switch (k) {
                case VK_SHIFT, 0xA0, 0xA1 -> mods |= MOD_SHIFT;    // Shift, izquierdo, derecho
                case VK_CONTROL, 0xA2, 0xA3 -> mods |= MOD_CONTROL;
                case VK_MENU, 0xA4, 0xA5 -> mods |= MOD_ALT;
                default -> {
                    if (vk != 0 || !supported(k)) return null;
                    vk = k;
                }
            }
        }
        return vk == 0 ? null : new Hotkey(mods, vk, action, action == Action.SET ? value : null);
    }
}
