module its.downloader {
    requires javafx.controls;
    requires java.desktop;

    exports its.yt.downloader;
    opens its.yt.downloader to javafx.graphics;
}
