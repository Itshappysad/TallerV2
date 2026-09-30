package chat.server.session;

import ChatApp.ClientCallbackPrx;
import ChatApp.DisconnectReason;
import ChatApp.InvalidCallbackException;
import ChatApp.InvalidNicknameException;
import ChatApp.NicknameInUseException;
import ChatApp.NotLoggedInException;
import ChatApp.SessionManager;
import ChatApp.UserInfo;
import chat.server.Log;
import com.zeroc.Ice.ACMClose;
import com.zeroc.Ice.ACMHeartbeat;
import com.zeroc.Ice.Connection;
import com.zeroc.Ice.Current;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * Servant del servicio de sesiones (RF-01): login con nickname único,
 * registro del callback, presencia en tiempo real y logout ordenado.
 */
public class SessionManagerI implements SessionManager {

    /** 3 a 20 caracteres: letras (con tildes), dígitos, '_', '-' o '.'. Sin espacios. */
    private static final Pattern NICK_PATTERN = Pattern.compile("^[\\p{L}\\p{N}_.\\-]{3,20}$");

    /** Tiempo máximo para verificar si una sesión con el mismo nickname sigue viva. */
    private static final int LIVENESS_PING_TIMEOUT_MS = 2000;

    private final UserRegistry registry;
    private final int connectionTimeoutSeconds;

    /**
     * @param connectionTimeoutSeconds segundos sin tráfico (ni heartbeats) tras los
     *        cuales Ice cierra a la fuerza la conexión de un cliente caído.
     */
    public SessionManagerI(UserRegistry registry, int connectionTimeoutSeconds) {
        this.registry = registry;
        this.connectionTimeoutSeconds = connectionTimeoutSeconds;
    }

    @Override
    public UserInfo[] login(String nickname, ClientCallbackPrx callback, Current current)
            throws NicknameInUseException, InvalidNicknameException, InvalidCallbackException {

        String nick = validateNickname(nickname);

        if (callback == null) {
            throw new InvalidCallbackException("Debe registrar un proxy de callback para iniciar sesión.");
        }
        ClientCallbackPrx cb = callback.ice_invocationTimeout(UserRegistry.CALLBACK_TIMEOUT_MS);

        // 1. Verificación rápida de unicidad. Si el dueño actual del nickname
        //    ya no responde (cliente caído que aún no se ha detectado), se
        //    libera el nombre en lugar de bloquearlo indefinidamente.
        Optional<ClientSession> existing = registry.find(nick);
        if (existing.isPresent()) {
            if (isAlive(existing.get())) {
                throw nicknameInUse(nick);
            }
            Log.warn("La sesión previa de " + existing.get().nickname() + " no responde; se libera el nickname.");
            registry.remove(existing.get(), DisconnectReason.Unresponsive);
        }

        // 2. Comprobar que el servidor puede alcanzar el callback ANTES de
        //    registrarlo (detecta firewalls o PublishedHost mal configurado).
        try {
            cb.ice_ping();
        } catch (com.zeroc.Ice.LocalException e) {
            throw new InvalidCallbackException(
                    "El servidor no pudo contactar su callback (" + e.getClass().getSimpleName()
                            + "). Revise la IP publicada (Callback.PublishedHost) o el firewall.");
        }

        // 3. Registro atómico: si dos clientes piden el mismo nickname al
        //    mismo tiempo, putIfAbsent garantiza que solo uno gane.
        ClientSession session = registry.tryRegister(nick, cb, current.con);
        if (session == null) {
            throw nicknameInUse(nick);
        }

        watchConnection(current.con);

        Log.info("Sesión iniciada: " + nick + " desde " + remoteAddress(current.con)
                + ". En línea: " + registry.size());

        // 4. Presencia: avisar a todos los demás (asíncrono, no bloquea).
        registry.announceConnected(session);

        return registry.onlineUsers();
    }

    @Override
    public void logout(String nickname, Current current) throws NotLoggedInException {
        String nick = nickname == null ? "" : nickname.trim();
        ClientSession session = registry.find(nick).orElseThrow(
                () -> new NotLoggedInException("No hay una sesión activa para '" + nick + "'.", nick));

        // Solo el cliente dueño de la sesión (misma conexión) puede cerrarla.
        if (current.con != null && session.connection() != null && current.con != session.connection()) {
            throw new NotLoggedInException("La sesión de '" + nick + "' pertenece a otro cliente.", nick);
        }

        registry.remove(session, DisconnectReason.Voluntary);
    }

    @Override
    public UserInfo[] getOnlineUsers(Current current) {
        return registry.onlineUsers();
    }

    // ------------------------------------------------------------------

    private static String validateNickname(String nickname) throws InvalidNicknameException {
        String nick = nickname == null ? "" : nickname.trim();
        if (!NICK_PATTERN.matcher(nick).matches()) {
            throw new InvalidNicknameException(
                    "Nickname inválido: use de 3 a 20 caracteres (letras, números, '_', '-' o '.') sin espacios.",
                    nick);
        }
        return nick;
    }

    private static NicknameInUseException nicknameInUse(String nick) {
        return new NicknameInUseException(
                "El nickname '" + nick + "' ya está en uso por otra sesión activa.", nick);
    }

    private static boolean isAlive(ClientSession session) {
        try {
            session.callback().ice_invocationTimeout(LIVENESS_PING_TIMEOUT_MS).ice_ping();
            return true;
        } catch (com.zeroc.Ice.LocalException e) {
            return false;
        }
    }

    /**
     * Detección de caídas abruptas a nivel de conexión:
     * <ul>
     *   <li>Si el proceso cliente muere, el SO cierra el socket y Ice invoca
     *       de inmediato el close callback.</li>
     *   <li>Si la red se corta sin cerrar el socket, ACM detecta la ausencia de
     *       heartbeats y cierra la conexión tras {@code connectionTimeoutSeconds}.</li>
     * </ul>
     */
    private void watchConnection(Connection con) {
        if (con == null) {
            return; // invocación colocada (mismo proceso): no hay socket que vigilar
        }
        try {
            con.setACM(OptionalInt.of(connectionTimeoutSeconds),
                    Optional.of(ACMClose.CloseOnIdleForceful),
                    Optional.of(ACMHeartbeat.HeartbeatAlways));
            con.setCloseCallback(closed -> registry.removeByConnection(closed, DisconnectReason.ConnectionLost));
        } catch (com.zeroc.Ice.ConnectionManuallyClosedException e) {
            registry.removeByConnection(con, DisconnectReason.ConnectionLost);
        }
    }

    private static String remoteAddress(Connection con) {
        if (con == null) {
            return "(local)";
        }
        try {
            com.zeroc.Ice.ConnectionInfo info = con.getInfo();
            while (info != null && !(info instanceof com.zeroc.Ice.IPConnectionInfo)) {
                info = info.underlying;
            }
            if (info != null) {
                com.zeroc.Ice.IPConnectionInfo ip = (com.zeroc.Ice.IPConnectionInfo) info;
                return ip.remoteAddress + ":" + ip.remotePort;
            }
        } catch (com.zeroc.Ice.LocalException ignored) {
            // sin información disponible
        }
        return "(desconocido)";
    }
}
