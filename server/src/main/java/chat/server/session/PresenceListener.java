package chat.server.session;

import ChatApp.DisconnectReason;

/**
 * Permite que otros módulos del servidor (salas, llamadas, transferencias)
 * reaccionen a los cambios de presencia. Por ejemplo, el gestor de salas
 * (RF-03) puede sacar al usuario de todas sus salas, y el de llamadas
 * (RF-05/06) colgar las llamadas en curso cuando el usuario se cae.
 *
 * <pre>
 *   registry.addListener(new PresenceListener() {
 *       public void onUserDisconnected(ClientSession s, DisconnectReason r) {
 *           rooms.leaveAll(s.nickname());
 *       }
 *   });
 * </pre>
 *
 * Los métodos se ejecutan en hilos de Ice: deben ser rápidos y thread-safe.
 */
public interface PresenceListener {

    default void onUserConnected(ClientSession session) {
    }

    default void onUserDisconnected(ClientSession session, DisconnectReason reason) {
    }
}
