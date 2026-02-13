import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class MainController {

    @FXML private Label sessionLabel;
    @FXML private TextArea outputArea;

    @FXML private TextField filenameField;
    @FXML private TextArea contentArea;

    private String username;
    private String token;

    public void setSession(String username, String token) {
        this.username = username;
        this.token = token;

        sessionLabel.setText("Session: " + SessionStore.latestSessionInfo());
        outputArea.setText("");
        outputArea.appendText("Logged in as: " + username + "\n");
        outputArea.appendText("Token: " + token + "\n\n");
    }

    private void log(String s) {
        outputArea.appendText(s + "\n");
    }

    private boolean ensureLoggedIn() {
        if (token == null || token.isEmpty()) {
            log("ERR Not logged in");
            return false;
        }
        return true;
    }

    @FXML
    private void onPing() {
        try { log(">> PING"); log(TcpClient.send("PING")); }
        catch (Exception e) { log("ERROR: " + e.getMessage()); }
    }

    @FXML
    private void onList() {
        if (!ensureLoggedIn()) return;
        try {
            String cmd = "AUTH " + token + " LIST";
            log(">> " + cmd);
            log(TcpClient.send(cmd));
        } catch (Exception e) { log("ERROR: " + e.getMessage()); }
    }

    @FXML
    private void onStore() {
        if (!ensureLoggedIn()) return;

        String filename = filenameField.getText().trim();
        String content = contentArea.getText();
        if (filename.isEmpty()) { log("ERROR: filename required"); return; }

        try {
            String b64 = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
            String cmd = "AUTH " + token + " STORE " + filename + " " + b64;
            log(">> " + cmd);
            log(TcpClient.send(cmd));
        } catch (Exception e) { log("ERROR: " + e.getMessage()); }
    }

    @FXML
    private void onLoad() {
        if (!ensureLoggedIn()) return;

        String filename = filenameField.getText().trim();
        if (filename.isEmpty()) { log("ERROR: filename required"); return; }

        try {
            String cmd = "AUTH " + token + " LOAD " + filename;
            log(">> " + cmd);
            String resp = TcpClient.send(cmd);
            log(resp);

            // Optional: if response seems to contain base64 data, decode into content area.
            if (resp.startsWith("OK")) {
                String[] parts = resp.split("\\s+");
                if (parts.length >= 2) {
                    String possibleB64 = parts[parts.length - 1];
                    try {
                        byte[] bytes = Base64.getDecoder().decode(possibleB64);
                        contentArea.setText(new String(bytes, StandardCharsets.UTF_8));
                    } catch (IllegalArgumentException ignored) {
                        // Not valid base64; leave content area unchanged.
                    }
                }
            }
        } catch (Exception e) { log("ERROR: " + e.getMessage()); }
    }

    @FXML
    private void onDelete() {
        if (!ensureLoggedIn()) return;

        String filename = filenameField.getText().trim();
        if (filename.isEmpty()) { log("ERROR: filename required"); return; }

        try {
            String cmd = "AUTH " + token + " DELETE " + filename;
            log(">> " + cmd);
            log(TcpClient.send(cmd));
        } catch (Exception e) { log("ERROR: " + e.getMessage()); }
    }
}
