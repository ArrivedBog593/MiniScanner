package miniscanner;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

import static miniscanner.Ui.*;

/**
 * MiniScanner - escáner/editor de memoria estilo Cheat Engine (solo Windows).
 * La ventana guarda el proceso abierto y lo común a todas las pestañas; cada pestaña vive en su clase:
 * {@link ScannerTab}, {@link CheatTab}, {@link PointerTab}, {@link MemoryTab} y {@link BytesTab}.
 */
public class MiniScanner extends JFrame {
    static final int MAX_SHOWN = 1000;

    final Preferences prefs = Preferences.userNodeForPackage(MiniScanner.class);
    volatile Win.Proc proc;
    private List<Win.ProcInfo> procs = new ArrayList<>();
    volatile boolean busy;
    volatile long progDone, progTotal;
    volatile String phase = "";
    private volatile boolean cancelRequested;

    /** Nombre del juego que se cerró y al que hay que reconectarse cuando vuelva a abrirse (null = ninguno). */
    private String waitingFor;
    private long attachedAt;

    private final JComboBox<String> procBox = new JComboBox<>();
    private final JCheckBox reconnectBox = new JCheckBox("Reconectar solo", prefs.getBoolean("reconnect", true));
    private final JLabel status = new JLabel(" Elige el proceso del juego y presiona Abrir.");
    private final JTabbedPane tabs = new JTabbedPane();
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JButton cancelBtn = new JButton("Cancelar");
    private final JPanel busyPanel = row(progressBar, cancelBtn);

    final ScannerTab scannerTab;
    final CheatTab cheatTab;
    final PointerTab pointerTab;
    final MemoryTab memoryTab;
    final BytesTab bytesTab;

    MiniScanner() {
        super("MiniScanner");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(860, 640);
        setLocationRelativeTo(null);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        procBox.setPreferredSize(new Dimension(420, procBox.getPreferredSize().height));
        top.add(new JLabel("Proceso:"));
        top.add(procBox);
        top.add(button("↻", this::refreshProcs));
        top.add(button("Abrir", this::openProc));
        reconnectBox.setToolTipText("Si el juego se cierra, vuelve a conectarse solo cuando lo abras otra vez");
        reconnectBox.addActionListener(e -> prefs.putBoolean("reconnect", reconnectBox.isSelected()));
        top.add(reconnectBox);

        scannerTab = new ScannerTab(this);
        cheatTab = new CheatTab(this);
        pointerTab = new PointerTab(this);
        memoryTab = new MemoryTab(this);
        bytesTab = new BytesTab(this);
        tabs.addTab("Escáner", scannerTab);
        tabs.addTab("Tabla de trucos", cheatTab);
        tabs.addTab("Pointers", pointerTab);
        tabs.addTab("Memoria", memoryTab);
        tabs.addTab("Bytes / Texto", bytesTab);

        status.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        progressBar.setStringPainted(true);
        cancelBtn.addActionListener(e -> {
            cancelRequested = true;
            cancelBtn.setEnabled(false);
            cancelBtn.setText("Cancelando...");
        });
        busyPanel.setVisible(false);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(status, BorderLayout.CENTER);
        bottom.add(busyPanel, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        refreshProcs();
        new Timer(1000, e -> watchProcess()).start();
    }

    // ================================================================ helpers para las pestañas
    void setStatus(String text) { status.setText(text); }

    void showTab(JComponent tab) { tabs.setSelectedComponent(tab); }

    void error(String msg) { JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE); }

    void info(String msg) { JOptionPane.showMessageDialog(this, msg); }

    boolean needProc() {
        if (proc == null) { error("Primero elige el proceso del juego y presiona Abrir."); return false; }
        if (busy) { info("Espera a que termine la operación actual."); return false; }
        return true;
    }

    String addrText(long a) {
        Win.Proc p = proc;
        Win.Module m = p == null ? null : p.moduleAt(a);
        return m != null ? m.name() + "+" + AddressExpr.hex(a - m.base()) : AddressExpr.hex(a);
    }

    /** Lee y formatea un valor del juego, o "??" si no se puede. */
    String readText(long addr, ValueType t) {
        Win.Proc p = proc;
        if (p == null || addr == Win.BAD) return "??";
        byte[] raw = p.read(addr, t.size);
        return raw == null ? "??" : t.format(t.decode(raw));
    }

    JFileChooser chooser(String filterName, String ext) {
        JFileChooser fc = new JFileChooser(prefs.get("lastDir", System.getProperty("user.home")));
        fc.setFileFilter(new FileNameExtensionFilter(filterName, ext));
        return fc;
    }

    /**
     * Ejecuta una tarea larga en segundo plano con barra de progreso y botón Cancelar.
     * La tarea debe pasar {@link #setProg} como su {@link Progress}: es por donde se entera de que la cancelaron.
     */
    <T> void runBg(String label, Callable<T> task, Consumer<T> onDone) {
        busy = true;
        cancelRequested = false;
        progDone = 0; progTotal = 0; phase = label;
        cancelBtn.setEnabled(true);
        cancelBtn.setText("Cancelar");
        progressBar.setValue(0);
        progressBar.setIndeterminate(true);
        busyPanel.setVisible(true);
        Timer timer = new Timer(150, e -> {
            status.setText(" " + phase + "...");
            long total = progTotal;
            progressBar.setIndeterminate(total <= 0);
            if (total > 0) progressBar.setValue((int) (progDone * 100 / total));
        });
        timer.start();
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                timer.stop();
                busyPanel.setVisible(false);
                busy = false;
                try { onDone.accept(get()); }
                catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    if (c instanceof Progress.Cancelled) { status.setText(" Cancelado. Los resultados anteriores siguen como estaban."); return; }
                    status.setText(" Error.");
                    error(c.getMessage() != null ? c.getMessage() : c.toString());
                }
            }
        }.execute();
    }

    /** El {@link Progress} de las tareas de runBg; lanza Progress.Cancelled si se pulsó Cancelar. */
    void setProg(long done, long total) {
        if (cancelRequested) throw new Progress.Cancelled();
        progDone = done; progTotal = total;
    }

    // ================================================================ proceso
    void refreshProcs() {
        procs = Win.listProcesses();
        procBox.removeAllItems();
        for (Win.ProcInfo p : procs) procBox.addItem(p.name() + "  (PID " + p.pid() + ")");
        String last = prefs.get("lastProc", "");
        for (int i = 0; i < procs.size(); i++) if (procs.get(i).name().equalsIgnoreCase(last)) procBox.setSelectedIndex(i);
    }

    void openProc() {
        if (busy) return;
        int idx = procBox.getSelectedIndex();
        if (idx < 0) return;
        String err = attach(procs.get(idx));
        if (err != null) error(err);
        else waitingFor = null;
    }

    /** Se conecta al proceso. Devuelve null si salió bien, o el mensaje de error. */
    private String attach(Win.ProcInfo info) {
        Win.Proc np;
        try { np = new Win.Proc(info); } catch (IllegalStateException ex) { return ex.getMessage(); }
        // Las direcciones absolutas eran del proceso anterior: congelarlas escribiría basura en el nuevo
        int unfrozen = cheatTab.unfreezeDynamic();
        Win.Proc old = proc;
        proc = np;
        if (old != null) old.close();
        attachedAt = System.currentTimeMillis();
        prefs.put("lastProc", info.name());
        scannerTab.processChanged();
        status.setText(" Abierto: " + info.name() + " (" + (np.ptrSize * 8) + " bits, " + np.modules.size() + " módulos)"
                + (unfrozen > 0 ? ". Se descongelaron " + unfrozen + " direcciones dinámicas del proceso anterior." : ""));
        return null;
    }

    /** Cada segundo: si el juego se cerró, espera a que vuelva a abrirse con el mismo nombre y se reconecta. */
    private void watchProcess() {
        if (busy) return; // un escaneo a medias se queda con el proceso; se revisa al terminar
        Win.Proc p = proc;
        if (p != null) {
            if (p.isAlive()) {
                // Recién abierto, el juego sigue cargando DLLs: sin esto, sus pointers no se resolverían
                if (System.currentTimeMillis() - attachedAt < 30_000) p.refreshModules();
                return;
            }
            proc = null;
            p.close();
            scannerTab.processChanged();
            waitingFor = reconnectBox.isSelected() ? p.info.name() : null;
            status.setText(" " + p.info.name() + " se cerró." + (waitingFor != null
                    ? " Esperando a que vuelva a abrirse para reconectar..." : ""));
            return;
        }
        if (waitingFor == null || !reconnectBox.isSelected()) return;
        for (Win.ProcInfo info : Win.listProcesses()) {
            if (!info.name().equalsIgnoreCase(waitingFor)) continue;
            // Si falla (p. ej. el juego aún está arrancando), se reintenta en el siguiente segundo
            if (attach(info) == null) {
                waitingFor = null;
                refreshProcs();
                status.setText(status.getText() + " Reconectado: la tabla de trucos sigue funcionando.");
            }
            return;
        }
    }

    // ================================================================ main
    public static void main(String[] args) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            System.err.println("Este programa solo funciona en Windows.");
            return;
        }
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> {
            MiniScanner app = new MiniScanner();
            app.setVisible(true);
            // Si el programa se cierra a la mitad de un escaneo con pausa, que el juego no se quede congelado
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Win.Proc p = app.proc;
                if (p != null) p.resume();
                app.scannerTab.closeScanner();
            }));
        });
    }
}
