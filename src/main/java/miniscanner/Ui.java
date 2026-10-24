package miniscanner;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;

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

    static JPanel row(Component... cs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (Component c : cs) p.add(c);
        return p;
    }
}
