package chat.client;

import ChatApp.ClientCallbackPrx;
import ChatApp.SessionManagerPrx;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.InitializationData;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Properties;
import com.zeroc.Ice.Util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cliente CLI (RF-01).
 *
 * Hilos:
 *  - main: lee comandos del teclado (bloqueante).
 *  - pool del adaptador "Callback" (Ice): recibe notificaciones del servidor
 *    y las imprime a través de ConsoleUI sin bloquear la lectura.
 *
 * Opciones (todas opcionales):
 *   --Chat.Server.Host=192.168.1.10   IP/host del servidor   (localhost)
 *   --Chat.Server.Port=10000          puerto del servidor     (10000)
 *   --Callback.PublishedHost=IP       IP con la que el servidor debe
 *                                     contactar a este cliente (útil en LAN)
 */
public class ClientMain {

    private static final String HELP = String.join(System.lineSeparator(),
            "Comandos disponibles:",
            "  /login <nickname>   Inicia sesión con un nickname único",
            "  /users              Lista los usuarios en línea",
            "  /logout             Cierra la sesión actual",
            "  /help               Muestra esta ayuda",
            "  /quit               Cierra sesión y sale del programa");

    public static void main(String[] args) {
        InitializationData initData = new InitializationData();
        initData.properties = Util.createProperties(args);
        Properties p = initData.properties;
        setDefault(p, "Chat.Server.Host", "localhost");
        setDefault(p, "Chat.Server.Port", "10000");
        // Adaptador del cliente donde vive el objeto callback. "tcp" sin -p
        // => el SO asigna un puerto libre; se publican las IPs locales.
        setDefault(p, "Callback.Endpoints", "tcp");
        // La conexión con el servidor debe permanecer abierta mientras haya
        // sesión: enviamos heartbeats siempre y nunca la cerramos por inactividad.
        // Así el servidor distingue "cliente inactivo" de "cliente caído".
        setDefault(p, "Ice.ACM.Client.Timeout", "10");
        setDefault(p, "Ice.ACM.Client.Heartbeat", "3");   // HeartbeatAlways
        setDefault(p, "Ice.ACM.Client.Close", "0");       // CloseOff
        setDefault(p, "Ice.Override.ConnectTimeout", "3000");

        ConsoleUI ui = new ConsoleUI();
        AtomicBoolean closed = new AtomicBoolean(false);

        try (Communicator communicator = Util.initialize(initData)) {

            // 1. Adaptador de objetos del cliente + servant de callback
            ObjectAdapter adapter = communicator.createObjectAdapter("Callback");

            String proxyStr = "SessionManager:default -h " + p.getProperty("Chat.Server.Host")
                    + " -p " + p.getProperty("Chat.Server.Port");
            SessionManagerPrx server = SessionManagerPrx.uncheckedCast(communicator.stringToProxy(proxyStr));

            // Identidad única (UUID) para que varios clientes nunca colisionen.
            // createProxy genera un proxy DIRECTO con los endpoints del adaptador,
            // que es lo que el servidor usará para invocarnos.
            com.zeroc.Ice.Identity id = new com.zeroc.Ice.Identity(UUID.randomUUID().toString(), "callback");
            ClientCallbackPrx callbackPrx = ClientCallbackPrx.uncheckedCast(adapter.createProxy(id));

            ChatClient client = new ChatClient(server, callbackPrx, ui);
            adapter.add(new ClientCallbackI(client), id);

            // 2. Activar ANTES del login: el servidor hace ice_ping al callback
            adapter.activate();

            // 3. Ctrl+C: cierre de sesión ordenado antes de terminar
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (closed.compareAndSet(false, true)) {
                    client.logoutQuietly();
                    communicator.destroy();
                }
            }, "client-shutdown"));

            ui.println("=== Cliente de chat (ZeroC Ice) ===");
            ui.println("Servidor: " + p.getProperty("Chat.Server.Host") + ":" + p.getProperty("Chat.Server.Port"));
            ui.println(HELP);

            // 4. Bucle de comandos en el hilo principal
            runCommandLoop(client, ui);

            if (closed.compareAndSet(false, true)) {
                client.logoutQuietly();
            }
        } catch (com.zeroc.Ice.LocalException e) {
            System.err.println("[ERROR CLIENTE] " + e);
            System.exit(1);
        }
        ui.println("Hasta luego.");
        System.exit(0);
    }

    private static void runCommandLoop(ChatClient client, ConsoleUI ui) {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            ui.showPrompt();
            String line;
            try {
                line = in.readLine();
            } catch (IOException e) {
                return;
            }
            if (line == null) {          // EOF (Ctrl+D / Ctrl+Z)
                return;
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            String[] parts = line.split("\\s+", 2);
            String cmd = parts[0].toLowerCase();
            String arg = parts.length > 1 ? parts[1].trim() : "";

            switch (cmd) {
                case "/login":
                    if (arg.isEmpty()) {
                        ui.error("Uso: /login <nickname>");
                    } else {
                        client.login(arg);
                    }
                    break;
                case "/users":
                case "/who":
                    client.showUsers();
                    break;
                case "/logout":
                    client.logout();
                    break;
                case "/help":
                    ui.println(HELP);
                    break;
                case "/quit":
                case "/exit":
                    return;
                default:
                    ui.error("Comando no reconocido: " + cmd + ". Escribe /help.");
            }
        }
    }

    private static void setDefault(Properties props, String key, String value) {
        if (props.getProperty(key).isEmpty()) {
            props.setProperty(key, value);
        }
    }
}
