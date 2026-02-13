import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

public class TcpClient {

    // If GUI runs in Docker, use "load-balancer". If GUI runs on host, set LB_HOST=localhost.
    private static final String HOST = System.getenv().getOrDefault("LB_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("LB_PORT", "9000"));

    public static String send(String line) throws IOException {
        try (Socket s = new Socket(HOST, PORT);
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))) {

            // Allow for long delays from the load balancer.
            s.setSoTimeout(120_000);

            out.write(line);
            out.newLine();
            out.flush();

            String first = in.readLine();
            if (first == null) {
                return "ERROR: Empty response";
            }

            StringBuilder sb = new StringBuilder(first);

            // After the first line, use a short timeout so we don't hang forever
            // waiting for optional additional lines (e.g. STATS output).
            s.setSoTimeout(200);
            while (true) {
                try {
                    String next = in.readLine();
                    if (next == null) {
                        break;
                    }
                    sb.append('\n').append(next);
                } catch (SocketTimeoutException e) {
                    // No more data within the short timeout; stop reading.
                    break;
                }
            }

            return sb.toString();
        }
    }
}
