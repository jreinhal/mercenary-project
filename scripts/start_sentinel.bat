@echo off
setlocal
title SENTINEL // LAUNCHER

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "ROOT_DIR=%%~fI"
set "GRADLEW=%ROOT_DIR%\gradlew.bat"

echo ========================================================
echo   SENTINEL INTELLIGENCE PLATFORM (v1.0.0)
echo   Turnkey Edition
echo ========================================================
echo.

:: 1. CHECK FOR JAVA 21
java -version 2>&1 | findstr "version" > nul
if %errorlevel% neq 0 (
    echo [ERROR] Java not found. Please install Java JDK 21.
    pause
    exit /b
)

:: Simple check for version 21 (this is a basic check, can be improved)
java -version 2>&1 | findstr "21" > nul
if %errorlevel% neq 0 (
    echo [WARNING] Java 21 is recommended. You may be running a different version.
    java -version
    echo.
)

:: 2. MODE SELECTION
echo Select Deployment Mode:
echo [1] COMMERCIAL (Standard Mode)
echo     - Ideal for Banking, Legal, Medical
echo     - Permissive Security
echo.
echo [2] GOVERNMENT (Secure Mode)
echo     - DoD/IC Compliant (IL4/IL5)
echo     - Requires CAC/PIV + HTTPS
echo     - Zero Trust Enforced
echo.

set /p mode="Enter Selection [1 or 2]: "

pushd "%ROOT_DIR%"
if "%mode%"=="2" (
    echo.
    echo [STATUS] Initializing SECURE GOVERNMENT MODE...
    echo [INFO]  Enforcing HTTPS, Mutual TLS, and RBAC.
    call "%GRADLEW%" bootRun --args='--spring.profiles.active=govcloud'
) else (
    echo.
    echo [STATUS] Initializing COMMERCIAL MODE...
    echo [INFO]  Standard security enabled.
    call "%GRADLEW%" bootRun --args='--spring.profiles.active=dev'
)
popd

pause
