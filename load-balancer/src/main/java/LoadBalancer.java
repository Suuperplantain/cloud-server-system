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
    private static boolean isStorageHealthy(int port) {
    try (Socket s = new Socket()) {
        // Fast fail if the node is down
        s.connect(new InetSocketAddress("localhost", port), 800);
        s.setSoTimeout(800);

        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream()));
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));

        out.write("PING");
        out.newLine();
        out.flush();

        String resp = in.readLine();
        return resp != null && resp.toUpperCase().startsWith("PONG");
    } catch (IOException e) {
        return false;
    }
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
            int attempts = STORAGE_PORTS.size();
int targetPort = -1;

while (attempts-- > 0) {
    int p = nextPort();
    if (isStorageHealthy(p)) {
        targetPort = p;
        break;
    }
}

if (targetPort == -1) {
    clientOut.write("ERROR: No storage nodes available");
    clientOut.newLine();
    clientOut.flush();
    continue;
}


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
