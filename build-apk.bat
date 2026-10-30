@echo off
REM WeKnora Android APK 一键构建。
REM
REM 工具链装在 D:\workbuddy\toolchain（不进系统 PATH），所以必须在这里临时
REM 设 JAVA_HOME —— 本机 PATH 里那个 java.exe 是 JRE 8，用它跑 Gradle 会直接
REM 报 unsupported class file major version。
setlocal
set JAVA_HOME=D:\workbuddy\toolchain\jdk17
set ANDROID_SDK_ROOT=D:\workbuddy\toolchain\android-sdk
set ANDROID_HOME=D:\workbuddy\toolchain\android-sdk
set GRADLE_USER_HOME=%USERPROFILE%\.gradle

echo JAVA_HOME=%JAVA_HOME%
echo 构建 debug APK ...
call gradlew.bat --no-daemon assembleDebug
if errorlevel 1 goto :fail

echo.
echo 构建 release APK ...
call gradlew.bat --no-daemon assembleRelease
if errorlevel 1 goto :fail

echo.
echo ===== 产物 =====
dir /b app\build\outputs\apk\debug\*.apk 2>nul
dir /b app\build\outputs\apk\release\*.apk 2>nul
goto :eof

:fail
echo.
echo ===== 构建失败，请往上翻查看错误 =====
exit /b 1
