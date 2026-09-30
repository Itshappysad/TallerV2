package chat.server;

import chat.server.session.SessionManagerI;
import chat.server.session.SessionReaper;
import chat.server.session.UserRegistry;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.InitializationData;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Properties;
import com.zeroc.Ice.Util;

public class ServerMain {

    public static void main(String[] args) {
        int exitCode = 0;

        // Propiedades por defecto; cualquiera se puede sobrescribir con
        // --Propiedad=valor o con un archivo --Ice.Config=config.server
        InitializationData initData = new InitializationData();
        initData.properties = Util.createProperties(args);
        setDefault(initData.properties, "ChatAdapter.Endpoints", "default -p 10000");
        // El servidor Ice es multihilo: varias invocaciones se despachan en paralelo
        setDefault(initData.properties, "Ice.ThreadPool.Server.Size", "4");
        setDefault(initData.properties, "Ice.ThreadPool.Server.SizeMax", "16");
        // No esperar demasiado al conectar con el callback de un cliente inalcanzable
        setDefault(initData.properties, "Ice.Override.ConnectTimeout", "3000");
        // RF-01: segundos sin heartbeats para declarar caída una conexión
        setDefault(initData.properties, "Session.ConnectionTimeout", "30");
        // RF-01: cada cuántos segundos se hace ping a los callbacks
        setDefault(initData.properties, "Session.ReaperPeriod", "10");

        // Inicializacion del Communicator dentro de try-with-resources
        try (Communicator communicator = Util.initialize(initData)) {
            Properties props = communicator.getProperties();

            // 1. Crear ObjectAdapter vinculado al puerto TCP 10000
            ObjectAdapter adapter = communicator.createObjectAdapter("ChatAdapter");

            // 2. Servants
            //    - ChatService    : sala compartida (RF-03)
            //    - SessionManager : sesión, presencia y callbacks (RF-01)
            ChatRoomI servant = new ChatRoomI();
            adapter.add(servant, Util.stringToIdentity("ChatService"));

            UserRegistry registry = new UserRegistry();
            SessionManagerI sessionManager = new SessionManagerI(
                    registry, props.getPropertyAsInt("Session.ConnectionTimeout"));
            adapter.add(sessionManager, Util.stringToIdentity("SessionManager"));

            SessionReaper reaper = new SessionReaper(registry, props.getPropertyAsInt("Session.ReaperPeriod"));

            // 3. Activar el adaptador para recibir llamadas RPC
            adapter.activate();

            // Apagado ordenado con Ctrl+C: avisar a los clientes y liberar recursos
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Log.info("Apagando servidor: notificando a " + registry.size() + " cliente(s)...");
                reaper.close();
                registry.terminateAll("El servidor se está apagando.");
                communicator.destroy();
            }, "shutdown-hook"));

            System.out.println("=================================================");
            System.out.println("SERVIDOR ZEROC ICE INICIADO EXITOSAMENTE");
            System.out.println("Endpoints: " + props.getProperty("ChatAdapter.Endpoints"));
            System.out.println("Servicios: ChatService (RF-03), SessionManager (RF-01)");
            System.out.println("=================================================");
            System.out.println("Esperando llamadas remotas de clientes...");

            // 4. Bloquear el hilo principal hasta orden de apagado (Ctrl+C)
            communicator.waitForShutdown();

        } catch (Exception e) {
            System.err.println(
                    "[ERROR SERVIDOR] Fallo critico: " + e.getMessage()
            );

            e.printStackTrace();
            exitCode = 1;
        }

        System.exit(exitCode);
    }

    private static void setDefault(Properties props, String key, String value) {
        if (props.getProperty(key).isEmpty()) {
            props.setProperty(key, value);
        }
    }
}
