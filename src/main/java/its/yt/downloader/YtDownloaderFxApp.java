package its.yt.downloader;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
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

    private static final int MAX_LOG_LINES = 5000;
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final TextArea inputArea = new TextArea();
    private final ListView<String> logView = new ListView<>();
    private final ObservableList<String> logLines = FXCollections.observableArrayList();
    private final Button btnDownload = new Button("Download MP3");
    private final Button btnDownloadMp4 = new Button("Download MP4");
    private final Button btnStop = new Button("Stop");
    private final Button btnOpenFolder = new Button("Otvori folder");
    private final CheckBox chkOriginalNames = new CheckBox("Originalan naziv");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label statusLabel = new Label("Spreman.");

    private volatile Process currentProcess;
    private volatile boolean stopRequested = false;
    private volatile long startTimeMs = 0;
    private volatile int totalUrls = 0;
    private volatile int doneUrls = 0;

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
        inputArea.setPrefRowCount(10);

        VBox root = new VBox(12,
                buildHeader(),
                buildSection("LINKOVI PO OBLASTIMA", inputArea, false),
                buildControls(),
                buildProgress(),
                buildSection("LOG", buildLogView(), true)
        );
        root.setPadding(new Insets(16));

        Scene scene = new Scene(root, 940, 760);
        scene.getStylesheets().add(getClass().getResource("style.css").toExternalForm());

        stage.setTitle("YouTube Downloader");
        stage.getIcons().addAll(loadAppIcons());
        stage.setMinWidth(700);
        stage.setMinHeight(520);
        stage.setScene(scene);
        stage.show();
    }

    private HBox buildHeader() {
        ImageView logo = new ImageView();
        var iconStream = getClass().getResourceAsStream("icons/icon-32.png");
        if (iconStream != null) {
            logo.setImage(new Image(iconStream));
        }

        Label title = new Label("YouTube Downloader");
        title.getStyleClass().add("app-title");

        Label subtitle = new Label("MP3 / MP4 po oblastima");
        subtitle.getStyleClass().add("app-subtitle");

        VBox texts = new VBox(1, title, subtitle);

        HBox header = new HBox(12, logo, texts);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("app-header");
        return header;
    }

    /**
     * Naslov sekcije + sadrzaj. grow=true znaci da sekcija jede visak visine
     * pri resize-u.
     */
    private VBox buildSection(String title, Region content, boolean grow) {
        Label label = new Label(title);
        label.getStyleClass().add("section-label");

        VBox box = new VBox(5, label, content);
        if (grow) {
            VBox.setVgrow(box, Priority.ALWAYS);
            VBox.setVgrow(content, Priority.ALWAYS);
        }
        return box;
    }

    private HBox buildControls() {
        btnDownload.getStyleClass().add("button-primary");
        btnDownloadMp4.getStyleClass().add("button-primary");
        btnStop.getStyleClass().add("button-stop");
        btnStop.setDisable(true);

        btnDownload.setOnAction(e -> startDownload(Format.MP3));
        btnDownloadMp4.setOnAction(e -> startDownload(Format.MP4));
        btnStop.setOnAction(e -> stopDownload());
        btnOpenFolder.setOnAction(e -> openDownloadsFolder());

        chkOriginalNames.setTooltip(new Tooltip(
                "Cekirano: fajlovi zadrzavaju originalan YouTube naziv.\n"
                + "Necekirano: fajlovi se imenuju 1, 2, 3..."));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox controls = new HBox(10,
                btnDownload, btnDownloadMp4, btnStop, chkOriginalNames, spacer, btnOpenFolder);
        controls.setAlignment(Pos.CENTER_LEFT);
        return controls;
    }

    private HBox buildProgress() {
        progressBar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(progressBar, Priority.ALWAYS);

        statusLabel.getStyleClass().add("status-label");
        statusLabel.setMinWidth(260);

        HBox row = new HBox(12, progressBar, statusLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private ListView<String> buildLogView() {
        logView.setItems(logLines);
        logView.getStyleClass().add("log-list");
        logView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        logView.setCellFactory(lv -> new LogCell());

        MenuItem copySelected = new MenuItem("Kopiraj izabrano");
        copySelected.setOnAction(e -> copyToClipboard(logView.getSelectionModel().getSelectedItems()));

        MenuItem copyAll = new MenuItem("Kopiraj ceo log");
        copyAll.setOnAction(e -> copyToClipboard(logLines));

        logView.setContextMenu(new ContextMenu(copySelected, copyAll));
        return logView;
    }

    private void copyToClipboard(List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(String.join("\n", lines));
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void openDownloadsFolder() {
        Path dir = baseDownloadDir();
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            log("ERROR: ne mogu da napravim folder: " + e.getMessage());
            return;
        }

        Thread t = new Thread(() -> {
            try {
                Desktop.getDesktop().open(dir.toFile());
            } catch (Exception e) {
                log("ERROR: ne mogu da otvorim folder: " + e.getMessage());
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /**
     * Boji log liniju prema sadrzaju.
     */
    private static class LogCell extends ListCell<String> {

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("log-error", "log-ok", "log-cmd", "log-header");

            if (empty || item == null) {
                setText(null);
                return;
            }
            setText(item);

            String body = stripTimestamp(item);
            String u = body.toUpperCase();

            if (u.startsWith("OK=")) {
                getStyleClass().add(u.contains("FAIL=0") ? "log-ok" : "log-error");
            } else if (u.contains("ERROR") || u.contains("FAIL") || u.contains("WARNING")) {
                getStyleClass().add("log-error");
            } else if (u.contains("EXIT CODE: 0")) {
                getStyleClass().add("log-ok");
            } else if (body.startsWith("CMD:") || body.startsWith("[")) {
                getStyleClass().add("log-cmd");
            } else if (body.startsWith("=") || body.startsWith("#")) {
                getStyleClass().add("log-header");
            }
        }

        /**
         * Skida "[HH:mm:ss] " prefiks da bi klasifikacija gledala pravi tekst.
         */
        private static String stripTimestamp(String s) {
            boolean stamped = s.length() > 11
                    && s.charAt(0) == '[' && s.charAt(3) == ':' && s.charAt(6) == ':' && s.charAt(9) == ']';
            return stamped ? s.substring(11) : s;
        }
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

        boolean originalNames = chkOriginalNames.isSelected();

        stopRequested = false;
        totalUrls = 0;
        doneUrls = 0;
        progressBar.setProgress(0);
        statusLabel.setText("Priprema...");
        btnDownload.setDisable(true);
        btnDownloadMp4.setDisable(true);
        chkOriginalNames.setDisable(true);
        btnStop.setDisable(false);
        logLines.clear();

        log("=== START " + LocalDateTime.now() + " (" + format + ") ===");
        log("yt-dlp: " + YT_DLP);
        log("Output base: " + baseDownloadDir().toAbsolutePath());
        log("Nazivi fajlova: " + (originalNames ? "originalni" : "1, 2, 3..."));

        Thread worker = new Thread(() -> {
            try {
                downloadFromText(text, format, originalNames);
                ui(() -> {
                    if (!stopRequested) {
                        progressBar.setProgress(1);
                    }
                    statusLabel.setText(stopRequested ? "Zaustavljeno." : "Gotovo.");
                });
            } catch (Exception ex) {
                log("ERROR: " + ex.getMessage());
                ui(() -> statusLabel.setText("Greska."));
            } finally {
                ui(() -> {
                    btnDownload.setDisable(false);
                    btnDownloadMp4.setDisable(false);
                    chkOriginalNames.setDisable(false);
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

        ui(() -> statusLabel.setText("Zaustavljanje..."));
    }

    private void downloadFromText(String text, Format format, boolean originalNames) throws Exception {

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

        // Faza 2: pitaj start index za oblasti < 8 pesama (pre skidanja).
        // Kod originalnih naziva nema indexiranja, pa nema ni pitanja.
        if (!originalNames) {
            for (OblastJob job : jobs) {
                if (job.urls.size() < 8) {
                    job.startIndex = askStartIndex(job.name, job.urls.size());
                }
            }
        }

        totalUrls = jobs.stream().mapToInt(j -> j.urls.size()).sum();
        doneUrls = 0;

        // Faza 3: cist start - brisi sve oblasti od proslog puta
        cleanPreviousDownloads(format);

        // Faza 4: skidanje
        List<OblastSummary> allSummaries = new ArrayList<>();
        for (OblastJob job : jobs) {
            if (stopRequested) {
                break;
            }
            OblastSummary s = downloadOblast(job.name, job.urls, job.startIndex, format, originalNames);
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

    private OblastSummary downloadOblast(String oblastName, List<String> urls, int startIndex, Format format,
            boolean originalNames) throws Exception {

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
        log(originalNames ? "Naziv: originalan" : "Start index: " + startIndex);
        log("Folder: " + outDir.toAbsolutePath());
        log("=======================================");

        for (int i = 0; i < urls.size(); i++) {

            if (stopRequested) {
                return summary;
            }

            int index = startIndex + i;
            int redniBroj = i + 1;
            String url = urls.get(i);

            // kod originalnih naziva ime fajla ne znamo unapred -> brojimo fajlove
            Path outFile = originalNames ? null : outDir.resolve(index + "." + format.ext);
            int filesBefore = originalNames ? countFiles(outDir, format) : 0;

            updateProgress(oblastName + "  " + redniBroj + "/" + urls.size()
                    + "   (ukupno " + doneUrls + "/" + totalUrls + ")");

            String outputTemplate = originalNames
                    ? outDir.resolve("%(title)s.%(ext)s").toString()
                    : outDir.resolve(index + ".%(ext)s").toString();

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

            boolean downloaded = originalNames
                    ? countFiles(outDir, format) > filesBefore
                    : Files.exists(outFile);

            if (exit == 0 && downloaded) {
                summary.ok++;
            } else {
                summary.fail++;
                summary.failedSongs.add((originalNames ? redniBroj : index) + " -> " + url);
            }

            doneUrls++;
            updateProgress(oblastName + "  " + redniBroj + "/" + urls.size()
                    + "   (ukupno " + doneUrls + "/" + totalUrls + ")");
        }

        return summary;
    }

    /**
     * Broj gotovih fajlova datog formata u folderu (.part i slicni se ne
     * racunaju).
     */
    private static int countFiles(Path dir, Format format) throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            return (int) files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith("." + format.ext))
                    .count();
        }
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
        String line = msg.isEmpty()
                ? ""
                : "[" + LocalTime.now().format(LOG_TIME) + "] " + msg;

        ui(() -> {
            logLines.add(line);
            if (logLines.size() > MAX_LOG_LINES) {
                logLines.remove(0, logLines.size() - MAX_LOG_LINES);
            }
            logView.scrollTo(logLines.size() - 1);
        });
    }

    private void updateProgress(String status) {
        int done = doneUrls;
        int total = totalUrls;
        ui(() -> {
            progressBar.setProgress(total <= 0 ? 0 : (double) done / total);
            statusLabel.setText(status);
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
     * Brise sve fajlove datog formata od prethodnih skidanja (i nedovrsene
     * .part fajlove), pa pokupi foldere koji su ostali prazni. Drugi format se
     * ne dira.
     */
    private void cleanPreviousDownloads(Format format) throws Exception {
        Path base = baseDownloadDir();
        Files.createDirectories(base);

        String ext = "." + format.ext;
        int deletedFiles = 0;

        try (Stream<Path> walk = Files.walk(base)) {
            List<Path> victims = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(ext) || name.endsWith(ext + ".part");
                    })
                    .toList();

            for (Path p : victims) {
                Files.deleteIfExists(p);
                deletedFiles++;
            }
        }

        int deletedDirs = deleteEmptyDirs(base);

        log("Cleaning " + format + " u " + base.toAbsolutePath()
                + ": obrisano " + deletedFiles + " fajlova, " + deletedDirs + " foldera");
    }

    /**
     * Brise prazne podfoldere (najdublje prvo). Sam root ostaje.
     */
    private static int deleteEmptyDirs(Path root) throws Exception {
        List<Path> dirs;
        try (Stream<Path> walk = Files.walk(root)) {
            dirs = walk
                    .filter(Files::isDirectory)
                    .filter(p -> !p.equals(root))
                    .sorted(Comparator.reverseOrder())
                    .toList();
        }

        int deleted = 0;
        for (Path dir : dirs) {
            try (Stream<Path> children = Files.list(dir)) {
                if (children.findAny().isPresent()) {
                    continue;
                }
            }
            Files.deleteIfExists(dir);
            deleted++;
        }
        return deleted;
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
