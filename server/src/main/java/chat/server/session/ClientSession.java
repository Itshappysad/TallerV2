package chat.server.session;

import ChatApp.ClientCallbackPrx;
import ChatApp.UserInfo;
import com.zeroc.Ice.Connection;

import java.time.Instant;

/**
 * Datos inmutables de una sesión activa (RF-01).
 *
 * Al ser inmutable puede compartirse entre los hilos del servidor Ice sin
 * sincronización adicional; el estado mutable vive únicamente en el mapa
 * concurrente de {@link UserRegistry}.
 */
public final class ClientSession {

    private final String nickname;
    private final ClientCallbackPrx callback;
    private final Connection connection;
    private final Instant loginTime;

    ClientSession(String nickname, ClientCallbackPrx callback, Connection connection) {
        this.nickname = nickname;
        this.callback = callback;
        this.connection = connection;
        this.loginTime = Instant.now();
    }

    /** Nickname tal como el usuario lo escribió (para mostrar). */
    public String nickname() {
        return nickname;
    }

    /** Proxy de retorno para notificar eventos a este cliente. */
    public ClientCallbackPrx callback() {
        return callback;
    }

    /** Conexión TCP (cliente → servidor) por la que se hizo login. Puede ser null. */
    public Connection connection() {
        return connection;
    }

    public Instant loginTime() {
        return loginTime;
    }

    /** Struct Slice con la información pública del usuario. */
    public UserInfo toUserInfo() {
        return new UserInfo(nickname, loginTime.toEpochMilli());
    }

    /** Clave normalizada: la unicidad del nickname no distingue mayúsculas. */
    public String key() {
        return UserRegistry.keyOf(nickname);
    }

    @Override
    public String toString() {
        return nickname;
    }
}
