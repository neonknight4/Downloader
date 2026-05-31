module its.downloader {
    requires javafx.controls;

    exports its.yt.downloader;
    opens its.yt.downloader to javafx.graphics;
}
