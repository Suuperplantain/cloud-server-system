import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class Metrics {

    private Metrics() {}

    // Totals
    private static final LongAdder totalRequests = new LongAdder();
    private static final LongAdder totalErrors = new LongAdder();

    // Per-command counts, e.g. LOGIN/STORE/LOAD/SHARE
    private static final ConcurrentHashMap<String, LongAdder> perCommand = new ConcurrentHashMap<>();

    // Per-storage node counts, e.g. STORAGE-1 / STORAGE-2
    private static final ConcurrentHashMap<String, LongAdder> perNode = new ConcurrentHashMap<>();

    // Server Latencies (ms): request read -> response write.
    // This is the canonical SERVER_MS metric for the load balancer and is
    // reported both as AVG_MS/P95_MS (legacy names) and SERVER_AVG_MS/SERVER_P95_MS.
    private static final int LAT_N = 400;
    private static final long[] latMsRing = new long[LAT_N];
    private static final AtomicLong latIdx = new AtomicLong(0);
    private static final LongAdder latSumMs = new LongAdder();
    private static final LongAdder latCount = new LongAdder();

    // Queue wait latencies (enqueue -> dispatcher dequeue)
    private static final long[] queueMsRing = new long[LAT_N];
    private static final AtomicLong queueIdx = new AtomicLong(0);
    private static final LongAdder queueSumMs = new LongAdder();
    private static final LongAdder queueCount = new LongAdder();

    // Artificial delay latencies (time spent inside maybeDelay)
    private static final long[] delayMsRing = new long[LAT_N];
    private static final AtomicLong delayIdx = new AtomicLong(0);
    private static final LongAdder delaySumMs = new LongAdder();
    private static final LongAdder delayCount = new LongAdder();

    // Forwarding latencies (time spent forwarding to storage node)
    private static final long[] forwardMsRing = new long[LAT_N];
    private static final AtomicLong forwardIdx = new AtomicLong(0);
    private static final LongAdder forwardSumMs = new LongAdder();
    private static final LongAdder forwardCount = new LongAdder();

    public static void recordRequest(String command) {
        totalRequests.increment();
        perCommand.computeIfAbsent(safe(command), k -> new LongAdder()).increment();
    }

    public static void recordError() {
        totalErrors.increment();
    }

    public static void recordNodeHit(String node) {
        perNode.computeIfAbsent(safe(node), k -> new LongAdder()).increment();
    }

    // SERVER_MS
    public static void recordServerMs(long ms) {
        recordLatencyMs(ms);
    }

    // Internal entry point for server latency ring-buffer
    private static void recordLatencyMs(long ms) {
        if (ms <= 0) ms = 1; // 0 is reserved for "empty slot"
        latSumMs.add(ms);
        latCount.increment();

        long i = latIdx.getAndIncrement();
        latMsRing[(int)(i % LAT_N)] = ms;
    }

    // QUEUE_WAIT_MS
    public static void recordQueueWaitMs(long ms) {
        if (ms <= 0) ms = 1; // 0 is reserved for "empty slot"
        queueSumMs.add(ms);
        queueCount.increment();

        long i = queueIdx.getAndIncrement();
        queueMsRing[(int)(i % LAT_N)] = ms;
    }

    // DELAY_MS
    public static void recordDelayMs(long ms) {
        if (ms <= 0) ms = 1; // 0 is reserved for "empty slot"
        delaySumMs.add(ms);
        delayCount.increment();

        long i = delayIdx.getAndIncrement();
        delayMsRing[(int)(i % LAT_N)] = ms;
    }

    // FORWARD_MS
    public static void recordForwardMs(long ms) {
        if (ms <= 0) ms = 1; // 0 is reserved for "empty slot"
        forwardSumMs.add(ms);
        forwardCount.increment();

        long i = forwardIdx.getAndIncrement();
        forwardMsRing[(int)(i % LAT_N)] = ms;
    }

    public static String snapshot() {
        long req = totalRequests.sum();
        long err = totalErrors.sum();

        // Server latency stats (SERVER_MS = overall LB handling time:
        // auth, ACL checks, scheduling, queue wait, artificial delay,
        // forwarding to storage nodes, and response write. It explicitly
        // excludes client think-time before the request line is read.)
        long serverCnt = latCount.sum();
        long serverAvg = (serverCnt == 0) ? 0 : (latSumMs.sum() / serverCnt);
        long serverP95 = percentile95(latMsRing, (int)Math.min(serverCnt, LAT_N));

        // Queue wait stats
        long queueCnt = queueCount.sum();
        long queueAvg = (queueCnt == 0) ? 0 : (queueSumMs.sum() / queueCnt);
        long queueP95 = percentile95(queueMsRing, (int)Math.min(queueCnt, LAT_N));

        // Delay stats
        long delayCnt = delayCount.sum();
        long delayAvg = (delayCnt == 0) ? 0 : (delaySumMs.sum() / delayCnt);
        long delayP95 = percentile95(delayMsRing, (int)Math.min(delayCnt, LAT_N));

        // Forward stats
        long forwardCnt = forwardCount.sum();
        long forwardAvg = (forwardCnt == 0) ? 0 : (forwardSumMs.sum() / forwardCnt);
        long forwardP95 = percentile95(forwardMsRing, (int)Math.min(forwardCnt, LAT_N));

        StringBuilder sb = new StringBuilder();
        sb.append("OK STATS ");
        sb.append("REQ=").append(req).append(' ');
        sb.append("ERR=").append(err).append(' ');
        // AVG_MS/P95_MS are legacy aliases for SERVER_AVG_MS/SERVER_P95_MS.
        sb.append("AVG_MS=").append(serverAvg).append(' ');
        sb.append("P95_MS=").append(serverP95).append(' ');
        sb.append("CMD=").append(mapToCompact(perCommand)).append(' ');
        sb.append("NODE=").append(mapToCompact(perNode)).append(' ');

        // New explicit latency components appended for compatibility
        sb.append("QUEUE_AVG_MS=").append(queueAvg).append(' ');
        sb.append("QUEUE_P95_MS=").append(queueP95).append(' ');
        sb.append("DELAY_AVG_MS=").append(delayAvg).append(' ');
        sb.append("DELAY_P95_MS=").append(delayP95).append(' ');
        sb.append("FORWARD_AVG_MS=").append(forwardAvg).append(' ');
        sb.append("FORWARD_P95_MS=").append(forwardP95).append(' ');
        sb.append("SERVER_AVG_MS=").append(serverAvg).append(' ');
        sb.append("SERVER_P95_MS=").append(serverP95);
        return sb.toString();
    }

    /**
     * Compute an approximate 95th percentile over the samples currently stored
     * in a metric's ring buffer (up to N entries). A value of 0ms is reserved
     * for "empty slot" / uninitialised elements; all recorded samples are
     * clamped to at least 1ms, and we exclude 0ms values here to avoid
     * contamination by uninitialised data.
     */
    private static long percentile95(long[] ring, int filled) {
        if (filled <= 0) return 0;

        long[] copy = new long[filled];
        int actualFilled = 0;
        for (int i = 0; i < ring.length && actualFilled < filled; i++) {
            if (ring[i] > 0) copy[actualFilled++] = ring[i];
        }
        if (actualFilled == 0) return 0;

        long[] trimmed = Arrays.copyOf(copy, actualFilled);
        Arrays.sort(trimmed);

        int idx = (int)Math.ceil(0.95 * actualFilled) - 1;
        if (idx < 0) idx = 0;
        if (idx >= actualFilled) idx = actualFilled - 1;
        return trimmed[idx];
    }

    private static String mapToCompact(ConcurrentHashMap<String, LongAdder> m) {
        if (m.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, LongAdder> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(e.getKey()).append(':').append(e.getValue().sum());
        }
        sb.append('}');
        return sb.toString();
    }

    private static String safe(String s) {
        if (s == null) return "UNKNOWN";
        s = s.trim();
        return s.isEmpty() ? "UNKNOWN" : s.toUpperCase();
    }
}