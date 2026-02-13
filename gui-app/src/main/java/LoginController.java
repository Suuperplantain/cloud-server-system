import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.UUID;

public class LoginController {

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label statusLabel;
    @FXML private Button loginButton;
    @FXML private Button registerButton;

    @FXML
    private void onLogin() {
        final String user = usernameField.getText().trim();
        final String pass = passwordField.getText();

        if (user.isEmpty()) {
            statusLabel.setText("Enter a username.");
            return;
        }
        if (pass == null || pass.isEmpty()) {
            statusLabel.setText("Enter a password.");
            return;
        }

        setBusy(true);
        statusLabel.setText("Logging in...");

        new Thread(() -> {
            String response;
            try {
                // Send exactly: LOGIN <username> <password>
                response = TcpClient.send("LOGIN " + user + " " + pass);
            } catch (IOException e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Login failed: " + e.getMessage());
                    setBusy(false);
                });
                return;
            }

            if (response == null) {
                Platform.runLater(() -> {
                    statusLabel.setText("Login failed: empty response from server");
                    setBusy(false);
                });
                return;
            }

            Platform.runLater(() -> {
                // Only succeed if response starts with "OK TOKEN " and the token is a valid UUID.
                if (response.startsWith("OK TOKEN ")) {
                    String[] parts = response.split("\\s+");
                    if (parts.length >= 3) {
                        String token = parts[2];
                        try {
                            UUID.fromString(token); // validate UUID

                            // Persist session and open main screen
                            SessionStore.createSession(user, token);

                            try {
                                FXMLLoader loader = new FXMLLoader(getClass().getResource("/main.fxml"));
                                Scene scene = new Scene(loader.load(), 900, 560);

                                MainController ctrl = loader.getController();
                                ctrl.setSession(user, token);

                                Stage stage = (Stage) usernameField.getScene().getWindow();
                                stage.setScene(scene);
                                stage.setResizable(true);
                                stage.centerOnScreen();
                            } catch (Exception e) {
                                statusLabel.setText("Failed to open main screen: " + e.getMessage());
                            } finally {
                                setBusy(false);
                            }
                            return;
                        } catch (IllegalArgumentException ignored) {
                            // fall through to error handling below
                        }
                    }
                }

                // Any other response is a login failure; show raw backend response.
                statusLabel.setText(response);
                setBusy(false);
            });
        }, "login-request-thread").start();
    }

    @FXML
    private void onRegister() {
        final String user = usernameField.getText().trim();
        final String pass = passwordField.getText();

        if (user.isEmpty() || pass == null || pass.isEmpty()) {
            statusLabel.setText("Enter username and password.");
            return;
        }

        setBusy(true);
        statusLabel.setText("Registering...");

        new Thread(() -> {
            String response;
            try {
                // Send exactly: REGISTER <username> <password>
                response = TcpClient.send("REGISTER " + user + " " + pass);
            } catch (IOException e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Register failed: " + e.getMessage());
                    setBusy(false);
                });
                return;
            }

            if (response == null) {
                Platform.runLater(() -> {
                    statusLabel.setText("Register failed: empty response from server");
                    setBusy(false);
                });
                return;
            }

            Platform.runLater(() -> {
                // Show raw backend response by default; mildly friendlier text on success.
                if (response.startsWith("OK")) {
                    statusLabel.setText("Registered. Now login.");
                } else {
                    statusLabel.setText(response);
                }
                setBusy(false);
            });
        }, "register-request-thread").start();
    }

    private void setBusy(boolean busy) {
        if (loginButton != null) {
            loginButton.setDisable(busy);
        }
        if (registerButton != null) {
            registerButton.setDisable(busy);
        }
        if (usernameField != null) {
            usernameField.setDisable(busy);
        }
        if (passwordField != null) {
            passwordField.setDisable(busy);
        }
    }
}
