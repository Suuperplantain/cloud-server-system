import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class LoadBalancer {

    private static final boolean VM_MODE = "sqlite".equalsIgnoreCase(System.getenv().getOrDefault("AUTH_DB", "sqlite"));
    private static final List<Node> NODES = List.of(
            new Node(VM_MODE ? "localhost" : "storage1", 9101),
            new Node(VM_MODE ? "localhost" : "storage2", 9102)
    );

    private static final int MIN_DELAY_SEC = Integer.parseInt(
            Optional.ofNullable(System.getenv("MIN_DELAY_SEC")).orElse("30")
    );
    private static final int MAX_DELAY_SEC = Integer.parseInt(
            Optional.ofNullable(System.getenv("MAX_DELAY_SEC")).orElse("90")
    );

    private static volatile Algo algo = Algo.RR;
    private static int rrIndex = 0;

    // Metrics
    private static final AtomicLong totalReq = new AtomicLong(0);
    private static final AtomicLong routedReq = new AtomicLong(0);
    private static final AtomicLong rejectedReq = new AtomicLong(0);
    private static final AtomicLong healthSkips = new AtomicLong(0);

    // Scheduler queue
    private static final PriorityBlockingQueue<Request> queue =
            new PriorityBlockingQueue<>(200, Comparator.comparingLong(Request::priorityKey));

    public static void main(String[] args) throws IOException {
        int port = 9000;
        ServerSocket serverSocket = new ServerSocket(port);
        System.out.println("Load Balancer running on port " + port);

        new Thread(LoadBalancer::dispatchLoop, "dispatcher").start();

        while (true) {
            Socket client = serverSocket.accept();
            new Thread(() -> handleClient(client), "client-" + client.getPort()).start();
        }
    }

    private static void handleClient(Socket client) {
        // Will start once we have a non-empty request line
        long serverStartNs = 0L;
        
        try (Socket c = client;
             BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(c.getOutputStream(), StandardCharsets.UTF_8), true)) {

            String line = in.readLine();
            if (line == null) return;
            
            line = line.trim();
            if (line.isEmpty()) return;

            // Start server latency timing (request received -> response sent).
            // This deliberately excludes any time spent blocked on readLine().
            serverStartNs = System.nanoTime();

            totalReq.incrementAndGet();

            // ---- AUTH wrapper (optional prefix) ----
            String authUser = null;
            String authRole = null;

            if (line.toUpperCase().startsWith("AUTH ")) {
                String[] parts = line.split(" ", 3);
                if (parts.length < 3) {
                    out.println("ERR BAD_AUTH_FORMAT");
                    return;
                }
                String token = parts[1];
                AuthAclService.Session s = AuthAclService.validateToken(token);
                if (s == null) {
                    out.println("ERR UNAUTHENTICATED");
                    return;
                }
                authUser = s.username;
                authRole = s.role;
                line = parts[2].trim();
                if (line.isEmpty()) {
                    out.println("ERR BAD_AUTH_FORMAT");
                    return;
                }
            }

            // Parse command token
            String cmd = "";
            if (line.length() > 0) {
                int spaceIdx = line.indexOf(' ');
                if (spaceIdx > 0) {
                    cmd = line.substring(0, spaceIdx).toUpperCase();
                } else {
                    cmd = line.toUpperCase();
                }
            }
            
            // Record request metric
            Metrics.recordRequest(cmd);

            // LB control commands handled immediately.
            // NOTE: STATS requests are treated as real LB requests for SERVER_MS:
            // they contribute a very small latency sample (clamped to ≥1ms, no
            // queue/delay/forward). Frequent STATS can reduce AVG_MS when mixed
            // with slower operations; the effect depends on the traffic mix.
            if (line.equalsIgnoreCase("STATS")) {
                out.write(Metrics.snapshot());
                out.write("\n");
                out.flush();
                return;
            }
            if (line.toUpperCase().startsWith("ALGO ")) {
                String a = line.substring(5).trim().toUpperCase();
                try {
                    algo = Algo.valueOf(a);
                    out.println("OK: ALGO SET TO " + algo);
                } catch (Exception e) {
                    out.println("ERROR: Unknown algo. Use RR, FCFS, SJN");
                }
                return;
            }

            // ---- AUTH/DB commands handled immediately (not scheduled) ----
            if (line.toUpperCase().startsWith("REGISTER ")) {
                String[] p = line.split(" ", 3);
                if (p.length < 3) { out.println("ERR REGISTER_FORMAT"); return; }
                out.println(AuthAclService.register(p[1], p[2]));
                return;
            }

            if (line.toUpperCase().startsWith("LOGIN ")) {
                String[] p = line.split(" ", 3);
                if (p.length < 3) { out.println("ERR LOGIN_FORMAT"); return; }
                out.println(AuthAclService.login(p[1], p[2]));
                return;
            }

            if (line.toUpperCase().startsWith("SHARE ")) {
                if (authUser == null) { out.println("ERR UNAUTHENTICATED"); return; }

                String[] p = line.split(" ", 4);
                if (p.length < 4) { out.println("ERR SHARE_FORMAT"); return; }

                String filename = p[1].trim();
                String target = p[2].trim();
                String perm = p[3].trim().toUpperCase();

                try {
                    String owner = AuthAclService.getOwner(filename);
                    if (owner == null) { out.println("ERR NO_SUCH_FILE"); return; }

                    if (!"ADMIN".equals(authRole) && !owner.equals(authUser)) {
                        out.println("ERR FORBIDDEN");
                        return;
                    }

                    boolean r = perm.equals("R") || perm.equals("RW");
                    boolean w = perm.equals("W") || perm.equals("RW");

                    AuthAclService.shareFile(owner, filename, target, r, w);
                    AuthAclService.audit(authUser, "SHARE",
                            "file=" + filename + " to=" + target + " perm=" + perm,
                            "LOAD-BALANCER");

                    out.println("OK");
                    return;

                } catch (Exception ex) {
                    out.println("ERR DB");
                    return;
                }
            }

            boolean fileOp = isFileOp(line);

            // ---- Require auth for file ops + enforce ACL ----
            if (fileOp) {
                if (authUser == null) {
                    out.println("ERR UNAUTHENTICATED");
                    return;
                }

                String upper = line.toUpperCase();

                // Extract filename for LOAD/DELETE/STORE
                String filename = null;
                if (upper.startsWith("LOAD ")) filename = line.substring(5).trim();
                else if (upper.startsWith("DELETE ")) filename = line.substring(7).trim();
                else if (upper.startsWith("STORE ")) {
                    String[] p = line.split(" ", 3);
                    if (p.length >= 2) filename = p[1].trim();
                }

                try {
                    if (filename != null && !filename.isEmpty()) {
                        if (upper.startsWith("LOAD ")) {
                            String owner = AuthAclService.getOwner(filename);
                            if (owner == null) {
                                out.println("ERR NOT_FOUND");
                                return;
                            }

                            if (!owner.equals(authUser)) {
                                if (!"ADMIN".equals(authRole) && !AuthAclService.canRead(owner, authUser, filename)) {
                                    out.println("ERR FORBIDDEN");
                                    return;
                                }
                            }
                        }
                        if (upper.startsWith("DELETE ") || upper.startsWith("STORE ")) {
                            String owner = AuthAclService.getOwner(filename);
                            if (owner != null) {
                                if (!"ADMIN".equals(authRole) && !owner.equals(authUser)) {
                                    if (!AuthAclService.canWrite(owner, authUser, filename)) {
                                        out.println("ERR FORBIDDEN");
                                        return;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception ex) {
                    out.println("ERR DB");
                    return;
                }
            }

            // Create a future so the dispatcher can respond
            CompletableFuture<String> future = new CompletableFuture<>();
            queue.put(new Request(line, fileOp, future));

            // Wait for dispatcher response
            String response;
            try {
                response = future.get(200, TimeUnit.SECONDS);
            } catch (Exception e) {
                response = "ERROR: Timeout waiting for scheduler/forwarding";
            }

            // ---- After successful STORE, ensure owner has RW ACL ----
            try {
                if (fileOp && authUser != null && line.toUpperCase().startsWith("STORE ") && response != null) {
                    if (!response.toUpperCase().startsWith("ERROR") && !response.toUpperCase().startsWith("ERR")) {
                        String[] p = line.split(" ", 3);
                        if (p.length >= 2) {
                            String filename = p[1].trim();
                            AuthAclService.ensureOwnerRW(authUser, filename);
                            AuthAclService.audit(authUser, "STORE", "file=" + filename, "LOAD-BALANCER");
                        }
                    }
                }
            } catch (Exception ignored) {}

            // Add audit for LOAD/DELETE
            try {
                if (fileOp && authUser != null && response != null && !response.toUpperCase().startsWith("ERROR") && !response.toUpperCase().startsWith("ERR")) {
                    String u = line.toUpperCase();
                    if (u.startsWith("LOAD ")) AuthAclService.audit(authUser, "LOAD", "file=" + line.substring(5).trim(), "LOAD-BALANCER");
                    if (u.startsWith("DELETE ")) AuthAclService.audit(authUser, "DELETE", "file=" + line.substring(7).trim(), "LOAD-BALANCER");
                }
            } catch (Exception ignored) {}

            out.println(response);

        } catch (Exception e) {
            System.err.println("[ERROR] handleClient: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Record server-side latency only after we have a proper request line.
            if (serverStartNs != 0L) {
                long serverMs = (System.nanoTime() - serverStartNs) / 1_000_000L;
                Metrics.recordServerMs(serverMs);
            }
        }
    }

    // Timing: queue wait immediately after take(); delay wraps only maybeDelay(); forward wraps only forward().
    private static volatile boolean timingDebugLogged = false;

    private static void dispatchLoop() {
        while (true) {
            try {
                Request req = queue.take();

                // Queue wait: enqueue -> dequeue
                long dequeueNs = System.nanoTime();
                long queueMs = 0L;
                if (req.enqueuedAtNs > 0L) {
                    queueMs = (dequeueNs - req.enqueuedAtNs) / 1_000_000L;
                    Metrics.recordQueueWaitMs(queueMs);
                }

                // Choose node(s) for this request.
                Node primaryNode;
                Node fallbackNode = null;

                String trimmed = req.payload.trim();
                String upper = trimmed.toUpperCase(Locale.ROOT);
                boolean isLoad = upper.startsWith("LOAD ");
                boolean isDelete = upper.startsWith("DELETE ");

                if (req.fileOp && (isLoad || isDelete)) {
                    // For LOAD/DELETE, use deterministic filename-based routing,
                    // with a single fallback to the other node if NOT_FOUND.
                    int primaryIdx = stableIndex(trimmed);
                    primaryNode = NODES.get(primaryIdx);
                    if (NODES.size() > 1) {
                        fallbackNode = NODES.get((primaryIdx + 1) % NODES.size());
                    }
                } else {
                    // Existing health-based routing for other ops.
                    primaryNode = pickHealthyNode(req.payload, req.fileOp);
                    if (primaryNode == null) {
                        rejectedReq.incrementAndGet();
                        req.future.complete("ERROR: No storage nodes available");
                        continue;
                    }
                }

                Metrics.recordNodeHit(getNodeStorageName(primaryNode));

                // Delay: only maybeDelay() (storage must not sleep or FORWARD_MS would include it)
                long delayMs = 0L;
                if (req.fileOp) {
                    long delayStart = System.nanoTime();
                    maybeDelay();
                    delayMs = (System.nanoTime() - delayStart) / 1_000_000L;
                    Metrics.recordDelayMs(delayMs);
                }

                // Forward: network + storage I/O, no LB delay.
                // For LOAD/DELETE we may try primary + one fallback on NOT_FOUND.
                long forwardMsTotal = 0L;
                String resp;

                boolean usedFallback = false;

                if (req.fileOp && (isLoad || isDelete) && fallbackNode != null) {
                    // First attempt: primary node
                    long fwdStart = System.nanoTime();
                    resp = forward(primaryNode, req.payload);
                    forwardMsTotal += (System.nanoTime() - fwdStart) / 1_000_000L;

                    boolean notFound = resp != null && resp.toUpperCase(Locale.ROOT).startsWith("ERR NOT_FOUND");

                    if (notFound) {
                        usedFallback = true;
                        // Second attempt: fallback node
                        long fwdStart2 = System.nanoTime();
                        String resp2 = forward(fallbackNode, req.payload);
                        forwardMsTotal += (System.nanoTime() - fwdStart2) / 1_000_000L;

                        // If fallback succeeds (anything other than ERR NOT_FOUND), use that response.
                        if (resp2 != null && !resp2.toUpperCase(Locale.ROOT).startsWith("ERR NOT_FOUND")) {
                            resp = resp2;
                        }

                        // Minimal debug logging for demo: file, primary, fallback used.
                        String fileKey = extractFileKeyForDebug(trimmed);
                        String primaryName = getNodeStorageName(primaryNode);
                        String fallbackName = getNodeStorageName(fallbackNode);
                        System.out.println("[LB debug] op=" + (isLoad ? "LOAD" : "DELETE") +
                                " file=" + fileKey +
                                " primary=" + primaryName +
                                " fallbackUsed=" + usedFallback +
                                " fallbackNode=" + fallbackName);
                    }
                } else {
                    long fwdStart = System.nanoTime();
                    resp = forward(primaryNode, req.payload);
                    forwardMsTotal += (System.nanoTime() - fwdStart) / 1_000_000L;
                }

                Metrics.recordForwardMs(forwardMsTotal);
                routedReq.incrementAndGet();
                req.future.complete(resp);

                if (req.fileOp && !timingDebugLogged) {
                    timingDebugLogged = true;
                    System.out.println("[LB timing] queueMs=" + queueMs + " delayMs=" + delayMs + " forwardMs=" + forwardMs);
                }

            } catch (Exception e) {
                System.err.println("[ERROR] dispatchLoop: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private static synchronized int nextRrIndex() {
        int i = rrIndex;
        rrIndex = (rrIndex + 1) % NODES.size();
        return i;
    }

    private static Node pickHealthyNode(String payload, boolean fileOp) {
        int n = NODES.size();

        // Deterministic placement for file ops (hash-based)
        if (fileOp) {
            int start = stableIndex(payload);
            for (int off = 0; off < n; off++) {
                Node cand = NODES.get((start + off) % n);
                if (isHealthy(cand)) {
                    return cand;
                }
                healthSkips.incrementAndGet();
            }
            return null;
        }

        // RR for non-file ops
        int tries = n;
        while (tries-- > 0) {
            Node cand = NODES.get(nextRrIndex());
            if (isHealthy(cand)) {
                return cand;
            }
            healthSkips.incrementAndGet();
        }
        return null;
    }

    private static String getNodeStorageName(Node node) {
        if (node.port == 9101) return "STORAGE-1";
        if (node.port == 9102) return "STORAGE-2";
        return node.host + ":" + node.port;
    }

    private static boolean isHealthy(Node node) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(node.host, node.port), 800);
            s.setSoTimeout(800);
            PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out.println("PING");
            String resp = in.readLine();
            return resp != null && resp.toUpperCase().startsWith("PONG");
        } catch (Exception e) {
            return false;
        }
    }

    private static String forward(Node node, String payload) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(node.host, node.port), 2000);
            s.setSoTimeout(240000);
            PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out.println(payload);
            String resp = in.readLine();
            return (resp == null) ? "ERROR: Empty response" : resp;
        } catch (Exception e) {
            return "ERROR: Forward failed: " + e.getMessage();
        }
    }

    private static boolean isFileOp(String line) {
        String p = line.trim().toUpperCase();
        return p.startsWith("STORE ")
                || p.startsWith("LOAD ")
                || p.startsWith("DELETE ")
                || p.equals("LIST")
                || p.startsWith("MKDIR ")
                || p.startsWith("LS")
                || p.startsWith("TREE")
                || p.startsWith("CP ")
                || p.startsWith("MV ")
                || p.startsWith("NANO ");
    }

    private static int stableIndex(String payload) {
        // Deterministic routing based on the *file key* so that the same
        // filename always maps to the same storage node, regardless of the
        // specific operation (STORE/LOAD/DELETE) or any extra arguments.
        String line = payload.trim();
        String upper = line.toUpperCase(Locale.ROOT);

        String key = line; // fallback

        if (upper.startsWith("STORE ")) {
            // STORE <filename> <base64>
            String[] parts = line.split("\\s+", 3);
            if (parts.length >= 2) key = parts[1];
        } else if (upper.startsWith("LOAD ")) {
            // LOAD <filename>
            String[] parts = line.split("\\s+", 2);
            if (parts.length >= 2) key = parts[1];
        } else if (upper.startsWith("DELETE ")) {
            // DELETE <filename>
            String[] parts = line.split("\\s+", 2);
            if (parts.length >= 2) key = parts[1];
        } else {
            // Other file-ish commands (LIST, MKDIR, etc.) just hash on payload.
            key = line;
        }

        return Math.floorMod(key.hashCode(), NODES.size());
    }

    // Helper used only for debug logging.
    private static String extractFileKeyForDebug(String payload) {
        String line = payload.trim();
        String upper = line.toUpperCase(Locale.ROOT);

        if (upper.startsWith("STORE ")) {
            String[] parts = line.split("\\s+", 3);
            if (parts.length >= 2) return parts[1];
        } else if (upper.startsWith("LOAD ")) {
            String[] parts = line.split("\\s+", 2);
            if (parts.length >= 2) return parts[1];
        } else if (upper.startsWith("DELETE ")) {
            String[] parts = line.split("\\s+", 2);
            if (parts.length >= 2) return parts[1];
        }
        return line;
    }

    private static void maybeDelay() {
        int min = Math.min(MIN_DELAY_SEC, MAX_DELAY_SEC);
        int max = Math.max(MIN_DELAY_SEC, MAX_DELAY_SEC);
        int delay = (min == max) ? min : (min + new Random().nextInt(max - min + 1));
        try { Thread.sleep(delay * 1000L); } catch (InterruptedException ignored) {}
    }

    private static String stats() {
        return "STATS LB algo=" + algo +
                " total=" + totalReq.get() +
                " routed=" + routedReq.get() +
                " rejected=" + rejectedReq.get() +
                " healthSkips=" + healthSkips.get() +
                " time=" + LocalDateTime.now();
    }

    enum Algo { RR, FCFS, SJN }

    static class Node {
        final String host;
        final int port;
        Node(String host, int port) { this.host = host; this.port = port; }
    }

    static class Request {
        final String payload;
        final boolean fileOp;
        final CompletableFuture<String> future;
        // Enqueue timestamp: when the request is placed in the scheduler queue.
        // Used both for FCFS scheduling and for QUEUE_WAIT_MS metrics.
        final long enqueuedAtNs = System.nanoTime();
        final int length;

        Request(String payload, boolean fileOp, CompletableFuture<String> future) {
            this.payload = payload;
            this.fileOp = fileOp;
            this.future = future;
            this.length = payload.length();
        }

        long priorityKey() {
            if (algo == Algo.SJN) return length;
            return enqueuedAtNs;
        }
    }
}