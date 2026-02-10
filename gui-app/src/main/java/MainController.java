import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class MainController {

    @FXML private Label sessionLabel;
    @FXML private TextArea outputArea;

    @FXML private TextField filenameField;
    @FXML private TextArea contentArea;

    public void setSession(String username, String token) {
        sessionLabel.setText("Session: " + SessionStore.latestSessionInfo());
        outputArea.setText("");
        outputArea.appendText("Logged in as: " + username + "\n");
        outputArea.appendText("Token: " + token + "\n\n");
    }

    private void log(String s) {
        outputArea.appendText(s + "\n");
    }

    @FXML
    private void onPing() {
        try { log(">> PING"); log(TcpClient.send("PING")); }
        catch (Exception e) { log("ERROR: " + e.getMessage()); }
    }

    @FXML
    private void onList() {
        try { log(">> LIST"); log(TcpClient.send("LIST")); }
        catch (Exception e) { log("ERROR: " + e.getMessage()); }
    }

    @FXML
    private void onStore() {
        String filename = filenameField.getText().trim();
        String content = contentArea.getText();
        if (filename.isEmpty()) { log("ERROR: filename required"); return; }

        try {
            String b64 = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
            log(">> STORE " + filename);
            log(TcpClient.send("STORE " + filename + " " + b64));
        } catch (Exception e) { log("ERROR: " + e.getMessage()); }
    }

    @FXML
    private void onLoad() {
        String filename = filenameField.getText().trim();
        if (filename.isEmpty()) { log("ERROR: filename required"); return; }

        try {
            log(">> LOAD " + filename);
            String resp = TcpClient.send("LOAD " + filename);
            log(resp);

            if (resp.startsWith("OK:")) {
                String[] parts = resp.split("\\s+");
                if (parts.length >= 3) {
                    String b64 = parts[2];
                    try {
                        byte[] bytes = Base64.getDecoder().decode(b64);
                        contentArea.setText(new String(bytes, StandardCharsets.UTF_8));
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) { log("ERROR: " + e.getMessage()); }
    }

    @FXML
    private void onDelete() {
        String filename = filenameField.getText().trim();
        if (filename.isEmpty()) { log("ERROR: filename required"); return; }

        try {
            log(">> DELETE " + filename);
            log(TcpClient.send("DELETE " + filename));
        } catch (Exception e) { log("ERROR: " + e.getMessage()); }
    }
}
