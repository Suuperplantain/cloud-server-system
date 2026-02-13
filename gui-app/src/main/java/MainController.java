import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class MainController {

    @FXML private Label sessionLabel;
    @FXML private TextArea outputArea;

    @FXML private TextField filenameField;
    @FXML private TextArea contentArea;

    @FXML private Button pingButton;
    @FXML private Button listButton;
    @FXML private Button storeButton;
    @FXML private Button loadButton;
    @FXML private Button deleteButton;

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

    private void setBusy(boolean busy) {
        if (listButton != null) listButton.setDisable(busy);
        if (storeButton != null) storeButton.setDisable(busy);
        if (loadButton != null) loadButton.setDisable(busy);
        if (deleteButton != null) deleteButton.setDisable(busy);
        if (filenameField != null) filenameField.setDisable(busy);
        if (contentArea != null) contentArea.setDisable(busy);
    }

    @FXML
    private void onPing() {
        // PING is quick; can stay synchronous.
        try { log(">> PING"); log(TcpClient.send("PING")); }
        catch (Exception e) { log("ERROR: " + e.getMessage()); }
    }

    @FXML
    private void onList() {
        if (!ensureLoggedIn()) return;

        final String cmd = "AUTH " + token + " LIST";
        log(">> " + cmd);
        setBusy(true);
        outputArea.appendText("Working...\n");

        new Thread(() -> {
            String result;
            try {
                result = TcpClient.send(cmd);
            } catch (Exception e) {
                result = "ERROR: " + e.getMessage();
            }

            final String toLog = result;
            Platform.runLater(() -> {
                log(toLog);
                setBusy(false);
            });
        }, "list-request-thread").start();
    }

    @FXML
    private void onStore() {
        if (!ensureLoggedIn()) return;

        final String filename = filenameField.getText().trim();
        final String content = contentArea.getText();
        if (filename.isEmpty()) { log("ERROR: filename required"); return; }

        final String b64 = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        final String cmd = "AUTH " + token + " STORE " + filename + " " + b64;
        log(">> " + cmd);
        setBusy(true);
        outputArea.appendText("Working...\n");

        new Thread(() -> {
            String result;
            try {
                result = TcpClient.send(cmd);
            } catch (Exception e) {
                result = "ERROR: " + e.getMessage();
            }

            final String toLog = result;
            Platform.runLater(() -> {
                log(toLog);
                setBusy(false);
            });
        }, "store-request-thread").start();
    }

    @FXML
    private void onLoad() {
        if (!ensureLoggedIn()) return;

        final String filename = filenameField.getText().trim();
        if (filename.isEmpty()) { log("ERROR: filename required"); return; }

        final String cmd = "AUTH " + token + " LOAD " + filename;
        log(">> " + cmd);
        setBusy(true);
        outputArea.appendText("Working...\n");

        new Thread(() -> {
            String resp;
            try {
                resp = TcpClient.send(cmd);
            } catch (Exception e) {
                resp = "ERROR: " + e.getMessage();
            }

            final String decodedContent;
            if (resp.startsWith("OK")) {
                String possibleContent = null;
                String[] parts = resp.split("\\s+");
                if (parts.length >= 2) {
                    String possibleB64 = parts[parts.length - 1];
                    try {
                        byte[] bytes = Base64.getDecoder().decode(possibleB64);
                        possibleContent = new String(bytes, StandardCharsets.UTF_8);
                    } catch (IllegalArgumentException ignored) {
                        // Not valid base64; leave content unchanged.
                    }
                }
                decodedContent = possibleContent;
            } else {
                decodedContent = null;
            }

            final String toLog = resp;
            Platform.runLater(() -> {
                log(toLog);
                if (decodedContent != null) {
                    contentArea.setText(decodedContent);
                }
                setBusy(false);
            });
        }, "load-request-thread").start();
    }

    @FXML
    private void onDelete() {
        if (!ensureLoggedIn()) return;

        final String filename = filenameField.getText().trim();
        if (filename.isEmpty()) { log("ERROR: filename required"); return; }

        final String cmd = "AUTH " + token + " DELETE " + filename;
        log(">> " + cmd);
        setBusy(true);
        outputArea.appendText("Working...\n");

        new Thread(() -> {
            String result;
            try {
                result = TcpClient.send(cmd);
            } catch (Exception e) {
                result = "ERROR: " + e.getMessage();
            }

            final String toLog = result;
            Platform.runLater(() -> {
                log(toLog);
                setBusy(false);
            });
        }, "delete-request-thread").start();
    }
}
