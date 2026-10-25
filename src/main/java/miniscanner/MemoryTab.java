package miniscanner;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.Arrays;
import java.util.List;

import static miniscanner.Ui.*;

/** Pestaña Memoria: visor/editor hexadecimal que marca en rojo los bytes que cambian. */
final class MemoryTab extends JPanel {
    static final int MEM_ROWS = 32, MEM_BYTES = MEM_ROWS * 16;

    private final MiniScanner app;
    private long memBase;
    private final byte[] memData = new byte[MEM_BYTES];
    private final boolean[] memOk = new boolean[MEM_BYTES];
    private final long[] memChanged = new long[MEM_BYTES];
    private final JTextField memAddr = new JTextField(22);
    private final JCheckBox memAuto = new JCheckBox("Auto-actualizar", true);
    private final JLabel memInfo = new JLabel(" ");
    private final JComboBox<ValueType> memType = new JComboBox<>(ValueType.values());
    private final MemModel memModel = new MemModel();
    private final JTable memTable = new JTable(memModel);

    MemoryTab(MiniScanner app) {
        super(new BorderLayout());
        this.app = app;
        memType.setSelectedItem(ValueType.INT4);
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 13);
        memTable.setFont(mono);
        memTable.setRowHeight(memTable.getFontMetrics(mono).getHeight() + 4);
        memTable.setCellSelectionEnabled(true);
        memTable.getTableHeader().setReorderingAllowed(false);
        memTable.getColumnModel().getColumn(0).setPreferredWidth(190);
        for (int c = 1; c <= 16; c++) memTable.getColumnModel().getColumn(c).setPreferredWidth(28);
        memTable.getColumnModel().getColumn(17).setPreferredWidth(150);
        DefaultTableCellRenderer render = new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                boolean recent = c >= 1 && c <= 16 && System.currentTimeMillis() - memChanged[r * 16 + c - 1] < 1500;
                comp.setForeground(recent ? Color.RED : sel ? t.getSelectionForeground() : t.getForeground());
                comp.setFont(recent ? mono.deriveFont(Font.BOLD) : mono);
                setHorizontalAlignment(c >= 1 && c <= 16 ? CENTER : LEFT);
                return comp;
            }
        };
        for (int c = 0; c < 18; c++) memTable.getColumnModel().getColumn(c).setCellRenderer(render);
        memTable.getSelectionModel().addListSelectionListener(e -> updateMemInfo());
        memTable.getColumnModel().getSelectionModel().addListSelectionListener(e -> updateMemInfo());
        memType.addActionListener(e -> updateMemInfo());

        JPanel north = new JPanel(new GridLayout(0, 1));
        north.add(row(new JLabel("Dirección:"), memAddr, button("Ir", this::memGo),
                button("▲", () -> memMove(-0x10 * 8)), button("▼", () -> memMove(0x10 * 8)),
                button("◀ Página", () -> memMove(-MEM_BYTES)), button("Página ▶", () -> memMove(MEM_BYTES)), memAuto));
        JLabel help = new JLabel(" Los bytes que cambian se pintan de rojo. Doble clic en un byte para editarlo (en hex).");
        north.add(help);
        JPanel south = new JPanel(new GridLayout(0, 1));
        memInfo.setFont(mono);
        south.add(memInfo);
        south.add(row(new JLabel("Tipo:"), memType, button("Agregar dirección seleccionada a la tabla", this::memAddToTable)));
        add(north, BorderLayout.NORTH);
        add(new JScrollPane(memTable), BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        // refresca mientras la pestaña está a la vista
        new Timer(500, e -> {
            if (isShowing() && memAuto.isSelected() && !memTable.isEditing()) refreshMem(false);
        }).start();
    }

    final class MemModel extends AbstractTableModel {
        public int getRowCount() { return MEM_ROWS; }
        public int getColumnCount() { return 18; }
        @Override public String getColumnName(int c) {
            return c == 0 ? "Dirección" : c == 17 ? "Texto" : String.format("%X", c - 1);
        }
        @Override public boolean isCellEditable(int r, int c) { return c >= 1 && c <= 16 && memOk[r * 16 + c - 1]; }

        public Object getValueAt(int r, int c) {
            if (c == 0) return app.addrText(memBase + r * 16L);
            if (c == 17) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 16; i++) {
                    int idx = r * 16 + i;
                    int b = memData[idx] & 0xFF;
                    sb.append(!memOk[idx] ? ' ' : b >= 32 && b < 127 ? (char) b : '.');
                }
                return sb.toString();
            }
            int idx = r * 16 + c - 1;
            return memOk[idx] ? String.format("%02X", memData[idx] & 0xFF) : "??";
        }

        @Override public void setValueAt(Object v, int r, int c) {
            try {
                int b = Integer.parseInt(String.valueOf(v).trim(), 16);
                if (b < 0 || b > 255) throw new NumberFormatException();
                app.proc.write(memBase + r * 16L + c - 1, new byte[]{(byte) b});
                refreshMem(false);
            } catch (NumberFormatException ex) { app.error("Escribe un byte en hexadecimal (00 a FF)."); }
        }
    }

    /** Muestra la dirección en el visor y cambia a esta pestaña. */
    void showAddress(long addr) {
        memAddr.setText(AddressExpr.hex(addr));
        memBase = Math.max(0, (addr & ~0xFL) - 0x40);
        Arrays.fill(memChanged, 0);
        refreshMem(true);
        app.showTab(this);
        int idx = (int) (addr - memBase);
        if (idx >= 0 && idx < MEM_BYTES) memTable.changeSelection(idx / 16, idx % 16 + 1, false, false);
    }

    void memGo() {
        if (app.proc == null) { app.error("Primero abre el proceso del juego."); return; }
        try {
            long a = AddressExpr.parse(memAddr.getText(), "").resolve(app.proc);
            if (a == Win.BAD) { app.error("No encontré ese módulo en el juego."); return; }
            showAddress(a);
        } catch (IllegalArgumentException ex) { app.error("Escribe una dirección como 7FF6A1B2C3D0 o \"Juego.exe\"+1A2B"); }
    }

    void memMove(long delta) {
        memBase = Math.max(0, memBase + delta);
        Arrays.fill(memChanged, 0);
        refreshMem(true);
    }

    void refreshMem(boolean fresh) {
        Win.Proc proc = app.proc;
        byte[] all = proc == null ? null : proc.read(memBase, MEM_BYTES);
        long now = System.currentTimeMillis();
        for (int r = 0; r < MEM_ROWS; r++) {
            byte[] row = all != null ? Arrays.copyOfRange(all, r * 16, r * 16 + 16)
                    : proc == null ? null : proc.read(memBase + r * 16L, 16);
            for (int i = 0; i < 16; i++) {
                int idx = r * 16 + i;
                boolean ok = row != null;
                if (ok && memOk[idx] && !fresh && memData[idx] != row[i]) memChanged[idx] = now;
                memOk[idx] = ok;
                if (ok) memData[idx] = row[i];
            }
        }
        memModel.fireTableRowsUpdated(0, MEM_ROWS - 1);
        updateMemInfo();
    }

    long memSelected() {
        int r = memTable.getSelectedRow(), c = memTable.getSelectedColumn();
        if (r < 0) return Win.BAD;
        return memBase + r * 16L + (c >= 1 && c <= 16 ? c - 1 : 0);
    }

    void updateMemInfo() {
        long a = memSelected();
        Win.Proc proc = app.proc;
        if (a == Win.BAD || proc == null) { memInfo.setText(" "); return; }
        byte[] raw = proc.read(a, 8);
        if (raw == null) { memInfo.setText(" " + app.addrText(a) + ": no se puede leer"); return; }
        StringBuilder sb = new StringBuilder(" " + app.addrText(a) + "  →");
        for (ValueType t : ValueType.values()) sb.append("  ").append(t.ctName).append(": ").append(t.format(t.decode(raw)));
        memInfo.setText(sb.toString());
    }

    void memAddToTable() {
        long a = memSelected();
        if (a == Win.BAD || app.proc == null) { app.info("Selecciona un byte en la tabla."); return; }
        String name = JOptionPane.showInputDialog(this, "Nombre para la entrada:", "Sin nombre");
        if (name == null) return;
        app.cheatTab.addAll(List.of(new CheatEntry(name, AddressExpr.of(app.proc, a), selected(memType))));
        app.setStatus(" Agregado a la tabla de trucos.");
    }
}
