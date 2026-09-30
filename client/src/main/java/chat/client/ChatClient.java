package chat.client;

import ChatApp.ClientCallbackPrx;
import ChatApp.DisconnectReason;
import ChatApp.InvalidCallbackException;
import ChatApp.InvalidNicknameException;
import ChatApp.NicknameInUseException;
import ChatApp.NotLoggedInException;
import ChatApp.SessionManagerPrx;
import ChatApp.UserInfo;
import com.zeroc.Ice.Connection;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lógica de sesión del cliente (RF-01): login, logout, lista de usuarios y
 * reacción a los eventos de presencia que llegan por callback.
 *
 * El estado se comparte entre el hilo de consola y los hilos de Ice, por eso
 * se usan campos volatile y colecciones concurrentes.
 */
public final class ChatClient {

    private final SessionManagerPrx server;
    private final ClientCallbackPrx callbackPrx;
    private final ConsoleUI ui;

    /** Usuarios en línea según el último login + eventos de presencia. */
    private static final DateTimeFormatter HOUR =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private final Set<String> onlineUsers = ConcurrentHashMap.newKeySet();

    /** Nickname de la sesión actual; null si no hay sesión. */
    private volatile String nickname;
    /** Conexión TCP con el servidor usada por la sesión actual. */
    private volatile Connection sessionConnection;

    public ChatClient(SessionManagerPrx server, ClientCallbackPrx callbackPrx, ConsoleUI ui) {
        this.server = server;
        this.callbackPrx = callbackPrx;
        this.ui = ui;
    }

    public boolean isLoggedIn() {
        return nickname != null;
    }

    public String nickname() {
        return nickname;
    }

    // ------------------------------------------------------------------
    // Comandos del usuario (hilo de consola)
    // ------------------------------------------------------------------

    public void login(String requestedNick) {
        if (isLoggedIn()) {
            ui.error("Ya tienes una sesión activa como '" + nickname + "'. Usa /logout primero.");
            return;
        }
        try {
            UserInfo[] users = server.login(requestedNick, callbackPrx);

            nickname = requestedNick.trim();
            onlineUsers.clear();
            for (UserInfo u : users) {
                onlineUsers.add(u.nickname);
            }
            watchConnection();

            ui.setPrompt("[" + nickname + "]> ");
            ui.println("Sesión iniciada como '" + nickname + "'.");
            printUsers(users);
        } catch (NicknameInUseException e) {
            ui.error(e.reason);
        } catch (InvalidNicknameException e) {
            ui.error(e.reason);
        } catch (InvalidCallbackException e) {
            ui.error(e.reason);
        } catch (com.zeroc.Ice.LocalException e) {
            ui.error("No se pudo contactar el servidor (" + e.getClass().getSimpleName() + ").");
        }
    }

    public void logout() {
        if (!isLoggedIn()) {
            ui.error("No hay una sesión activa.");
            return;
        }
        String nick = nickname;
        try {
            server.logout(nick);
            ui.println("Sesión de '" + nick + "' cerrada correctamente.");
        } catch (NotLoggedInException e) {
            ui.error(e.reason);
        } catch (com.zeroc.Ice.LocalException e) {
            ui.error("El servidor no respondió al logout (" + e.getClass().getSimpleName()
                    + "). La sesión se cerró localmente.");
        } finally {
            endSession();
        }
    }

    public void showUsers() {
        try {
            printUsers(server.getOnlineUsers());
        } catch (com.zeroc.Ice.LocalException e) {
            ui.error("No se pudo contactar el servidor (" + e.getClass().getSimpleName() + ").");
        }
    }

    /** Logout silencioso y rápido para /quit o Ctrl+C. */
    public void logoutQuietly() {
        String nick = nickname;
        if (nick == null) {
            return;
        }
        endSession();
        try {
            server.ice_invocationTimeout(2000).logout(nick);
        } catch (Exception ignored) {
            // Si el servidor no responde, el close callback / ACM lo detectará.
        }
    }

    // ------------------------------------------------------------------
    // Eventos del servidor (hilos de Ice, vía ClientCallbackI)
    // ------------------------------------------------------------------

    void onUserConnected(UserInfo user) {
        if (onlineUsers.add(user.nickname)) {
            ui.event(">> " + user.nickname + " se conectó. (" + onlineUsers.size() + " en línea)");
        }
    }

    void onUserDisconnected(String nick, DisconnectReason reason) {
        if (onlineUsers.remove(nick)) {
            ui.event("<< " + nick + " se desconectó [" + describe(reason) + "]. ("
                    + onlineUsers.size() + " en línea)");
        }
    }

    void onSessionTerminated(DisconnectReason reason, String message) {
        if (isLoggedIn()) {
            endSession();
            ui.event("!! El servidor cerró tu sesión [" + describe(reason) + "]: " + message);
        }
    }

    // ------------------------------------------------------------------

    /**
     * Si la conexión TCP con el servidor se cae, el servidor ya habrá
     * eliminado la sesión; el cliente lo refleja de inmediato.
     */
    private void watchConnection() {
        try {
            Connection con = server.ice_getConnection();
            sessionConnection = con;
            con.setCloseCallback(closed -> {
                if (closed == sessionConnection && isLoggedIn()) {
                    endSession();
                    ui.event("!! Se perdió la conexión con el servidor. Usa /login para volver a entrar.");
                }
            });
        } catch (com.zeroc.Ice.LocalException e) {
            // Sin conexión que vigilar: los errores aparecerán en la próxima invocación.
        }
    }

    private void endSession() {
        nickname = null;
        sessionConnection = null;
        onlineUsers.clear();
        ui.setPrompt("> ");
    }

    private void printUsers(UserInfo[] users) {
        List<String> shown = new ArrayList<>();
        for (UserInfo u : users) {
            String since = HOUR.format(Instant.ofEpochMilli(u.connectedSince));
            String me = u.nickname.equalsIgnoreCase(nickname == null ? "" : nickname) ? " (tú)" : "";
            shown.add(u.nickname + me + " desde " + since);
        }
        ui.println("Usuarios en línea (" + users.length + "): "
                + (shown.isEmpty() ? "(ninguno)" : String.join(", ", shown)));
    }

    private static String describe(DisconnectReason reason) {
        switch (reason) {
            case Voluntary:      return "logout";
            case ConnectionLost: return "conexión perdida";
            case Unresponsive:   return "sin respuesta";
            case ServerShutdown: return "servidor apagado";
            default:             return reason.toString();
        }
    }
}
