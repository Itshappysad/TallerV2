// =====================================================================
//  Session.ice — RF-01: Gestión de Sesión, Presencia y Registro de Clientes
// =====================================================================
//  Contrato Slice del servicio de sesiones. Se mantiene en un archivo
//  aparte de Chat.ice (salas, RF-03) para que cada requerimiento tenga su
//  propio contrato y los equipos no se pisen al editarlos.
//
//  Reutiliza la excepción base ChatException definida en Chat.ice.
// =====================================================================

#pragma once

#include "Chat.ice"

module ChatApp {

    // -----------------------------------------------------------------
    // Tipos de datos
    // -----------------------------------------------------------------

    // Información pública de un usuario conectado
    struct UserInfo {
        string nickname;       // identificador único de la sesión
        long   connectedSince; // instante del login (epoch en milisegundos)
    };

    sequence<UserInfo> UserInfoSeq;

    // Motivo por el que un usuario deja de estar en línea
    enum DisconnectReason {
        Voluntary,       // hizo logout de forma ordenada
        ConnectionLost,  // la conexión TCP se cerró o expiró (ACM)
        Unresponsive,    // su callback dejó de responder a los pings
        ServerShutdown   // el servidor se está apagando
    };

    // -----------------------------------------------------------------
    // Excepciones personalizadas (todas heredan de ChatException)
    // -----------------------------------------------------------------

    // El nickname ya está siendo usado por otra sesión activa
    exception NicknameInUseException extends ChatException {
        string nickname;
    };

    // El nickname no cumple el formato (vacío, espacios, longitud, etc.)
    exception InvalidNicknameException extends ChatException {
        string nickname;
    };

    // El proxy de callback es nulo o el servidor no logra contactarlo
    exception InvalidCallbackException extends ChatException {
    };

    // Operación que exige sesión activa invocada sin haber hecho login
    exception NotLoggedInException extends ChatException {
        string nickname;
    };

    // -----------------------------------------------------------------
    // Interfaz de retorno (callback): la implementa el CLIENTE
    // -----------------------------------------------------------------
    // El servidor la invoca para notificar eventos de forma asíncrona.
    // Los RF siguientes (mensajes privados, salas, archivos, llamadas)
    // pueden extenderla: interface ChatCallback extends ClientCallback {...}
    interface ClientCallback {

        // Otro usuario inició sesión
        void userConnected(UserInfo user);

        // Otro usuario cerró sesión o fue removido por falla
        void userDisconnected(string nickname, DisconnectReason reason);

        // El servidor cerró la sesión de ESTE cliente
        void sessionTerminated(DisconnectReason reason, string message);
    };

    // -----------------------------------------------------------------
    // Interfaz del servidor (identidad Ice: "SessionManager")
    // -----------------------------------------------------------------
    interface SessionManager {

        // Inicia sesión registrando el proxy de callback del cliente.
        // Devuelve los usuarios en línea (incluido el propio).
        UserInfoSeq login(string nickname, ClientCallback* callback)
            throws NicknameInUseException,
                   InvalidNicknameException,
                   InvalidCallbackException;

        // Cierre de sesión ordenado: libera el proxy y notifica a los demás
        void logout(string nickname) throws NotLoggedInException;

        // Usuarios conectados en este instante (orden alfabético)
        idempotent UserInfoSeq getOnlineUsers();
    };
};
