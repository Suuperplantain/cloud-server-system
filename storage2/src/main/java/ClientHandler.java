import java.io.*;
import java.sql.SQLException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public class ClientHandler implements Runnable {

    private static final String STORAGE_NAME = "STORAGE-2";
    private static final String STORE_DIR = System.getenv().getOrDefault("STORE_DIR", "./data");
    private static final Path ROOT;

    static {
        ROOT = Paths.get(STORE_DIR).toAbsolutePath().normalize();
        try {
            Files.createDirectories(ROOT);
        } catch (IOException e) {
            throw new RuntimeException("Could not create STORE_DIR: " + ROOT, e);
        }
    }

    private final Socket client;

    // file locks
    private static final Map<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    // metrics
    private static final AtomicLong reqCount = new AtomicLong(0);
    private static final AtomicLong pingCount = new AtomicLong(0);
    private static final AtomicLong storeCount = new AtomicLong(0);
    private static final AtomicLong loadCount = new AtomicLong(0);
    private static final AtomicLong deleteCount = new AtomicLong(0);
    private static final AtomicLong errCount = new AtomicLong(0);

    public ClientHandler(Socket client) {
        this.client = client;
    }

    @Override
    public void run() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8), true)) {

            Files.createDirectories(ROOT);

            String line;
            while ((line = in.readLine()) != null) {
                reqCount.incrementAndGet();
                line = line.trim();
                if (line.isEmpty()) continue;

                String resp = handle(line);
                out.println(resp);
            }

        } catch (Exception e) {
            errCount.incrementAndGet();
            System.out.println(ts() + " [" + STORAGE_NAME + "] ERROR: " + e.getMessage());
        } finally {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private String handle(String raw) {
        try {
            // existing commands
            if (raw.equalsIgnoreCase("PING")) {
                pingCount.incrementAndGet();
                return "PONG FROM " + STORAGE_NAME;
            }
            if (raw.equalsIgnoreCase("TIME")) return LocalDateTime.now().toString();
            if (raw.equalsIgnoreCase("HELLO")) return "HELLO FROM " + STORAGE_NAME;

            // metrics
            if (raw.equalsIgnoreCase("STATS")) {
                return "STATS " + STORAGE_NAME +
                        " req=" + reqCount.get() +
                        " ping=" + pingCount.get() +
                        " store=" + storeCount.get() +
                        " load=" + loadCount.get() +
                        " del=" + deleteCount.get() +
                        " err=" + errCount.get();
            }

            // file ops:
            // STORE <filename> <base64>
            // LOAD <filename>
            // DELETE <filename>
            // LIST
            String[] parts = raw.split(" ", 3);
            String cmd = parts[0].toUpperCase();

            switch (cmd) {
                case "STORE":
                    return store(parts);
                case "LOAD":
                    return load(parts);
                case "DELETE":
                    return delete(parts);
                case "LIST":
                    return listFiles();

                // terminal-ish commands
                case "WHOAMI":
                    return "user@" + STORAGE_NAME.toLowerCase();
                case "PS":
                    return "PID TTY TIME CMD\n1 pts/0 00:00:00 " + STORAGE_NAME.toLowerCase();
                case "MKDIR":
                    return mkdir(raw);
                case "LS":
                    return ls(raw);
                case "TREE":
                    return tree(raw);
                case "CP":
                    return cp(raw);
                case "MV":
                    return mv(raw);
                case "NANO":
                    return nano(parts); // NANO <filename> <base64>
                default:
                    return "ERROR: Unknown command";
            }
        } catch (Exception e) {
            errCount.incrementAndGet();
            return "ERROR: " + e.getMessage();
        }
    }

    // No artificial delay on storage: LB applies maybeDelay(); storage responds promptly so FORWARD_MS is accurate.

    private String store(String[] parts) throws Exception {
        if (parts.length < 3) return "ERROR: STORE requires filename and base64";
        String filename = safeName(parts[1]);
        String b64 = parts[2];

        ReentrantLock lock = LOCKS.computeIfAbsent(filename, k -> new ReentrantLock());
        lock.lock();
        try {
            byte[] data = Base64.getDecoder().decode(b64);
            Path p = ROOT.resolve(filename).normalize();
            ensureInsideRoot(p);
            Files.write(p, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            String owner = "demo";
            MySqlStore.ensureUser(owner);
            try { MySqlStore.upsertFileMeta(owner, filename, STORAGE_NAME, data.length); } catch (SQLException ignored) {}
            MySqlStore.log(owner, "STORE", filename + " bytes=" + data.length, STORAGE_NAME);
            storeCount.incrementAndGet();
            System.out.println(ts() + " [" + STORAGE_NAME + "] STORED " + filename + " (" + data.length + " bytes)");
            return "OK: STORED " + filename + " ON " + STORAGE_NAME;
        } finally {
            lock.unlock();
        }
    }

    private String load(String[] parts) throws Exception {
        if (parts.length < 2) return "ERROR: LOAD requires filename";
        String filename = safeName(parts[1]);

        ReentrantLock lock = LOCKS.computeIfAbsent(filename, k -> new ReentrantLock());
        lock.lock();
        try {
            Path p = ROOT.resolve(filename).normalize();
            ensureInsideRoot(p);
            if (!Files.exists(p)) return "ERROR: Not found";
            byte[] data = Files.readAllBytes(p);
            loadCount.incrementAndGet();
            String b64 = Base64.getEncoder().encodeToString(data);
            System.out.println(ts() + " [" + STORAGE_NAME + "] LOADED " + filename + " (" + data.length + " bytes)");
            return "OK: " + filename + " " + b64;
        } finally {
            lock.unlock();
        }
    }

    private String delete(String[] parts) throws Exception {
        if (parts.length < 2) return "ERROR: DELETE requires filename";
        String filename = safeName(parts[1]);

        ReentrantLock lock = LOCKS.computeIfAbsent(filename, k -> new ReentrantLock());
        lock.lock();
        try {
            Path p = ROOT.resolve(filename).normalize();
            ensureInsideRoot(p);
            if (!Files.exists(p)) return "ERROR: Not found";
            Files.delete(p);
            String owner = "demo";
            try { MySqlStore.deleteFileMeta(owner, filename); } catch (SQLException ignored) {}
            MySqlStore.log(owner, "DELETE", filename, STORAGE_NAME);
            deleteCount.incrementAndGet();
            System.out.println(ts() + " [" + STORAGE_NAME + "] DELETED " + filename);
            return "OK: DELETED " + filename + " ON " + STORAGE_NAME;
        } finally {
            lock.unlock();
        }
    }

    private String listFiles() throws Exception {
        Files.createDirectories(ROOT);
        StringBuilder sb = new StringBuilder("OK: FILES\n");
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(ROOT)) {
            for (Path p : ds) {
                if (Files.isRegularFile(p)) sb.append(p.getFileName()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    // terminal emulation (minimal)
    private String mkdir(String raw) throws Exception {
        String[] p = raw.split(" ", 2);
        if (p.length < 2) return "ERROR: MKDIR <dir>";
        Path dir = ROOT.resolve(safeRel(p[1])).normalize();
        ensureInsideRoot(dir);
        Files.createDirectories(dir);
        return "OK: MKDIR " + dir.getFileName();
    }

    private String ls(String raw) throws Exception {
        String[] p = raw.split(" ", 2);
        Path dir = (p.length < 2) ? ROOT : ROOT.resolve(safeRel(p[1])).normalize();
        ensureInsideRoot(dir);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) return "ERROR: Not a directory";
        StringBuilder sb = new StringBuilder("OK: LS\n");
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path x : ds) sb.append(x.getFileName()).append("\n");
        }
        return sb.toString().trim();
    }

    private String tree(String raw) throws Exception {
        String[] p = raw.split(" ", 2);
        Path dir = (p.length < 2) ? ROOT : ROOT.resolve(safeRel(p[1])).normalize();
        ensureInsideRoot(dir);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) return "ERROR: Not a directory";
        StringBuilder sb = new StringBuilder("OK: TREE\n");
        Files.walk(dir).forEach(path -> {
            Path rel = ROOT.relativize(path);
            sb.append(rel.toString().isEmpty() ? "." : rel.toString()).append("\n");
        });
        return sb.toString().trim();
    }

    private String cp(String raw) throws Exception {
        String[] p = raw.split(" ", 3);
        if (p.length < 3) return "ERROR: CP <src> <dst>";
        Path src = ROOT.resolve(safeRel(p[1])).normalize();
        Path dst = ROOT.resolve(safeRel(p[2])).normalize();
        ensureInsideRoot(src); ensureInsideRoot(dst);
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        return "OK: CP";
    }

    private String mv(String raw) throws Exception {
        String[] p = raw.split(" ", 3);
        if (p.length < 3) return "ERROR: MV <src> <dst>";
        Path src = ROOT.resolve(safeRel(p[1])).normalize();
        Path dst = ROOT.resolve(safeRel(p[2])).normalize();
        ensureInsideRoot(src); ensureInsideRoot(dst);
        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        return "OK: MV";
    }

    private String nano(String[] parts) throws Exception {
        if (parts.length < 3) return "ERROR: NANO <filename> <base64>";
        String filename = safeName(parts[1]);
        byte[] data = Base64.getDecoder().decode(parts[2]);
        Path p = ROOT.resolve(filename).normalize();
        ensureInsideRoot(p);
        Files.write(p, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return "OK: NANO wrote " + filename;
    }

    private static void ensureInsideRoot(Path p) {
        if (!p.startsWith(ROOT)) throw new IllegalArgumentException("Path escapes root");
    }

    private static String safeName(String s) {
        if (s.contains("/") || s.contains("\\") || s.contains(".."))
            throw new IllegalArgumentException("Bad filename");
        return s;
    }

    private static String safeRel(String s) {
        if (s.contains("..")) throw new IllegalArgumentException("Bad path");
        return s.replace("\\", "/");
    }

    private static String ts() {
        return LocalDateTime.now().toString();
    }
}