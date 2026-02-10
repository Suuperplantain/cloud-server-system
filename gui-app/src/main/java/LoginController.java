import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;

public class LoginController {

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label statusLabel;

    @FXML
    private void onLogin() {
        String user = usernameField.getText().trim();

        if (user.isEmpty()) {
            statusLabel.setText("Enter a username.");
            return;
        }

        // GUI-first: local SQLite session
        String token = SessionStore.createSession(user);

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
        }
    }
}
