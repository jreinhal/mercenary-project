@echo off
setlocal EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "ROOT_DIR=%%~fI"
set "GRADLEW=%ROOT_DIR%\gradlew.bat"
set "LOG_DIR=%ROOT_DIR%\reports\logs"

if "%1"=="start" goto start
if "%1"=="stop" goto stop
if "%1"=="restart" goto restart
if "%1"=="status" goto status
if "%1"=="jar" goto jar
if "%1"=="build" goto build
goto usage

:start
echo Starting Sentinel in dev mode...
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"
start "" /B cmd /c ""%GRADLEW%" bootRun --args="--spring.profiles.active=dev" > "%LOG_DIR%\\app.log" 2>&1"
echo Started. Logs in %LOG_DIR%\app.log
goto end

:jar
echo Finding and running the latest non-plain jar...
set "LATEST_JAR="
for /f "delims=" %%f in ('dir /b /o-d "%ROOT_DIR%\build\libs\*.jar" 2^>nul ^| findstr /v "-plain.jar$"') do (
    if not defined LATEST_JAR set "LATEST_JAR=%%f"
)
if not defined LATEST_JAR (
    echo No jar found! Run 'dev-control build' first.
    goto end
)
echo Running: %ROOT_DIR%\build\libs\!LATEST_JAR!
java -jar "%ROOT_DIR%\build\libs\!LATEST_JAR!"
goto end

:build
echo Building Sentinel...
call "%GRADLEW%" build -x test --no-daemon
echo Build complete. Run 'dev-control jar' to start.
goto end

:stop
echo Stopping Sentinel...
for /f "tokens=1" %%i in ('jps -l 2^>nul ^| findstr "mercenary"') do (
    echo Killing PID %%i
    taskkill /PID %%i /F 2>nul
)
for /f "tokens=1" %%i in ('jps 2^>nul ^| findstr "GradleDaemon"') do (
    echo Killing Gradle Daemon PID %%i
    taskkill /PID %%i /F 2>nul
)
echo Stopped.
goto end

:restart
call :stop
timeout /t 3 /nobreak >nul
call :start
goto end

:status
echo Checking for running Sentinel processes...
jps -l 2>nul | findstr "mercenary"
if errorlevel 1 echo No Sentinel processes found.
goto end

:usage
echo Usage: dev-control.bat [start^|stop^|restart^|status^|jar^|build]
echo   start   - Start with bootRun (dev mode)
echo   stop    - Stop running processes
echo   restart - Stop then start
echo   status  - Show running processes
echo   build   - Build the jar (skip tests)
echo   jar     - Run the latest built jar directly
goto end

:end
endlocal
