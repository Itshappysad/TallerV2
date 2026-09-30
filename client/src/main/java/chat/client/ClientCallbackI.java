package chat.client;

import ChatApp.ClientCallback;
import ChatApp.DisconnectReason;
import ChatApp.UserInfo;
import com.zeroc.Ice.Current;

/**
 * Servant que vive en el CLIENTE. El servidor lo invoca a través del proxy
 * que el cliente registró en login(). Se ejecuta en los hilos del
 * adaptador "Callback", nunca en el hilo que lee la consola.
 */
public class ClientCallbackI implements ClientCallback {

    private final ChatClient client;

    public ClientCallbackI(ChatClient client) {
        this.client = client;
    }

    @Override
    public void userConnected(UserInfo user, Current current) {
        client.onUserConnected(user);
    }

    @Override
    public void userDisconnected(String nickname, DisconnectReason reason, Current current) {
        client.onUserDisconnected(nickname, reason);
    }

    @Override
    public void sessionTerminated(DisconnectReason reason, String message, Current current) {
        client.onSessionTerminated(reason, message);
    }
}
