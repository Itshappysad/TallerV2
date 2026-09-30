package chat.server.session;

import ChatApp.ClientCallbackPrx;
import ChatApp.DisconnectReason;
import ChatApp.UserInfo;
import chat.server.Log;
import com.zeroc.Ice.Connection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Registro global y thread-safe de los usuarios conectados (RF-01).
 *
 * <p>Es la ÚNICA fuente de verdad sobre quién está en línea. Los demás
 * requerimientos (mensajes privados, salas, archivos, llamadas) deben
 * consultarlo para localizar el proxy de callback de un usuario:
 * <pre>
 *   ClientCallbackPrx cb = registry.callbackOf("ana");
 * </pre>
 * y pueden suscribirse con {@link #addListener(PresenceListener)} para
 * limpiar su propio estado cuando alguien se desconecta.</p>
 *
 * <p>Concurrencia: el estado vive en un {@link ConcurrentHashMap}; las
 * operaciones críticas son atómicas ({@code putIfAbsent} y
 * {@code remove(key, value)}), por lo que dos logins simultáneos con el mismo
 * nickname nunca pueden ganar ambos, y una remoción tardía nunca borra una
 * sesión más nueva con el mismo nombre.</p>
 */
public final class UserRegistry {

    /** Tiempo máximo que puede tardar un cliente en responder un callback. */
    public static final int CALLBACK_TIMEOUT_MS = 5000;

    private final ConcurrentHashMap<String, ClientSession> sessions = new ConcurrentHashMap<>();
    private final List<PresenceListener> listeners = new CopyOnWriteArrayList<>();

    /** Normaliza el nickname para que "Ana" y "ana" sean el mismo usuario. */
    public static String keyOf(String nickname) {
        return nickname.trim().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // Consultas (para uso de RF-01 y de los demás requerimientos)
    // ------------------------------------------------------------------

    public boolean isOnline(String nickname) {
        return nickname != null && sessions.containsKey(keyOf(nickname));
    }

    public Optional<ClientSession> find(String nickname) {
        if (nickname == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(keyOf(nickname)));
    }

    /** Proxy de callback del usuario, o {@code null} si no está conectado. */
    public ClientCallbackPrx callbackOf(String nickname) {
        return find(nickname).map(ClientSession::callback).orElse(null);
    }

    /** Nicknames conectados, en orden alfabético. */
    public List<String> onlineNicknames() {
        List<String> names = new ArrayList<>();
        for (ClientSession s : sessions.values()) {
            names.add(s.nickname());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** Usuarios conectados como structs Slice, en orden alfabético. */
    public UserInfo[] onlineUsers() {
        List<ClientSession> list = new ArrayList<>(sessions.values());
        list.sort((a, b) -> a.nickname().compareToIgnoreCase(b.nickname()));
        UserInfo[] out = new UserInfo[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i).toUserInfo();
        }
        return out;
    }

    /** Copia instantánea de las sesiones activas (segura para iterar). */
    public Collection<ClientSession> sessions() {
        return List.copyOf(sessions.values());
    }

    public int size() {
        return sessions.size();
    }

    public void addListener(PresenceListener listener) {
        listeners.add(listener);
    }

    // ------------------------------------------------------------------
    // Altas y bajas
    // ------------------------------------------------------------------

    /** Crea la sesión y la registra de forma atómica. Devuelve null si el nickname ya está tomado. */
    ClientSession tryRegister(String nickname, ClientCallbackPrx callback, Connection connection) {
        ClientSession session = new ClientSession(nickname, callback, connection);
        ClientSession previous = sessions.putIfAbsent(session.key(), session);
        return previous == null ? session : null;
    }

    /** Notifica a todos (menos al nuevo) que un usuario se conectó. */
    void announceConnected(ClientSession session) {
        for (ClientSession other : sessions.values()) {
            if (other != session) {
                sendAsync(other, cb -> cb.userConnectedAsync(session.toUserInfo()), "userConnected");
            }
        }
        for (PresenceListener l : listeners) {
            safeRun(() -> l.onUserConnected(session));
        }
    }

    /**
     * Elimina la sesión SOLO si la registrada sigue siendo exactamente esta
     * instancia, y avisa a los demás clientes.
     *
     * @return true si esta llamada fue la que la eliminó (evita avisos duplicados
     *         cuando varios mecanismos detectan la misma caída a la vez).
     */
    public boolean remove(ClientSession session, DisconnectReason reason) {
        if (!sessions.remove(session.key(), session)) {
            return false;
        }
        Log.info("Sesión cerrada: " + session.nickname() + " (" + describe(reason) + ")."
                + " En línea: " + sessions.size());

        for (ClientSession other : sessions.values()) {
            sendAsync(other, cb -> cb.userDisconnectedAsync(session.nickname(), reason), "userDisconnected");
        }
        for (PresenceListener l : listeners) {
            safeRun(() -> l.onUserDisconnected(session, reason));
        }
        return true;
    }

    /** Elimina todas las sesiones que usaban la conexión TCP indicada. */
    void removeByConnection(Connection connection, DisconnectReason reason) {
        for (ClientSession s : sessions.values()) {
            if (s.connection() != null && s.connection() == connection) {
                remove(s, reason);
            }
        }
    }

    /** Apagado del servidor: avisa a cada cliente que su sesión terminó. */
    public void terminateAll(String message) {
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (ClientSession s : sessions.values()) {
            if (sessions.remove(s.key(), s)) {
                pending.add(s.callback().sessionTerminatedAsync(DisconnectReason.ServerShutdown, message));
            }
        }
        try {
            CompletableFuture.allOf(pending.toArray(new CompletableFuture<?>[0])).get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Algunos clientes ya no respondían: no importa, el servidor se apaga.
        }
    }

    // ------------------------------------------------------------------
    // Envío de notificaciones
    // ------------------------------------------------------------------

    /**
     * Invoca un callback de forma ASÍNCRONA (no bloquea el hilo de despacho).
     * Si la invocación falla por un problema de red, el destinatario se
     * considera caído y se remueve (tolerancia a fallos).
     */
    public void sendAsync(ClientSession target,
                          Function<ClientCallbackPrx, CompletableFuture<Void>> call,
                          String operation) {
        CompletableFuture<Void> future;
        try {
            future = call.apply(target.callback());
        } catch (com.zeroc.Ice.LocalException e) {
            handleCallbackFailure(target, operation, e);
            return;
        }
        future.whenComplete((ok, error) -> {
            if (error != null) {
                handleCallbackFailure(target, operation, error);
            }
        });
    }

    private void handleCallbackFailure(ClientSession target, String operation, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause() : error;
        if (cause instanceof com.zeroc.Ice.LocalException) {
            Log.warn("Callback '" + operation + "' a " + target.nickname() + " falló ("
                    + cause.getClass().getSimpleName() + "). Se remueve al cliente.");
            remove(target, DisconnectReason.Unresponsive);
        } else {
            Log.warn("Callback '" + operation + "' a " + target.nickname() + " lanzó: " + cause);
        }
    }

    private static void safeRun(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            Log.warn("Listener de presencia lanzó una excepción: " + e);
        }
    }

    public static String describe(DisconnectReason reason) {
        switch (reason) {
            case Voluntary:      return "logout";
            case ConnectionLost: return "conexión perdida";
            case Unresponsive:   return "cliente sin respuesta";
            case ServerShutdown: return "apagado del servidor";
            default:             return reason.toString();
        }
    }
}
