package miniscanner;

import java.util.ArrayList;
import java.util.List;

/**
 * Los grupos de la tabla de trucos como árbol, armado a partir de {@link CheatEntry#group}.
 * Los hijos (subgrupos y entradas) quedan en el orden en que aparecen por primera vez en la lista.
 */
final class CheatTree {
    final String name;       // "" en la raíz
    final List<String> path; // ruta desde la raíz ([] en la raíz)
    /** Subgrupos (CheatTree) y entradas (CheatEntry), mezclados en orden. */
    final List<Object> children = new ArrayList<>();

    private CheatTree(String name, List<String> path) { this.name = name; this.path = path; }

    static CheatTree of(List<CheatEntry> entries) {
        CheatTree root = new CheatTree("", List.of());
        for (CheatEntry e : entries) {
            CheatTree node = root;
            for (String g : e.group) node = node.child(g);
            node.children.add(e);
        }
        return root;
    }

    private CheatTree child(String name) {
        for (Object o : children) if (o instanceof CheatTree t && t.name.equals(name)) return t;
        List<String> p = new ArrayList<>(path);
        p.add(name);
        CheatTree t = new CheatTree(name, List.copyOf(p));
        children.add(t);
        return t;
    }

    /** Todas las entradas de este grupo y de sus subgrupos, en orden. */
    List<CheatEntry> entries() {
        List<CheatEntry> out = new ArrayList<>();
        collect(out);
        return out;
    }

    private void collect(List<CheatEntry> out) {
        for (Object o : children) {
            if (o instanceof CheatEntry e) out.add(e);
            else ((CheatTree) o).collect(out);
        }
    }

    static String pathText(List<String> path) { return String.join(" / ", path); }

    /** Lo contrario de pathText: "Jugador / Armas" → [Jugador, Armas]; vacío = sin grupo. */
    static List<String> parsePath(String text) {
        List<String> out = new ArrayList<>();
        for (String s : text.split("/")) if (!s.isBlank()) out.add(s.trim());
        return List.copyOf(out);
    }
}
