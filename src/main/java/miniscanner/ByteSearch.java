package miniscanner;

import com.sun.jna.Memory;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Búsqueda de secuencias de bytes con comodines (AOB, "array of bytes") y de texto en la memoria del juego.
 * Las firmas AOB sirven para encontrar el mismo código aunque el juego se actualice y cambien las direcciones.
 */
final class ByteSearch {
    static final int MAX_RESULTS = 10_000, CHUNK = 4 * 1024 * 1024;

    private ByteSearch() {}

    /** Bytes a buscar; donde mask[i] es false, cualquier byte sirve (comodín). */
    record Pattern(byte[] bytes, boolean[] mask, boolean ignoreCase) {
        int length() { return bytes.length; }

        boolean matches(byte[] buf, int pos) {
            for (int i = 0; i < bytes.length; i++)
                if (mask[i] && !same(buf[pos + i], bytes[i], ignoreCase)) return false;
            return true;
        }

        /** Primera posición que no es comodín (para descartar rápido). */
        int firstFixed() {
            for (int i = 0; i < mask.length; i++) if (mask[i]) return i;
            return -1;
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted") // se usa como !same(...): "este byte no coincide"
    static boolean same(byte a, byte b, boolean ignoreCase) { return a == b || ignoreCase && fold(a) == fold(b); }

    /** Mayúsculas ASCII a minúsculas (sirve también para UTF-16, cuyo byte alto es 0). */
    static byte fold(byte b) { return b >= 'A' && b <= 'Z' ? (byte) (b + 32) : b; }

    /** Acepta "89 46 ?? 8B 0D", "8946??8B0D" y "?" o "*" como comodines. */
    static Pattern parseAob(String s) {
        List<String> tokens = new ArrayList<>();
        for (String t : s.trim().split("[\\s,]+")) {
            if (t.isEmpty()) continue;
            if (t.length() > 2 && t.length() % 2 == 0) for (int i = 0; i < t.length(); i += 2) tokens.add(t.substring(i, i + 2));
            else tokens.add(t);
        }
        if (tokens.isEmpty()) throw new IllegalArgumentException("Escribe los bytes a buscar, por ejemplo: 89 46 ?? 8B 0D");
        byte[] bytes = new byte[tokens.size()];
        boolean[] mask = new boolean[tokens.size()];
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.matches("\\?{1,2}|\\*{1,2}")) continue;
            if (!t.matches("[0-9A-Fa-f]{1,2}")) throw new IllegalArgumentException("Byte inválido: «" + t + "». Usa hex (00 a FF) o ?? como comodín.");
            bytes[i] = (byte) Integer.parseInt(t, 16);
            mask[i] = true;
        }
        Pattern p = new Pattern(bytes, mask, false);
        if (p.firstFixed() < 0) throw new IllegalArgumentException("Al menos un byte tiene que ser fijo (no todo ??).");
        return p;
    }

    static Pattern text(String s, Charset cs, boolean ignoreCase) {
        if (s.isEmpty()) throw new IllegalArgumentException("Escribe el texto a buscar.");
        byte[] b = s.getBytes(cs);
        boolean[] mask = new boolean[b.length];
        Arrays.fill(mask, true);
        return new Pattern(b, mask, ignoreCase);
    }

    /** Pone el patrón sobre los bytes actuales: donde hay comodín se deja el byte que ya estaba. */
    static byte[] apply(Pattern patch, byte[] current) {
        byte[] out = current.clone();
        for (int i = 0; i < patch.length(); i++) if (patch.mask()[i]) out[i] = patch.bytes()[i];
        return out;
    }

    /**
     * Direcciones (en orden) donde aparece el patrón, como mucho MAX_RESULTS.
     * @param writableOnly false = buscar también en código y memoria de solo lectura
     */
    static List<Long> scan(Win.MemorySource src, Pattern pat, boolean writableOnly, Progress p) {
        List<Long> out = new ArrayList<>();
        int len = pat.length(), first = pat.firstFixed();
        byte fb = pat.bytes()[first];
        boolean ic = pat.ignoreCase();
        byte[] buf = new byte[CHUNK + len - 1];
        List<Win.Region> regs = src.regions(writableOnly);
        try (Memory mem = new Memory(CHUNK + len - 1)) {
            for (int r = 0; r < regs.size(); r++) {
                long base = regs.get(r).base(), size = regs.get(r).size();
                for (long off = 0; off < size; off += CHUNK) {
                    p.update(r, regs.size());
                    // Se leen len-1 bytes de más para no perder coincidencias que cruzan al bloque siguiente
                    int got = src.readInto(base + off, mem, (int) Math.min(CHUNK + len - 1, size - off));
                    if (got < len) continue;
                    mem.read(0, buf, 0, got);
                    int last = Math.min(got - len, CHUNK - 1);
                    for (int i = 0; i <= last; i++) {
                        if (!same(buf[i + first], fb, ic) || !pat.matches(buf, i)) continue;
                        out.add(base + off + i);
                        if (out.size() >= MAX_RESULTS) return out;
                    }
                }
            }
        }
        p.update(regs.size(), regs.size());
        return out;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) { if (!sb.isEmpty()) sb.append(' '); sb.append(String.format("%02X", x & 0xFF)); }
        return sb.toString();
    }

    /** Vista previa de texto: los caracteres no imprimibles se muestran como punto. */
    static String preview(byte[] b, Charset cs) {
        StringBuilder sb = new StringBuilder();
        for (char c : new String(b, cs).toCharArray()) sb.append(c >= 32 && c != 0xFFFD && !Character.isISOControl(c) ? c : '.');
        return sb.toString();
    }
}
