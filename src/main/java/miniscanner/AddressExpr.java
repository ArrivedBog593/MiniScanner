package miniscanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Una dirección que sobrevive a reiniciar el juego (cuando es posible):
 *  - "Juego.exe"+1A2B            → dirección estática dentro de un módulo
 *  - "Juego.exe"+1A2B con offsets → pointer: se lee la dirección base, se suma el offset, se vuelve a leer...
 *  - 7FF6A1B2C3D0                → dirección absoluta (solo vale mientras el juego siga abierto)
 */
@SuppressWarnings("ClassCanBeRecord")
final class AddressExpr {
    final String module;      // null = dirección absoluta
    final long offset;
    final long[] offsets;     // del primero (cerca de la base) al último (cerca del valor)

    AddressExpr(String module, long offset, long[] offsets) {
        this.module = module; this.offset = offset; this.offsets = offsets;
    }

    static AddressExpr of(Win.Proc proc, long addr) {
        Win.Module m = proc == null ? null : proc.moduleAt(addr);
        return m != null ? new AddressExpr(m.name(), addr - m.base(), new long[0]) : new AddressExpr(null, addr, new long[0]);
    }

    boolean isPointer() { return offsets.length > 0; }

    /** Calcula la dirección final en el proceso actual, o Win.BAD si no se puede. */
    long resolve(Win.Proc p) {
        if (p == null) return Win.BAD;
        long base;
        if (module != null) {
            Win.Module m = p.moduleByName(module);
            if (m == null) return Win.BAD;
            base = m.base() + offset;
        } else base = offset;
        if (offsets.length == 0) return base;
        long ptr = p.readPtr(base);
        for (int i = 0; i < offsets.length - 1 && ptr != Win.BAD; i++) ptr = p.readPtr(ptr + offsets[i]);
        return ptr == Win.BAD || ptr == 0 ? Win.BAD : ptr + offsets[offsets.length - 1];
    }

    static String hex(long v) { return Long.toHexString(v).toUpperCase(Locale.ROOT); }

    String baseText() { return module != null ? "\"" + module + "\"+" + hex(offset) : hex(offset); }

    String offsetsText() {
        StringBuilder sb = new StringBuilder();
        for (long o : offsets) { if (!sb.isEmpty()) sb.append(", "); sb.append(hex(o)); }
        return sb.toString();
    }

    @Override public String toString() {
        if (offsets.length == 0) return baseText();
        StringBuilder sb = new StringBuilder("[" + baseText() + "]");
        for (long o : offsets) sb.append(" → ").append(hex(o));
        return sb.toString();
    }

    // ------------------------------------------------------------ texto ↔ dirección
    private static final Pattern MOD_PLUS = Pattern.compile("^\"?([^\"+\\[\\]]+?)\"?\\s*\\+\\s*(?:0x)?([0-9A-Fa-f]+)$");
    private static final Pattern HEX = Pattern.compile("^(?:0x)?([0-9A-Fa-f]+)$");
    private static final Pattern MOD_ONLY = Pattern.compile("^\"?([^\"+\\[\\]]+\\.(?:exe|dll))\"?$", Pattern.CASE_INSENSITIVE);

    /** Acepta: "Juego.exe"+1234, Juego.exe+1234, 7FF6A1B2C3D0, "Juego.exe". Lanza IllegalArgumentException si no. */
    static AddressExpr parse(String base, String offsetsCsv) {
        long[] offs = parseOffsets(offsetsCsv);
        String s = base.trim();
        Matcher m = MOD_PLUS.matcher(s);
        if (m.matches()) {
            String a = m.group(1).trim();
            long off = Long.parseUnsignedLong(m.group(2), 16);
            Matcher h = HEX.matcher(a);
            if (h.matches()) return new AddressExpr(null, Long.parseUnsignedLong(h.group(1), 16) + off, offs);
            return new AddressExpr(a, off, offs);
        }
        m = HEX.matcher(s);
        if (m.matches()) return new AddressExpr(null, Long.parseUnsignedLong(m.group(1), 16), offs);
        m = MOD_ONLY.matcher(s);
        if (m.matches()) return new AddressExpr(m.group(1), 0, offs);
        throw new IllegalArgumentException("No entiendo la dirección: " + base);
    }

    static long[] parseOffsets(String csv) {
        if (csv == null || csv.isBlank()) return new long[0];
        List<Long> out = new ArrayList<>();
        for (String part : csv.split("[,\\s]+")) {
            if (part.isBlank()) continue;
            Matcher h = HEX.matcher(part.trim());
            if (!h.matches()) throw new IllegalArgumentException("Offset inválido: " + part);
            out.add(Long.parseUnsignedLong(h.group(1), 16));
        }
        return out.stream().mapToLong(Long::longValue).toArray();
    }

    /** Formato de una línea para guardar listas de pointers: módulo|offset|off1,off2,... */
    String serialize() {
        StringBuilder sb = new StringBuilder(module == null ? "" : module).append('|').append(hex(offset)).append('|');
        for (int i = 0; i < offsets.length; i++) { if (i > 0) sb.append(','); sb.append(hex(offsets[i])); }
        return sb.toString();
    }

    static AddressExpr deserialize(String line) {
        String[] p = line.split("\\|", -1);
        if (p.length != 3) throw new IllegalArgumentException("Línea inválida: " + line);
        return new AddressExpr(p[0].isEmpty() ? null : p[0], Long.parseUnsignedLong(p[1], 16), parseOffsets(p[2]));
    }
}

/** Una fila de la tabla de trucos. */
final class CheatEntry {
    volatile String description;
    final AddressExpr address;
    final ValueType type;
    volatile byte[] frozenValue; // null = no congelado
    volatile Hotkey hotkey;      // null = sin tecla rápida
    /** Grupo donde está, desde la raíz: [] = sin grupo, ["Jugador", "Armas"] = dentro de Jugador / Armas. */
    volatile List<String> group = List.of();

    CheatEntry(String description, AddressExpr address, ValueType type) {
        this.description = description; this.address = address; this.type = type;
    }
}
