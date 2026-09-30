package chat.server;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Bitácora mínima con marca de tiempo, segura para múltiples hilos. */
public final class Log {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Log() {
    }

    public static synchronized void info(String msg) {
        System.out.println("[" + LocalTime.now().format(TIME) + "] [INFO] " + msg);
    }

    public static synchronized void warn(String msg) {
        System.out.println("[" + LocalTime.now().format(TIME) + "] [WARN] " + msg);
    }
}
