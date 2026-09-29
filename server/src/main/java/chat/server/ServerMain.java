package chat.server;

import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;

public class ServerMain {

    public static void main(String[] args) {
        int exitCode = 0;

        // Inicializacion del Communicator dentro de try-with-resources
        try (Communicator communicator = Util.initialize(args)) {

            // 1. Crear ObjectAdapter vinculado al puerto TCP 10000
            ObjectAdapter adapter =
                    communicator.createObjectAdapterWithEndpoints(
                            "ChatAdapter",
                            "default -p 10000"
                    );

            // 2. Instanciar el Servant y registrarlo con identidad "ChatService"
            ChatRoomI servant = new ChatRoomI();

            adapter.add(servant, Util.stringToIdentity("ChatService"));

            // 3. Activar el adaptador para recibir llamadas RPC
            adapter.activate();

            System.out.println("=================================================");
            System.out.println("SERVIDOR ZEROC ICE INICIADO EXITOSAMENTE");
            System.out.println("Puerto TCP: 10000 | Endpoint: default -p 10000");
            System.out.println("Identidad del Servicio: ChatService");
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
}