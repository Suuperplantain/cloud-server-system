import java.sql.*;

public class MySqlStore {

    private static final String URL =
            "jdbc:mysql://mysql:3306/cloud?useSSL=false&allowPublicKeyRetrieval=true";
    private static final String USER = "cloud";
    private static final String PASS = "cloud";

    public static Connection conn() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASS);
    }

    public static void ensureUser(String username) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT IGNORE INTO users(username, pass_hash, role) VALUES(?,?,?)")) {
            ps.setString(1, username);
            ps.setString(2, "TEMP");
            ps.setString(3, "STANDARD");
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("MYSQL ERROR: " + e.getMessage());
        }
    }

    public static void upsertFileMeta(String owner, String filename, String storageNode, long sizeBytes) throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO files(owner, filename, storage_node, size_bytes) VALUES(?,?,?,?) " +
                             "ON DUPLICATE KEY UPDATE storage_node=VALUES(storage_node), size_bytes=VALUES(size_bytes)")) {
            ps.setString(1, owner);
            ps.setString(2, filename);
            ps.setString(3, storageNode);
            ps.setLong(4, sizeBytes);
            ps.executeUpdate();
        }

        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT IGNORE INTO acl(owner, filename, grantee, can_read, can_write) VALUES(?,?,?,?,?)")) {
            ps.setString(1, owner);
            ps.setString(2, filename);
            ps.setString(3, owner);
            ps.setBoolean(4, true);
            ps.setBoolean(5, true);
            ps.executeUpdate();
        }
    }

    public static void deleteFileMeta(String owner, String filename) throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("DELETE FROM files WHERE owner=? AND filename=?")) {
            ps.setString(1, owner);
            ps.setString(2, filename);
            ps.executeUpdate();
        }
    }

    public static void log(String username, String action, String details, String storageNode) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log(username, action, details, storage_node) VALUES(?,?,?,?)")) {
            ps.setString(1, username);
            ps.setString(2, action);
            ps.setString(3, details);
            ps.setString(4, storageNode);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("MYSQL ERROR: " + e.getMessage());
        }
    }
}