package its.yt.downloader;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public class YtDownloaderFxApp extends Application {

    private static final String YT_DLP
            = System.getProperty("os.name").toLowerCase().contains("win")
            ? "yt-dlp.exe"
            : "/home/mihailo-jankovic/.local/bin/yt-dlp";

    private final TextArea inputArea = new TextArea();
    private final TextArea logArea = new TextArea();
    private final Button btnDownload = new Button("Download MP3");
    private final Button btnStop = new Button("Stop");
    private final Label statusLabel = new Label("Ready.");

    private volatile Process currentProcess;
    private volatile boolean stopRequested = false;
    private volatile long startTimeMs = 0;

    @Override
    public void start(Stage stage) {

        inputArea.setText(
                "1.oblast\n\n"
                + "2.oblast\n\n"
                + "3.oblast\n\n"
                + "4.oblast\n\n"
                + "5.oblast\n\n"
                + "6.oblast\n\n"
                + "7.oblast\n\n"
                + "8.oblast\n"
        );

        logArea.setEditable(false);
        logArea.setWrapText(true);

        btnStop.setDisable(true);

        btnDownload.setOnAction(e -> startDownload());
        btnStop.setOnAction(e -> stopDownload());

        HBox buttons = new HBox(10, btnDownload, btnStop);
        VBox root = new VBox(10,
                new Label("Input (oblast format):"),
                inputArea,
                buttons,
                statusLabel,
                new Label("Log:"),
                logArea
        );

        root.setPadding(new Insets(12));

        Scene scene = new Scene(root, 900, 700);
        stage.setTitle("YT MP3 Downloader (Oblasti)");
        stage.setScene(scene);
        stage.show();
    }

    private void startDownload() {
        startTimeMs = System.currentTimeMillis();
        String text = inputArea.getText();

        if (text == null || text.trim().isEmpty()) {
            alert("Input is empty", "Paste your oblast + URLs first.");
            return;
        }

        stopRequested = false;
        btnDownload.setDisable(true);
        btnStop.setDisable(false);
        logArea.clear();

        log("=== START " + LocalDateTime.now() + " ===");

        Thread worker = new Thread(() -> {
            try {
                downloadFromText(text);
                ui(() -> statusLabel.setText(stopRequested ? "Stopped." : "Done."));
            } catch (Exception ex) {
                log("ERROR: " + ex.getMessage());
                ui(() -> statusLabel.setText("Error."));
            } finally {
                ui(() -> {
                    btnDownload.setDisable(false);
                    btnStop.setDisable(true);
                });
            }
        });

        worker.setDaemon(true);
        worker.start();
    }

    private void stopDownload() {
        stopRequested = true;

        Process p = currentProcess;
        if (p != null && p.isAlive()) {
            log("Stopping current yt-dlp process...");
            p.destroy();
        }

        ui(() -> statusLabel.setText("Stop requested..."));
    }

    private void downloadFromText(String text) throws Exception {

        List<String> lines = List.of(text.split("\\R")); // \R = any line break

        String currentOblast = null;
        List<String> currentUrls = new ArrayList<>();

        // ovde skupljamo summary-je za sve oblasti
        List<OblastSummary> allSummaries = new ArrayList<>();

        for (String rawLine : lines) {
            if (stopRequested) {
                break;
            }

            String line = rawLine.trim();

            if (line.isEmpty()) {
                continue;
            }

            if (isOblastHeader(line)) {
                // ako imamo prethodnu oblast, završi je
                if (currentOblast != null) {
                    OblastSummary s = downloadOblastMp3(currentOblast, currentUrls);
                    allSummaries.add(s);
                }

                // započni novu oblast
                currentOblast = line;
                currentUrls = new ArrayList<>();
                continue;
            }

            // url linije
            if (line.startsWith("http://") || line.startsWith("https://")) {
                currentUrls.add(line);
            }
        }

        // poslednja oblast (ako postoji)
        if (!stopRequested && currentOblast != null) {
            OblastSummary s = downloadOblastMp3(currentOblast, currentUrls);
            allSummaries.add(s);
        }

        // FINAL SUMMARY na kraju celog run-a
        log("");
        log("########## FINAL SUMMARY ##########");

        for (OblastSummary s : allSummaries) {

            log("========== SUMMARY  " + s.oblastName + " ==========");
            log("OK=" + s.ok + " FAIL=" + s.fail);

            if (!s.failedSongs.isEmpty()) {
                log("FAILED SONGS:");
                for (String f : s.failedSongs) {
                    log(" - " + f);
                }
            }

            log("=====================================");
        }

        log("########## END ##########");
        long elapsedMs = System.currentTimeMillis() - startTimeMs;
        log("TOTAL TIME: " + formatDuration(elapsedMs));

    }

    private boolean isOblastHeader(String line) {
        return line.matches("\\d+\\.oblast");
    }

    private OblastSummary downloadOblastMp3(String oblastName, List<String> urls) throws Exception {

        OblastSummary summary = new OblastSummary(oblastName);

        if (stopRequested) {
            return summary;
        }

        if (urls == null || urls.isEmpty()) {
            log("Skipping " + oblastName + " (no URLs)");
            return summary;
        }

        Path outDir = Path.of("downloads", oblastName);

        // ako folder postoji, briši ga komplet
        if (Files.exists(outDir)) {
            log("Deleting existing folder: " + outDir.toAbsolutePath());
            deleteDirectoryRecursively(outDir);
        }

        // napravi folder ponovo
        Files.createDirectories(outDir);

        log("");
        log("=======================================");
        log("Oblast: " + oblastName);
        log("URLs: " + urls.size());
        log("Folder: " + outDir.toAbsolutePath());
        log("=======================================");

        for (int i = 0; i < urls.size(); i++) {

            if (stopRequested) {
                return summary;
            }

            int index = i + 1;
            String url = urls.get(i);

            Path mp3File = outDir.resolve(index + ".mp3");

            ui(() -> statusLabel.setText("Downloading " + oblastName + " " + index + "/" + urls.size()));

            String outputTemplate = outDir.toString() + "/" + index + ".%(ext)s";

            List<String> command = new ArrayList<>();
            command.add(YT_DLP);

            // stability / less random fails
            command.add("--no-update");
            command.add("--ignore-errors");
            command.add("--retries");
            command.add("3");
            command.add("--fragment-retries");
            command.add("3");

            // youtube client
            command.add("--extractor-args");
            command.add("youtube:player_client=android");

            // IMPORTANT for Windows portable: find ffmpeg in same folder
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                command.add("--ffmpeg-location");
                command.add(".");
            }

            // mp3 extract
            command.add("-x");
            command.add("--audio-format");
            command.add("mp3");
            command.add("--audio-quality");
            command.add("0");

            // output name 1.mp3, 2.mp3 ...
            command.add("--output");
            command.add(outputTemplate);

            String cleanUrl = cleanYoutubeUrl(url);
            command.add(cleanUrl);

            int exit = run(command);

            if (exit == 0 && Files.exists(mp3File)) {
                summary.ok++;
            } else {
                summary.fail++;
                summary.failedSongs.add(index + " -> " + url);
            }
        }

        return summary;
    }

    private int run(List<String> command) throws Exception {

        log("");
        log("CMD: " + String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        currentProcess = process;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (stopRequested) {
                    break;
                }
                log(line);
            }
        }

        int exitCode = process.waitFor();
        log("Exit code: " + exitCode);

        currentProcess = null;
        return exitCode;
    }

    private void log(String msg) {
        ui(() -> {
            logArea.appendText(msg + "\n");
            logArea.positionCaret(logArea.getText().length());
        });
    }

    private void ui(Runnable r) {
        Platform.runLater(r);
    }

    private static void deleteDirectoryRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }

        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()) // prvo briše fajlove, pa folder
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to delete: " + path + " -> " + e.getMessage(), e);
                        }
                    });
        }
    }

    private void alert(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle(title);
        a.setHeaderText(null);
        a.setContentText(msg);
        a.showAndWait();
    }

    public static void main(String[] args) {
        launch(args);
    }

    private static class OblastSummary {

        String oblastName;
        int ok;
        int fail;
        List<String> failedSongs = new ArrayList<>();

        OblastSummary(String oblastName) {
            this.oblastName = oblastName;
        }
    }

    private String cleanYoutubeUrl(String url) {
        if (url == null) {
            return null;
        }
        url = url.trim();

        // ako ima &list= ili bilo šta posle &, uzmi samo do &
        int amp = url.indexOf("&");
        if (amp > 0) {
            url = url.substring(0, amp);
        }
        return url;
    }

    private static String formatDuration(long ms) {
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;

        if (minutes >= 60) {
            long hours = minutes / 60;
            long remMin = minutes % 60;
            return String.format("%02d:%02d:%02d", hours, remMin, seconds);
        }

        return String.format("%02d:%02d", minutes, seconds);
    }

}
