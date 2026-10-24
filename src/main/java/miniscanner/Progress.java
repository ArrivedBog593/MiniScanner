package miniscanner;

/**
 * Avance de una tarea larga. Las tareas lo llaman a menudo dentro de sus bucles, así que también sirve
 * para cancelarlas: si el usuario pulsó Cancelar, update() lanza {@link Cancelled} y la tarea se corta ahí.
 */
interface Progress {
    void update(long done, long total);

    /** La tarea se canceló a pedido del usuario (no es un error). */
    final class Cancelled extends RuntimeException {
        Cancelled() { super("Cancelado.", null, false, false); }
    }
}
