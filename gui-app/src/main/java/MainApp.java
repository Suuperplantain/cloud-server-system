import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MainApp extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        SessionStore.init();

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/login.fxml"));
        Scene scene = new Scene(loader.load(), 520, 320);

        stage.setTitle("Cloud System Portal (JavaFX + SQLite)");
        stage.setScene(scene);
        stage.setResizable(false);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
