#pragma once

module ChatApp {

    // Estructura que modela un mensaje del chat
    struct ChatMessage {
        long id;
        string sender;
        string text;
        string timestamp;
    };

    // Secuencias tipadas (equivalentes a arreglos/listas en Java)
    sequence<ChatMessage> MessageSeq;
    sequence<string> UserSeq;

    // Excepción de usuario para validaciones de negocio
    exception ChatException {
        string reason;
    };

    // Interfaz remota provista por el servidor Ice
    interface ChatRoom {

        // Registra un usuario; falla si el nickname ya existe
        void login(string nickname) throws ChatException;

        // Envía un mensaje a la sala compartida
        void postMessage(string nickname, string message) throws ChatException;

        // Consulta de mensajes pendientes
        // idempotent permite reintentos seguros de red
        idempotent MessageSeq getPendingMessages(
            string nickname,
            long lastMessageId
        );

        // Retorna la lista de usuarios activos actualmente
        idempotent UserSeq getOnlineUsers();

        // Cierra sesión y desvincula al usuario
        void logout(string nickname);
    };
};
