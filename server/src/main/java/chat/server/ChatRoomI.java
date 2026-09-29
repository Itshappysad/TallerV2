package chat.server;

import ChatApp.ChatException;
import ChatApp.ChatMessage;
import ChatApp.ChatRoom;
import com.zeroc.Ice.Current;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public class ChatRoomI implements ChatRoom {

    // Estructuras thread-safe para soporte concurrente
    private final Set<String> onlineUsers = ConcurrentHashMap.newKeySet();
    private final List<ChatMessage> messageHistory = new CopyOnWriteArrayList<>();
    private final AtomicLong messageIdCounter = new AtomicLong(0);
    private final DateTimeFormatter timeFormat =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    @Override
    public synchronized void login(String nickname, Current current)
            throws ChatException {

        if (nickname == null || nickname.trim().isEmpty()) {
            throw new ChatException(
                    "El nickname no puede ser nulo o vacio."
            );
        }

        String cleanNick = nickname.trim();

        if (onlineUsers.contains(cleanNick)) {
            throw new ChatException(
                    "El nickname '" + cleanNick + "' ya se encuentra conectado."
            );
        }

        onlineUsers.add(cleanNick);

        System.out.println(
                "[ICE-SERVER] Usuario conectado con exito: " + cleanNick
        );

        // Notificacion interna del sistema
        String now = LocalTime.now().format(timeFormat);
        long id = messageIdCounter.incrementAndGet();

        ChatMessage joinMsg = new ChatMessage(
                id,
                "SISTEMA",
                cleanNick + " se unio a la sala.",
                now
        );

        messageHistory.add(joinMsg);
    }

    @Override
    public void postMessage(
            String nickname,
            String message,
            Current current
    ) throws ChatException {

        if (!onlineUsers.contains(nickname)) {
            throw new ChatException(
                    "Acceso denegado: El usuario debe iniciar sesion primero."
            );
        }

        if (message == null || message.trim().isEmpty()) {
            return;
        }

        long id = messageIdCounter.incrementAndGet();
        String now = LocalTime.now().format(timeFormat);

        ChatMessage msg = new ChatMessage(
                id,
                nickname,
                message.trim(),
                now
        );

        messageHistory.add(msg);

        System.out.println(
                "[" + now + "] <" + nickname + "> " + message.trim()
        );
    }

    @Override
    public ChatMessage[] getPendingMessages(
            String nickname,
            long lastMessageId,
            Current current
    ) {

        List<ChatMessage> pending = new ArrayList<>();

        for (ChatMessage msg : messageHistory) {
            if (msg.id > lastMessageId) {
                pending.add(msg);
            }
        }

        return pending.toArray(new ChatMessage[0]);
    }

    @Override
    public String[] getOnlineUsers(Current current) {
        return onlineUsers.toArray(new String[0]);
    }

    @Override
    public synchronized void logout(
            String nickname,
            Current current
    ) {

        if (onlineUsers.remove(nickname)) {

            System.out.println(
                    "[ICE-SERVER] Usuario desconectado: " + nickname
            );

            String now = LocalTime.now().format(timeFormat);
            long id = messageIdCounter.incrementAndGet();

            ChatMessage leaveMsg = new ChatMessage(
                    id,
                    "SISTEMA",
                    nickname + " ha abandonado la sala.",
                    now
            );

            messageHistory.add(leaveMsg);
        }
    }
}