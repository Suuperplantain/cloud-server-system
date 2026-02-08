import java.io.*;
import java.net.*;
import java.util.*;

public class LoadBalancer {

    private static final List<Integer> STORAGE_PORTS = Arrays.asList(9101, 9102);
    private static int index = 0;

    public static void main(String[] args) throws IOException {
        int port = 9000;
        ServerSocket serverSocket = new ServerSocket(port);
        System.out.println("Load Balancer running on port " + port);

        while (true) {
            Socket client = serverSocket.accept();
            new Thread(() -> handle(client)).start();
        }
    }

    private static synchronized int nextPort() {
        int p = STORAGE_PORTS.get(index);
        index = (index + 1) % STORAGE_PORTS.size();
        return p;
    }

    private static void handle(Socket client) {
    try (
        Socket c = client;
        BufferedReader clientIn = new BufferedReader(
            new InputStreamReader(c.getInputStream()));
        BufferedWriter clientOut = new BufferedWriter(
            new OutputStreamWriter(c.getOutputStream()))
    ) {
        String line;

        while ((line = clientIn.readLine()) != null) {
            int targetPort = nextPort();

            try (
                Socket storage = new Socket("localhost", targetPort);
                BufferedReader storageIn = new BufferedReader(
                    new InputStreamReader(storage.getInputStream()));
                BufferedWriter storageOut = new BufferedWriter(
                    new OutputStreamWriter(storage.getOutputStream()))
            ) {
                storageOut.write(line);
                storageOut.newLine();
                storageOut.flush();

                String response = storageIn.readLine();
                if (response == null) break;

                clientOut.write(response);
                clientOut.newLine();
                clientOut.flush();
            }
        }
    } catch (IOException e) {
        e.printStackTrace();
    }
} 

}
