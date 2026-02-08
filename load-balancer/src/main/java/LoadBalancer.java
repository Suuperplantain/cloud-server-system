import java.io.*;
import java.net.*;
import java.util.*;

public class LoadBalancer {

  private static final List<String> STORAGE_HOSTS = Arrays.asList("storage1", "storage2");
  private static final List<Integer> STORAGE_PORTS = Arrays.asList(9101, 9102);
  private static int index = 0;

  private static final int MIN_DELAY_SEC = Integer.parseInt(
      Optional.ofNullable(System.getenv("MIN_DELAY_SEC")).orElse("30")
  );
  private static final int MAX_DELAY_SEC = Integer.parseInt(
      Optional.ofNullable(System.getenv("MAX_DELAY_SEC")).orElse("90")
  );

  public static void main(String[] args) throws IOException {
    int port = 9000;
    ServerSocket serverSocket = new ServerSocket(port);
    System.out.println("Load Balancer running on port " + port);
    while (true) {
      Socket client = serverSocket.accept();
      new Thread(() -> handle(client)).start();
    }
  }

  private static synchronized int nextIndex() {
    int i = index;
    index = (index + 1) % STORAGE_HOSTS.size();
    return i;
  }

  private static boolean isStorageHealthy(String host, int port) {
    try (Socket s = new Socket()) {
      s.connect(new InetSocketAddress(host, port), 800);
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

  private static void maybeDelay() {
    int min = Math.min(MIN_DELAY_SEC, MAX_DELAY_SEC);
    int max = Math.max(MIN_DELAY_SEC, MAX_DELAY_SEC);
    int delay = (min == max) ? min : (min + new Random().nextInt(max - min + 1));
    try { Thread.sleep(delay * 1000L); } catch (InterruptedException ignored) {}
  }

  private static void handle(Socket client) {
    try (
      Socket c = client;
      BufferedReader clientIn = new BufferedReader(new InputStreamReader(c.getInputStream()));
      BufferedWriter clientOut = new BufferedWriter(new OutputStreamWriter(c.getOutputStream()))
    ) {
      String line;
      while ((line = clientIn.readLine()) != null) {

        int attempts = STORAGE_HOSTS.size();
        int targetIdx = -1;

        while (attempts-- > 0) {
          int i = nextIndex();
          String host = STORAGE_HOSTS.get(i);
          int port = STORAGE_PORTS.get(i);
          if (isStorageHealthy(host, port)) { targetIdx = i; break; }
        }

        if (targetIdx == -1) {
          clientOut.write("ERROR: No storage nodes available");
          clientOut.newLine();
          clientOut.flush();
          continue;
        }

        // Artificial latency (3090s by default)
        maybeDelay();

        String host = STORAGE_HOSTS.get(targetIdx);
        int port = STORAGE_PORTS.get(targetIdx);

        try (
          Socket storage = new Socket(host, port);
          BufferedReader storageIn = new BufferedReader(new InputStreamReader(storage.getInputStream()));
          BufferedWriter storageOut = new BufferedWriter(new OutputStreamWriter(storage.getOutputStream()))
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
