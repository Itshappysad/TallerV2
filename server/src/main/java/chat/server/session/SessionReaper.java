package chat.server.session;

import com.zeroc.Ice.ObjectPrx;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tercer nivel de tolerancia a fallos (RF-01): cada {@code periodSeconds}
 * hace ice_ping asíncrono al callback de cada cliente. Si alguno no
 * responde (proceso colgado, adaptador destruido, red caída), se remueve y
 * se notifica a los demás.
 *
 * Complementa al close callback y al ACM de la conexión, que no detectan
 * un cliente cuyo socket sigue abierto pero cuyo callback ya no funciona.
 */
public final class SessionReaper implements AutoCloseable {

    private final ScheduledExecutorService scheduler;

    public SessionReaper(UserRegistry registry, int periodSeconds) {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "session-reaper");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> {
            for (ClientSession s : registry.sessions()) {
                registry.sendAsync(s, ObjectPrx::ice_pingAsync, "ice_ping");
            }
        }, periodSeconds, periodSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
