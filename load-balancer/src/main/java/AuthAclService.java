import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AuthAclService {

    private static final SecureRandom RNG = new SecureRandom();
    private static final ConcurrentHashMap<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final long SESSION_TTL_MS = 6L * 60L * 60L * 1000L; // 6 hours

    public static class Session {
        public final String username;
        public final String role; // "STANDARD" or "ADMIN"
        public final long expiresAt;

        public Session(String u, String r, long e) {
            this.username = u;
            this.role = r;
            this.expiresAt = e;
        }
    }

    // --- DB connection (LB is in Docker => mysql hostname is "mysql") ---
    private static Connection db() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver"); // force-load driver (catches missing driver instantly)
        } catch (ClassNotFoundException e) {
            System.err.println("JDBC DRIVER MISSING: " + e);
            throw new SQLException("MySQL JDBC driver missing", e);
        }

        String host = System.getenv().getOrDefault("DB_HOST", "mysql");
        String port = System.getenv().getOrDefault("DB_PORT", "3306");
        String name = System.getenv().getOrDefault("DB_NAME", "cloud");
        String user = System.getenv().getOrDefault("DB_USER", "root");
        String pass = System.getenv().getOrDefault("DB_PASS", "root");
        String url = "jdbc:mysql://" + host + ":" + port + "/" + name + "?useSSL=false&allowPublicKeyRetrieval=true";

        try {
            return DriverManager.getConnection(url, user, pass);
        } catch (SQLException e) {
            System.err.println("DB CONNECT FAIL url=" + url + " user=" + user + " err=" + e);
            throw e;
        }
    }

    // --- Audit log helper ---
    public static void audit(String username, String action, String details, String storageNode) {
        try (Connection c = db();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log(username, action, details, storage_node) VALUES(?,?,?,?)")) {
            ps.setString(1, username);
            ps.setString(2, action);
            ps.setString(3, details);
            ps.setString(4, storageNode);
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }

    // --- Password hashing (salted SHA-256, stored in pass_hash) ---
    private static String hashPassword(String password) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        byte[] digest = sha256(concat(salt, password.getBytes(StandardCharsets.UTF_8)));
        return "sha256$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(digest);
    }

    private static boolean verifyPassword(String password, String stored) {
        try {
            String[] parts = stored.split("\\$");
            if (parts.length != 3 || !parts[0].equals("sha256")) return false;
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expected = Base64.getDecoder().decode(parts[2]);
            byte[] actual = sha256(concat(salt, password.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // --- Public API used by LoadBalancer ---

    // REGISTER <username> <password>
    public static String register(String username, String password) {
        String passHash = hashPassword(password);
        try (Connection c = db();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO users(username, pass_hash, role) VALUES(?,?, 'STANDARD')")) {
            ps.setString(1, username);
            ps.setString(2, passHash);
            ps.executeUpdate();
            audit(username, "REGISTER", "created STANDARD user", "LOAD-BALANCER");
            return "OK";
        } catch (SQLIntegrityConstraintViolationException dup) {
            return "ERR USER_EXISTS";
        } catch (Exception e) {
            return "ERR DB";
        }
    }

    // LOGIN <username> <password>  => OK TOKEN <uuid> ROLE <STANDARD|ADMIN>
    public static String login(String username, String password) {
        try (Connection c = db();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT pass_hash, role FROM users WHERE username=? LIMIT 1")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "ERR BAD_CREDENTIALS";
                String stored = rs.getString(1);
                String role = rs.getString(2);

                if (!verifyPassword(password, stored)) return "ERR BAD_CREDENTIALS";

                String token = UUID.randomUUID().toString();
                long exp = System.currentTimeMillis() + SESSION_TTL_MS;
                SESSIONS.put(token, new Session(username, role, exp));

                audit(username, "LOGIN", "issued token exp=" + exp, "LOAD-BALANCER");
                return "OK TOKEN " + token + " ROLE " + role;
            }
        } catch (Exception e) {
            return "ERR DB";
        }
    }

    // Validate token => Session or null
    public static Session validateToken(String token) {
        Session s = SESSIONS.get(token);
        if (s == null) return null;
        if (System.currentTimeMillis() > s.expiresAt) {
            SESSIONS.remove(token);
            return null;
        }
        return s;
    }

    // --- ACL ---
    public static void ensureOwnerRW(String owner, String filename) throws SQLException {
        try (Connection c = db()) {
            try (PreparedStatement upd = c.prepareStatement(
                    "UPDATE acl SET can_read=1, can_write=1 WHERE owner=? AND filename=? AND grantee=?")) {
                upd.setString(1, owner);
                upd.setString(2, filename);
                upd.setString(3, owner);
                int n = upd.executeUpdate();
                if (n == 0) {
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO acl(owner, filename, grantee, can_read, can_write) VALUES(?,?,?,?,?)")) {
                        ins.setString(1, owner);
                        ins.setString(2, filename);
                        ins.setString(3, owner);
                        ins.setInt(4, 1);
                        ins.setInt(5, 1);
                        ins.executeUpdate();
                    }
                }
            }
        }
    }

    public static boolean canRead(String owner, String grantee, String filename) throws SQLException {
        try (Connection c = db();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT can_read FROM acl WHERE owner=? AND filename=? AND grantee=? LIMIT 1")) {
            ps.setString(1, owner);
            ps.setString(2, filename);
            ps.setString(3, grantee);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        }
    }

    public static boolean canWrite(String owner, String grantee, String filename) throws SQLException {
        try (Connection c = db();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT can_write FROM acl WHERE owner=? AND filename=? AND grantee=? LIMIT 1")) {
            ps.setString(1, owner);
            ps.setString(2, filename);
            ps.setString(3, grantee);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        }
    }

    public static String getOwner(String filename) throws SQLException {
        try (Connection c = db();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT owner FROM files WHERE filename=? ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, filename);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public static void shareFile(String owner, String filename, String grantee, boolean r, boolean w) throws SQLException {
        try (Connection c = db()) {
            try (PreparedStatement upd = c.prepareStatement(
                    "UPDATE acl SET can_read=?, can_write=? WHERE owner=? AND filename=? AND grantee=?")) {
                upd.setInt(1, r ? 1 : 0);
                upd.setInt(2, w ? 1 : 0);
                upd.setString(3, owner);
                upd.setString(4, filename);
                upd.setString(5, grantee);
                int n = upd.executeUpdate();
                if (n == 0) {
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO acl(owner, filename, grantee, can_read, can_write) VALUES(?,?,?,?,?)")) {
                        ins.setString(1, owner);
                        ins.setString(2, filename);
                        ins.setString(3, grantee);
                        ins.setInt(4, r ? 1 : 0);
                        ins.setInt(5, w ? 1 : 0);
                        ins.executeUpdate();
                    }
                }
            }
        }
    }
}