package chat.client;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Salida de consola compartida por dos tipos de hilo:
 * <ul>
 *   <li>el hilo principal, que lee comandos del usuario, y</li>
 *   <li>los hilos de Ice, que entregan notificaciones asíncronas (callbacks).</li>
 * </ul>
 * Todos los métodos son {@code synchronized} para que las líneas nunca se
 * mezclen, y las notificaciones vuelven a dibujar el prompt al terminar.
 */
public final class ConsoleUI {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private String prompt = "> ";

    public synchronized void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public synchronized void showPrompt() {
        System.out.print(prompt);
        System.out.flush();
    }

    /** Respuesta directa a un comando (se imprime en el hilo del usuario). */
    public synchronized void println(String text) {
        System.out.println(text);
    }

    public synchronized void error(String text) {
        System.out.println("[!] " + text);
    }

    /**
     * Evento asíncrono que llega desde el servidor: se imprime en su propia
     * línea (sobrescribiendo el prompt) y se vuelve a mostrar el prompt.
     */
    public synchronized void event(String text) {
        String line = "\r[" + LocalTime.now().format(TIME) + "] " + text;
        int pad = Math.max(0, prompt.length() + 2 - line.length());
        System.out.println(line + " ".repeat(pad));
        System.out.print(prompt);
        System.out.flush();
    }
}
