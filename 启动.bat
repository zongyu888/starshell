@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

echo ===================================
echo    AI FinalShell
echo ===================================
echo.

echo [1/3] Check Java...
java -version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Java not found, please install Java 17+
    pause
    exit /b 1
)
echo Java OK

echo.
echo [2/3] Compiling...
cd /d "%~dp0java-client"
call mvn clean compile -q
if errorlevel 1 (
    echo Compile failed
    pause
    exit /b 1
)
echo Compile OK

echo.
echo [3/3] Launching...
call mvn javafx:run

echo.
echo ===================================
pause
