@echo off
chcp 936 >nul
setlocal

REM ============================================================
REM  一键编译 APK
REM  双击本文件即可。生成的 APK 会复制到 dist 目录。
REM ============================================================

set "JAVA_HOME=D:\AndroidDev\jdk"
set "ANDROID_HOME=D:\AndroidDev\sdk"
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
set "GRADLE_HOME=D:\AndroidDev\gradle-8.7"
set "PATH=%JAVA_HOME%\bin;%GRADLE_HOME%\bin;%PATH%"

cd /d "%~dp0"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [错误] 找不到 JDK: %JAVA_HOME%
    echo 请先完成工具链安装。
    pause
    exit /b 1
)

echo.
echo === 开始编译 ===
echo.

call "%GRADLE_HOME%\bin\gradle.bat" assembleRelease --no-daemon --console=plain
if errorlevel 1 (
    echo.
    echo [失败] 编译出错，请把上面的报错发给我。
    pause
    exit /b 1
)

if not exist "dist" mkdir "dist"
set "VER=unknown"
for /f "tokens=3" %%v in ('findstr /r /c:"versionName" app\build.gradle.kts') do set "RAW=%%v"
if defined RAW set "VER=%RAW:"=%"
copy /y "app\build\outputs\apk\release\app-release.apk" "dist\todo-v%VER%.apk" >nul

echo.
echo === 完成 ===
echo APK 位置: %CD%\dist\todo-v%VER%.apk
echo 把这个文件拷到手机，点击安装即可。
echo.
pause
