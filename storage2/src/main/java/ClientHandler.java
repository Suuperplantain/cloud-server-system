import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class ClientHandler implements Runnable {
  private final Socket socket;

  // Storage root inside container
  private static final Path ROOT = Paths.get(
      Optional.ofNullable(System.getenv("STORAGE_ROOT")).orElse("/data")
  ).toAbsolutePath().normalize();

  private static final String STORAGE_NAME =
      Optional.ofNullable(System.getenv("STORAGE_NAME")).orElse("STORAGE-2");

  // AES-256 key (32 bytes) from env as hex (64 chars). If missing -> uses a fixed dev key.
  private static final SecretKey AES_KEY = new SecretKeySpec(
      hexToBytes(Optional.ofNullable(System.getenv("AES_KEY_HEX"))
        .orElse("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")),
      "AES"
  );

  private static final SecureRandom RNG = new SecureRandom();
  private static final int CHUNK_SIZE = Integer.parseInt(
      Optional.ofNullable(System.getenv("CHUNK_SIZE")).orElse("65536") // 64KB
  );

  // Per-file locks
  private static final ConcurrentHashMap<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
  private static ReentrantLock lockFor(String k) { return LOCKS.computeIfAbsent(k, __ -> new ReentrantLock()); }

  // Per-connection working directory
  private Path cwd = ROOT;

  public ClientHandler(Socket socket) { this.socket = socket; }

  @Override
  public void run() {
    try {
      Files.createDirectories(ROOT);
      cwd = ROOT;
    } catch (IOException ignored) {}

    try (
      BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
    ) {
      String line;
      while ((line = in.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty()) continue;

        String resp;
        try {
          resp = handleCommand(line);
        } catch (Exception e) {
          resp = "ERROR: " + e.getClass().getSimpleName() + ": " + safeMsg(e.getMessage());
        }

        out.write(resp);
        out.newLine();
        out.flush();

        if ("GOODBYE".equals(resp)) break;
      }
    } catch (IOException e) {
      System.err.println("Client handler error: " + e.getMessage());
    } finally {
      try { socket.close(); } catch (IOException ignored) {}
    }
  }

  private String handleCommand(String line) throws Exception {
    String[] parts = splitCmd(line);
    String cmd = parts[0].toUpperCase(Locale.ROOT);

    switch (cmd) {
      case "PING":
        return "PONG FROM " + STORAGE_NAME;

      case "WHOAMI":
        return STORAGE_NAME;

      case "EXIT":
        return "GOODBYE";

      case "PWD":
        return cwd.toString();

      case "CD": {
        if (parts.length < 2) return "ERROR: usage CD <path>";
        Path next = resolvePath(parts[1]);
        if (!Files.exists(next) || !Files.isDirectory(next)) return "ERROR: not a directory";
        cwd = next.normalize();
        return "OK";
      }

      case "MKDIR": {
        if (parts.length < 2) return "ERROR: usage MKDIR <dir>";
        Path p = resolvePath(parts[1]);
        Files.createDirectories(p);
        return "OK";
      }

      case "LS": {
        Path p = (parts.length >= 2) ? resolvePath(parts[1]) : cwd;
        if (!Files.exists(p)) return "ERROR: path not found";
        if (Files.isRegularFile(p)) return p.getFileName().toString();
        StringBuilder sb = new StringBuilder();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
          for (Path x : ds) {
            sb.append(x.getFileName().toString());
            if (Files.isDirectory(x)) sb.append("/");
            sb.append(" ");
          }
        }
        return sb.toString().trim();
      }

      case "TREE": {
        Path p = (parts.length >= 2) ? resolvePath(parts[1]) : cwd;
        if (!Files.exists(p)) return "ERROR: path not found";
        return tree(p);
      }

      // FILE PUT: PUT <filename> <base64Data>
      // Stores encrypted chunk files: <name>.part00000, ... plus <name>.meta
      case "PUT": {
        if (parts.length < 3) return "ERROR: usage PUT <filename> <base64>";
        String name = parts[1];
        byte[] plain = Base64.getDecoder().decode(parts[2]);
        byte[] enc = aesGcmEncrypt(plain);

        ReentrantLock L = lockFor(keyFor(name));
        L.lock();
        try {
          writeChunked(name, enc);
          return "OK: STORED " + name + " BYTES=" + plain.length;
        } finally { L.unlock(); }
      }

      // GET: GET <filename> -> returns base64(plaintext)
      case "GET": {
        if (parts.length < 2) return "ERROR: usage GET <filename>";
        String name = parts[1];

        ReentrantLock L = lockFor(keyFor(name));
        L.lock();
        try {
          byte[] enc = readChunked(name);
          byte[] plain = aesGcmDecrypt(enc);
          return "OK: " + Base64.getEncoder().encodeToString(plain);
        } finally { L.unlock(); }
      }

      // DEL: DEL <filename>
      case "DEL": {
        if (parts.length < 2) return "ERROR: usage DEL <filename>";
        String name = parts[1];

        ReentrantLock L = lockFor(keyFor(name));
        L.lock();
        try {
          deleteChunked(name);
          return "OK: DELETED " + name;
        } finally { L.unlock(); }
      }

      // MV: MV <src> <dst>  (for stored chunked files)
      case "MV": {
        if (parts.length < 3) return "ERROR: usage MV <src> <dst>";
        String src = parts[1], dst = parts[2];

        ReentrantLock A = lockFor(keyFor(src));
        ReentrantLock B = lockFor(keyFor(dst));
        // avoid deadlock by consistent order
        List<ReentrantLock> locks = Arrays.asList(A, B);
        locks.sort(Comparator.comparingInt(System::identityHashCode));
        locks.get(0).lock(); locks.get(1).lock();
        try {
          moveChunked(src, dst);
          return "OK";
        } finally {
          locks.get(1).unlock(); locks.get(0).unlock();
        }
      }

      // CP: CP <src> <dst>
      case "CP": {
        if (parts.length < 3) return "ERROR: usage CP <src> <dst>";
        String src = parts[1], dst = parts[2];

        ReentrantLock A = lockFor(keyFor(src));
        ReentrantLock B = lockFor(keyFor(dst));
        List<ReentrantLock> locks = Arrays.asList(A, B);
        locks.sort(Comparator.comparingInt(System::identityHashCode));
        locks.get(0).lock(); locks.get(1).lock();
        try {
          copyChunked(src, dst);
          return "OK";
        } finally {
          locks.get(1).unlock(); locks.get(0).unlock();
        }
      }

      default:
        return "ERROR: UNKNOWN COMMAND";
    }
  }

  // ---------- Chunking helpers ----------
  private void writeChunked(String name, byte[] data) throws IOException {
    Path base = resolvePath(name);
    Path meta = metaPath(base);

    deleteChunked(name);

    int chunks = (data.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
    for (int i = 0; i < chunks; i++) {
      int from = i * CHUNK_SIZE;
      int to = Math.min(data.length, from + CHUNK_SIZE);
      byte[] part = Arrays.copyOfRange(data, from, to);
      Files.write(partPath(base, i), part, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    String metaTxt = "chunks=" + chunks + "\nsize=" + data.length + "\n";
    Files.write(meta, metaTxt.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
  }

  private byte[] readChunked(String name) throws IOException {
    Path base = resolvePath(name);
    Path meta = metaPath(base);
    if (!Files.exists(meta)) throw new FileNotFoundException("missing meta for " + name);

    int chunks = parseMetaChunks(meta);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    for (int i = 0; i < chunks; i++) {
      Path p = partPath(base, i);
      if (!Files.exists(p)) throw new FileNotFoundException("missing chunk " + i);
      baos.write(Files.readAllBytes(p));
    }
    return baos.toByteArray();
  }

  private void deleteChunked(String name) throws IOException {
    Path base = resolvePath(name);
    Path meta = metaPath(base);
    if (Files.exists(meta)) {
      int chunks = parseMetaChunks(meta);
      for (int i = 0; i < chunks; i++) Files.deleteIfExists(partPath(base, i));
      Files.deleteIfExists(meta);
    } else {
      // best-effort: delete common chunk names anyway
      for (int i = 0; i < 10000; i++) {
        Path p = partPath(base, i);
        if (!Files.exists(p)) break;
        Files.deleteIfExists(p);
      }
      Files.deleteIfExists(meta);
    }
  }

  private void moveChunked(String src, String dst) throws IOException {
    Path s = resolvePath(src);
    Path d = resolvePath(dst);

    Path sm = metaPath(s);
    if (!Files.exists(sm)) throw new FileNotFoundException("missing meta for " + src);
    int chunks = parseMetaChunks(sm);

    Files.createDirectories(d.getParent() == null ? cwd : d.getParent());

    Files.move(sm, metaPath(d), StandardCopyOption.REPLACE_EXISTING);
    for (int i = 0; i < chunks; i++) {
      Files.move(partPath(s, i), partPath(d, i), StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private void copyChunked(String src, String dst) throws IOException {
    Path s = resolvePath(src);
    Path d = resolvePath(dst);

    Path sm = metaPath(s);
    if (!Files.exists(sm)) throw new FileNotFoundException("missing meta for " + src);
    int chunks = parseMetaChunks(sm);

    Files.createDirectories(d.getParent() == null ? cwd : d.getParent());

    Files.copy(sm, metaPath(d), StandardCopyOption.REPLACE_EXISTING);
    for (int i = 0; i < chunks; i++) {
      Files.copy(partPath(s, i), partPath(d, i), StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static Path metaPath(Path base) { return base.resolveSibling(base.getFileName().toString() + ".meta"); }
  private static Path partPath(Path base, int idx) {
    return base.resolveSibling(String.format("%s.part%05d", base.getFileName().toString(), idx));
  }

  private static int parseMetaChunks(Path meta) throws IOException {
    List<String> lines = Files.readAllLines(meta, StandardCharsets.UTF_8);
    for (String l : lines) {
      l = l.trim();
      if (l.startsWith("chunks=")) return Integer.parseInt(l.substring("chunks=".length()).trim());
    }
    return 0;
  }

  // ---------- AES-GCM ----------
  private static byte[] aesGcmEncrypt(byte[] plain) throws Exception {
    byte[] iv = new byte[12];
    RNG.nextBytes(iv);
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.ENCRYPT_MODE, AES_KEY, new GCMParameterSpec(128, iv));
    byte[] ct = c.doFinal(plain);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(iv);
    out.write(ct);
    return out.toByteArray();
  }

  private static byte[] aesGcmDecrypt(byte[] enc) throws Exception {
    if (enc.length < 12 + 16) throw new IllegalArgumentException("ciphertext too short");
    byte[] iv = Arrays.copyOfRange(enc, 0, 12);
    byte[] ct = Arrays.copyOfRange(enc, 12, enc.length);

    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.DECRYPT_MODE, AES_KEY, new GCMParameterSpec(128, iv));
    return c.doFinal(ct);
  }

  // ---------- Path + parsing ----------
  private Path resolvePath(String raw) {
    Path p = Paths.get(raw);
    if (!p.isAbsolute()) p = cwd.resolve(p);
    p = p.normalize();
    if (!p.startsWith(ROOT)) throw new SecurityException("path escape blocked");
    return p;
  }

  private static String[] splitCmd(String line) {
    // allow base64 payload containing =+/ chars; split into max 3 parts for PUT
    String[] a = line.split("\\s+", 3);
    return a;
  }

  private static String keyFor(String name) { return name.toLowerCase(Locale.ROOT); }

  private static String safeMsg(String s) { return (s == null) ? "" : s.replace("\n"," ").replace("\r"," "); }

  private static byte[] hexToBytes(String hex) {
    hex = hex.trim();
    if (hex.length() % 2 != 0) throw new IllegalArgumentException("AES_KEY_HEX must have even length");
    byte[] out = new byte[hex.length()/2];
    for (int i=0;i<out.length;i++){
      int hi = Character.digit(hex.charAt(i*2),16);
      int lo = Character.digit(hex.charAt(i*2+1),16);
      if (hi<0||lo<0) throw new IllegalArgumentException("invalid hex");
      out[i]=(byte)((hi<<4)|lo);
    }
    return out;
  }

  private static String tree(Path root) throws IOException {
    StringBuilder sb = new StringBuilder();
    Files.walk(root).forEach(p -> {
      Path rel = ROOT.relativize(p);
      String s = rel.toString();
      if (s.isEmpty()) s = ".";
      if (Files.isDirectory(p)) s += "/";
      sb.append(s).append(" ");
    });
    return sb.toString().trim();
  }
}
