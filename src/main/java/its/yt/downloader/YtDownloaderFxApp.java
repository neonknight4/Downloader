package its.yt.downloader;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
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
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

public class YtDownloaderFxApp extends Application {

    private static final boolean WINDOWS
            = System.getProperty("os.name").toLowerCase().contains("win");

    private static final String YT_DLP
            = WINDOWS
            ? findWindowsTool("yt-dlp.exe")
            : "/home/mihailo-jankovic/.local/bin/yt-dlp";

    private final TextArea inputArea = new TextArea();
    private final TextArea logArea = new TextArea();
    private final Button btnDownload = new Button("Download MP3");
    private final Button btnDownloadMp4 = new Button("Download MP4");
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

        btnDownload.setOnAction(e -> startDownload(Format.MP3));
        btnDownloadMp4.setOnAction(e -> startDownload(Format.MP4));
        btnStop.setOnAction(e -> stopDownload());

        HBox buttons = new HBox(10, btnDownload, btnDownloadMp4, btnStop);
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
        stage.setTitle("YouTube Downloader");
        stage.getIcons().addAll(loadAppIcons());
        stage.setScene(scene);
        stage.show();
    }

    private List<Image> loadAppIcons() {
        List<Image> icons = new ArrayList<>();
        for (int size : new int[]{16, 24, 32, 48, 64, 128, 256}) {
            var in = getClass().getResourceAsStream("icons/icon-" + size + ".png");
            if (in != null) {
                icons.add(new Image(in));
            }
        }
        return icons;
    }

    private void startDownload(Format format) {
        startTimeMs = System.currentTimeMillis();
        String text = inputArea.getText();

        if (text == null || text.trim().isEmpty()) {
            alert("Input is empty", "Paste your oblast + URLs first.");
            return;
        }

        stopRequested = false;
        btnDownload.setDisable(true);
        btnDownloadMp4.setDisable(true);
        btnStop.setDisable(false);
        logArea.clear();

        log("=== START " + LocalDateTime.now() + " (" + format + ") ===");
        log("yt-dlp: " + YT_DLP);
        log("Output base: " + baseDownloadDir().toAbsolutePath());

        Thread worker = new Thread(() -> {
            try {
                downloadFromText(text, format);
                ui(() -> statusLabel.setText(stopRequested ? "Stopped." : "Done."));
            } catch (Exception ex) {
                log("ERROR: " + ex.getMessage());
                ui(() -> statusLabel.setText("Error."));
            } finally {
                ui(() -> {
                    btnDownload.setDisable(false);
                    btnDownloadMp4.setDisable(false);
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

    private void downloadFromText(String text, Format format) throws Exception {

        List<String> lines = List.of(text.split("\\R")); // \R = any line break

        // Faza 1: parsiranje
        List<OblastJob> jobs = new ArrayList<>();
        String currentOblast = null;
        List<String> currentUrls = new ArrayList<>();

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (isOblastHeader(line)) {
                if (currentOblast != null && !currentUrls.isEmpty()) {
                    jobs.add(new OblastJob(currentOblast, new ArrayList<>(currentUrls)));
                }
                currentOblast = line;
                currentUrls = new ArrayList<>();
                continue;
            }
            if (line.startsWith("http://") || line.startsWith("https://")) {
                currentUrls.add(line);
            }
        }
        if (currentOblast != null && !currentUrls.isEmpty()) {
            jobs.add(new OblastJob(currentOblast, currentUrls));
        }

        // Faza 2: pitaj start index za oblasti < 8 pesama (pre skidanja)
        for (OblastJob job : jobs) {
            if (job.urls.size() < 8) {
                job.startIndex = askStartIndex(job.name, job.urls.size());
            }
        }

        // Faza 3: cist start - brisi sve oblasti od proslog puta
        cleanPreviousDownloads(format);

        // Faza 4: skidanje
        List<OblastSummary> allSummaries = new ArrayList<>();
        for (OblastJob job : jobs) {
            if (stopRequested) {
                break;
            }
            OblastSummary s = downloadOblast(job.name, job.urls, job.startIndex, format);
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

    private OblastSummary downloadOblast(String oblastName, List<String> urls, int startIndex, Format format) throws Exception {

        OblastSummary summary = new OblastSummary(oblastName);

        if (stopRequested) {
            return summary;
        }

        Path base = baseDownloadDir();
        Path outDir = format == Format.MP4
                ? base.resolve("mp4").resolve(oblastName)
                : base.resolve(oblastName);

        Files.createDirectories(outDir);

        log("");
        log("=======================================");
        log("Oblast: " + oblastName + " [" + format + "]");
        log("URLs: " + urls.size());
        log("Start index: " + startIndex);
        log("Folder: " + outDir.toAbsolutePath());
        log("=======================================");

        for (int i = 0; i < urls.size(); i++) {

            if (stopRequested) {
                return summary;
            }

            int index = startIndex + i;
            String url = urls.get(i);

            Path outFile = outDir.resolve(index + "." + format.ext);

            ui(() -> statusLabel.setText("Downloading " + oblastName + " " + index + "/" + urls.size()));

            String outputTemplate = outDir.resolve(index + ".%(ext)s").toString();

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
            if (WINDOWS) {
                command.add("--ffmpeg-location");
                command.add(ffmpegDir());
            }

            if (format == Format.MP4) {
                // video + audio, merged to mp4
                command.add("-f");
                command.add("bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best");
                command.add("--merge-output-format");
                command.add("mp4");
            } else {
                // mp3 extract
                command.add("-x");
                command.add("--audio-format");
                command.add("mp3");
                command.add("--audio-quality");
                command.add("0");
            }

            // output name 1.mp3/1.mp4, 2... ...
            command.add("--output");
            command.add(outputTemplate);

            String cleanUrl = cleanYoutubeUrl(url);
            command.add(cleanUrl);

            int exit = run(command);

            if (exit == 0 && Files.exists(outFile)) {
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

    private int askStartIndex(String oblastName, int urlCount) {
        int[] result = {1};
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            TextInputDialog dialog = new TextInputDialog("1");
            dialog.setTitle("Start index");
            dialog.setHeaderText("Oblast: " + oblastName + " (" + urlCount + " pesama)");
            dialog.setContentText("Od kog indexa imenujemo fajlove?");
            dialog.showAndWait().ifPresent(val -> {
                try {
                    int v = Integer.parseInt(val.trim());
                    if (v >= 1) {
                        result[0] = v;
                    }
                } catch (NumberFormatException ignored) {
                }
            });
            latch.countDown();
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result[0];
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

    /**
     * Folder gde app pise. Na Windowsu je app instaliran u Program Files (nije
     * upisiv), pa idemo u user home.
     */
    private static Path baseDownloadDir() {
        return WINDOWS
                ? Path.of(System.getProperty("user.home"), "Downloads", "YouTube Downloader")
                : Path.of("downloads");
    }

    /**
     * Folder instalacije. Kod jpackage launcher setuje jpackage.app-path na
     * putanju .exe-a; van instalacije padamo na folder jar-a.
     */
    private static Path appDir() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            Path parent = Path.of(appPath).getParent();
            if (parent != null) {
                return parent;
            }
        }
        try {
            var cs = YtDownloaderFxApp.class.getProtectionDomain().getCodeSource();
            if (cs != null && "file".equals(cs.getLocation().getProtocol())) {
                Path p = Path.of(cs.getLocation().toURI());
                return Files.isDirectory(p) ? p : p.getParent();
            }
        } catch (Exception ignored) {
        }
        return Path.of(".").toAbsolutePath();
    }

    /**
     * Kandidati gde trazimo yt-dlp.exe / ffmpeg.exe: install root (tu ih
     * jpackage --app-content stavlja), njegov "app" podfolder, pa navise, pa
     * cwd.
     */
    private static List<Path> toolDirs() {
        List<Path> dirs = new ArrayList<>();
        Path d = appDir();
        for (int i = 0; i < 3 && d != null; i++) {
            dirs.add(d);
            dirs.add(d.resolve("app"));
            d = d.getParent();
        }
        dirs.add(Path.of(".").toAbsolutePath());
        return dirs;
    }

    private static String findWindowsTool(String exeName) {
        for (Path dir : toolDirs()) {
            Path candidate = dir.resolve(exeName);
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        return exeName; // fallback: PATH
    }

    private static String ffmpegDir() {
        for (Path dir : toolDirs()) {
            if (Files.isRegularFile(dir.resolve("ffmpeg.exe"))) {
                return dir.toString();
            }
        }
        return ".";
    }

    /**
     * Brise sve oblasti od prethodnih skidanja za dati format.
     */
    private void cleanPreviousDownloads(Format format) throws Exception {
        Path base = baseDownloadDir();

        if (format == Format.MP4) {
            Path mp4Root = base.resolve("mp4");
            if (Files.exists(mp4Root)) {
                log("Cleaning: " + mp4Root.toAbsolutePath());
                deleteDirectoryRecursively(mp4Root);
            }
            Files.createDirectories(mp4Root);
            return;
        }

        // MP3: brisi samo N.oblast foldere u rootu, mp4 podfolder ostaje
        Files.createDirectories(base);
        try (Stream<Path> children = Files.list(base)) {
            List<Path> oblasti = children
                    .filter(Files::isDirectory)
                    .filter(p -> isOblastHeader(p.getFileName().toString()))
                    .toList();

            for (Path p : oblasti) {
                log("Cleaning: " + p.toAbsolutePath());
                deleteDirectoryRecursively(p);
            }
        }
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

    private enum Format {
        MP3("mp3"),
        MP4("mp4");

        final String ext;

        Format(String ext) {
            this.ext = ext;
        }
    }

    private static class OblastJob {

        String name;
        List<String> urls;
        int startIndex = 1;

        OblastJob(String name, List<String> urls) {
            this.name = name;
            this.urls = urls;
        }
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
