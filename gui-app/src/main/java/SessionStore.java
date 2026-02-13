import java.sql.*;

public class SessionStore {

    private static final String DB_URL = "jdbc:sqlite:sessions.db";

    public static void init() {
        try (Connection c = DriverManager.getConnection(DB_URL);
             Statement st = c.createStatement()) {

            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS sessions (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                            "username TEXT NOT NULL," +
                            "token TEXT NOT NULL," +
                            "created_at DATETIME DEFAULT CURRENT_TIMESTAMP" +
                            ")"
            );

        } catch (SQLException e) {
            System.out.println("SQLITE INIT ERROR: " + e.getMessage());
        }
    }

    public static String createSession(String username, String token) {
        try (Connection c = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO sessions(username, token) VALUES(?,?)")) {
            ps.setString(1, username);
            ps.setString(2, token);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("SQLITE SESSION ERROR: " + e.getMessage());
        }
        return token;
    }

    public static String latestSessionInfo() {
        try (Connection c = DriverManager.getConnection(DB_URL);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT username, token, created_at FROM sessions ORDER BY id DESC LIMIT 1")) {
            if (rs.next()) {
                return "user=" + rs.getString(1) + " token=" + rs.getString(2) + " at=" + rs.getString(3);
            }
        } catch (SQLException e) {
            return "SQLITE ERROR: " + e.getMessage();
        }
        return "No session yet";
    }
}
