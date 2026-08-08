@echo off
REM ============================================================
REM  Builds the Windows installer ("YouTube Downloader-1.0.exe").
REM  Run this ON WINDOWS. Requirements:
REM    - JDK 21 (jpackage on PATH)
REM    - Maven (mvn on PATH)
REM    - WiX Toolset 3.x (candle.exe / light.exe on PATH)
REM  Output: dist\YouTube Downloader-1.0.exe
REM ============================================================

setlocal

set APP_NAME=YouTube Downloader
set JAR_NAME=Downloader
set APP_VERSION=1.0
set MAIN_MODULE=its.downloader/its.yt.downloader.YtDownloaderFxApp
set TOOLS_DIR=tools

echo [1/4] Maven build...
call mvn clean package || goto :error

echo [2/4] Checking bundled tools...
if not exist "%TOOLS_DIR%\yt-dlp.exe" (
    echo   Downloading yt-dlp.exe...
    curl -L -o "%TOOLS_DIR%\yt-dlp.exe" --create-dirs https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe || goto :error
)
if not exist "%TOOLS_DIR%\ffmpeg.exe" (
    echo   ffmpeg.exe MISSING in %TOOLS_DIR%\
    echo   Download https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip
    echo   and copy ffmpeg.exe + ffprobe.exe into %TOOLS_DIR%\
    goto :error
)

echo [3/4] Copying app jar to module path...
copy /Y "target\%JAR_NAME%-%APP_VERSION%.jar" "target\libs\" >nul || goto :error

echo [4/4] jpackage...
if exist dist rmdir /S /Q dist
jpackage ^
  --type exe ^
  --name "%APP_NAME%" ^
  --app-version %APP_VERSION% ^
  --icon "installer\video-download.ico" ^
  --module-path "target\libs" ^
  --module %MAIN_MODULE% ^
  --input "%TOOLS_DIR%" ^
  --dest dist ^
  --win-dir-chooser ^
  --win-menu ^
  --win-shortcut ^
  --vendor "its" || goto :error

echo.
echo DONE: dist\%APP_NAME%-%APP_VERSION%.exe
goto :eof

:error
echo.
echo BUILD FAILED
exit /b 1
