package miniscanner;

import javax.swing.*;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.Objects;

/** Pequeños atajos de Swing que usan todas las pestañas. */
final class Ui {
    private Ui() {}

    static JButton button(String text, Runnable r) {
        JButton b = new JButton(text);
        b.addActionListener(e -> r.run());
        return b;
    }

    static DefaultTableModel readOnlyModel(String... cols) {
        return new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
    }

    /** Lo elegido en un combo. Nunca es null: los combos de MiniScanner siempre tienen algo seleccionado. */
    static <T> T selected(JComboBox<T> box) {
        return Objects.requireNonNull(box.getItemAt(box.getSelectedIndex()), "combo sin selección");
    }

    static JPanel row(Component... cs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (Component c : cs) p.add(c);
        return p;
    }

    /**
     * Campo para elegir una tecla rápida pulsándola. Deja la combinación en key = {mods, vk}
     * (vk 0 = ninguna; Supr o Retroceso la quitan) y toma el foco al aparecer en un diálogo.
     */
    static JTextField keyCaptureField(int[] key) {
        JTextField f = new JTextField(key[1] == 0 ? "" : Hotkey.keyText(key[0], key[1]), 14);
        f.setEditable(false);
        f.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent ev) {
                int vk = ev.getKeyCode();
                if (vk == KeyEvent.VK_DELETE || vk == KeyEvent.VK_BACK_SPACE) { key[0] = key[1] = 0; f.setText(""); }
                else if (Hotkey.supported(vk)) {
                    key[0] = Hotkey.modsOf(ev);
                    key[1] = vk;
                    f.setText(Hotkey.keyText(key[0], vk));
                }
                ev.consume();
            }
        });
        f.addAncestorListener(new AncestorListener() {
            public void ancestorAdded(AncestorEvent ev) { f.requestFocusInWindow(); }
            public void ancestorRemoved(AncestorEvent ev) {}
            public void ancestorMoved(AncestorEvent ev) {}
        });
        return f;
    }
}
