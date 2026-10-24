package miniscanner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CheatTreeTest {
    static CheatEntry entry(String name, String... group) {
        CheatEntry e = new CheatEntry(name, new AddressExpr(null, 0x1000, new long[0]), ValueType.INT4);
        e.group = List.of(group);
        return e;
    }

    @Test
    void agrupaEnElOrdenEnQueAparecen() {
        CheatEntry dinero = entry("Dinero"), vida = entry("Vida", "Jugador"), balas = entry("Balas", "Jugador", "Armas"),
                nivel = entry("Nivel"), mana = entry("Maná", "Jugador");
        CheatTree root = CheatTree.of(List.of(dinero, vida, balas, nivel, mana));

        // Raíz: Dinero, [Jugador], Nivel — el grupo queda donde apareció su primera entrada
        assertEquals(3, root.children.size());
        assertSame(dinero, root.children.get(0));
        CheatTree jugador = (CheatTree) root.children.get(1);
        assertSame(nivel, root.children.get(2));

        assertEquals("Jugador", jugador.name);
        assertEquals(List.of("Jugador"), jugador.path);
        assertSame(vida, jugador.children.get(0));
        CheatTree armas = (CheatTree) jugador.children.get(1);
        assertEquals(List.of("Jugador", "Armas"), armas.path);
        assertSame(mana, jugador.children.get(2));

        assertEquals(List.of(vida, balas, mana), jugador.entries());
        assertEquals(List.of(dinero, vida, balas, mana, nivel), root.entries());
    }

    @Test
    void textoDeLasRutas() {
        assertEquals("Jugador / Armas", CheatTree.pathText(List.of("Jugador", "Armas")));
        assertEquals(List.of("Jugador", "Armas"), CheatTree.parsePath(" Jugador /Armas / "));
        assertEquals(List.of(), CheatTree.parsePath("  "));
    }
}
