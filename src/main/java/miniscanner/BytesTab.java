package miniscanner;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static miniscanner.Ui.*;

/** Pestaña Bytes / Texto: buscar firmas AOB con comodines o texto, y parchear bytes. */
final class BytesTab extends JPanel {
    enum Kind {
        AOB("Arreglo de bytes (AOB)", null), TEXT("Texto (ASCII / UTF-8)", StandardCharsets.UTF_8),
        UTF16("Texto (UTF-16)", StandardCharsets.UTF_16LE);

        final String label;
        final Charset charset; // null = bytes en hex

        Kind(String label, Charset charset) { this.label = label; this.charset = charset; }

        @Override public String toString() { return label; }
    }

    static final int PREVIEW = 16;

    private final MiniScanner app;
    private List<Long> results = new ArrayList<>();
    /** Codificación de la última búsqueda, para la vista previa de texto. */
    private Charset shownCharset = StandardCharsets.UTF_8;

    private final JTextField queryField = new JTextField(30), patchField = new JTextField(24);
    private final JComboBox<Kind> kindBox = new JComboBox<>(Kind.values());
    private final JCheckBox caseBox = new JCheckBox("Ignorar mayúsculas", true),
            allMemBox = new JCheckBox("Buscar también en el código y la memoria de solo lectura", true);
    private final JComboBox<ValueType> typeBox = new JComboBox<>(ValueType.values());
    private final DefaultTableModel model = readOnlyModel("Dirección", "Bytes", "Texto");
    private final JTable table = new JTable(model);

    BytesTab(MiniScanner app) {
        super(new BorderLayout());
        this.app = app;
        typeBox.setSelectedItem(ValueType.INT4);
        kindBox.addActionListener(e -> caseBox.setEnabled(kindBox.getSelectedItem() != Kind.AOB));
        caseBox.setEnabled(false);
        queryField.addActionListener(e -> search());
        table.getColumnModel().getColumn(0).setPreferredWidth(200);
        table.getColumnModel().getColumn(1).setPreferredWidth(380);
        table.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JPanel north = new JPanel(new GridLayout(0, 1));
        north.add(row(new JLabel("Buscar:"), queryField, kindBox, button("Buscar", this::search)));
        north.add(row(caseBox, allMemBox));
        north.add(new JLabel(" AOB: bytes en hex separados por espacios, ?? = cualquier byte. Ejemplo: 89 46 ?? 8B 0D"));
        JPanel south = new JPanel(new GridLayout(0, 1));
        south.add(row(button("Ver en memoria", this::showSelected), new JLabel("  Tipo:"), typeBox,
                button("Agregar a la tabla", this::addToTable)));
        south.add(row(new JLabel("Escribir bytes:"), patchField, button("Escribir en seleccionados", this::patchSelected),
                new JLabel(" (?? deja el byte como está)")));
        add(north, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
    }

    void search() {
        if (!app.needProc()) return;
        Kind kind = (Kind) kindBox.getSelectedItem();
        ByteSearch.Pattern pat;
        try {
            pat = kind == Kind.AOB ? ByteSearch.parseAob(queryField.getText())
                    : ByteSearch.text(queryField.getText(), kind.charset, caseBox.isSelected());
        } catch (IllegalArgumentException ex) { app.error(ex.getMessage()); return; }
        boolean writableOnly = !allMemBox.isSelected();
        Win.Proc p = app.proc;
        app.runBg("Buscando", () -> ByteSearch.scan(p, pat, writableOnly, app::setProg), list -> {
            results = list;
            shownCharset = kind.charset != null ? kind.charset : StandardCharsets.UTF_8;
            showResults();
        });
    }

    void showResults() {
        model.setRowCount(0);
        Win.Proc p = app.proc;
        int n = Math.min(results.size(), MiniScanner.MAX_SHOWN);
        for (int i = 0; i < n; i++) {
            long a = results.get(i);
            byte[] b = p == null ? null : p.read(a, PREVIEW);
            model.addRow(new Object[]{app.addrText(a), b == null ? "??" : ByteSearch.hex(b), b == null ? "" : ByteSearch.preview(b, shownCharset)});
        }
        String extra = results.size() >= ByteSearch.MAX_RESULTS ? " (se paró en el máximo; afina la búsqueda)"
                : results.size() > MiniScanner.MAX_SHOWN ? " (mostrando los primeros " + MiniScanner.MAX_SHOWN + ")" : "";
        app.setStatus(String.format(" %,d resultados%s.", results.size(), extra));
    }

    private long selected() {
        int r = table.getSelectedRow();
        return r < 0 ? Win.BAD : results.get(r);
    }

    void showSelected() {
        long a = selected();
        if (a == Win.BAD) { app.info("Selecciona un resultado."); return; }
        app.memoryTab.showAddress(a);
    }

    void addToTable() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0 || app.proc == null) { app.info("Selecciona uno o más resultados."); return; }
        String name = JOptionPane.showInputDialog(this, "Nombre para la(s) entrada(s):", queryField.getText().trim());
        if (name == null) return;
        List<CheatEntry> added = new ArrayList<>();
        for (int r : rows) added.add(new CheatEntry(name, AddressExpr.of(app.proc, results.get(r)), (ValueType) typeBox.getSelectedItem()));
        app.cheatTab.addAll(added);
        app.setStatus(" Agregado a la tabla de trucos.");
    }

    void patchSelected() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0 || app.proc == null) { app.info("Selecciona uno o más resultados."); return; }
        ByteSearch.Pattern patch;
        try { patch = ByteSearch.parseAob(patchField.getText()); } catch (IllegalArgumentException ex) { app.error(ex.getMessage()); return; }
        if (JOptionPane.showConfirmDialog(this, "¿Escribir " + patch.length() + " bytes en " + rows.length + " dirección(es)?\n"
                + "Si es código del juego y los bytes no son correctos, el juego puede cerrarse.",
                "Escribir bytes", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        Win.Proc p = app.proc;
        int ok = 0;
        for (int r : rows) {
            long a = results.get(r);
            byte[] cur = p.read(a, patch.length());
            if (cur != null && p.write(a, ByteSearch.apply(patch, cur))) ok++;
        }
        showResults();
        app.setStatus(" Escrito en " + ok + " de " + rows.length + " direcciones.");
    }
}
