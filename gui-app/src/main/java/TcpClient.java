import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class TcpClient {

    // If GUI runs in Docker, use "load-balancer". If GUI runs on host, set LB_HOST=localhost.
    private static final String HOST = System.getenv().getOrDefault("LB_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("LB_PORT", "9000"));

    public static String send(String line) throws IOException {
        try (Socket s = new Socket(HOST, PORT);
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))) {

            out.write(line);
            out.newLine();
            out.flush();

            String resp = in.readLine();
            return (resp == null) ? "ERROR: Empty response" : resp;
        }
    }
}
