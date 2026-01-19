@rem Gradle startup script for Windows

@if "%DEBUG%"=="" @echo off

set DIRNAME=%~dp0
set APP_HOME=%DIRNAME%

set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

set JAVA_EXE=java.exe
if defined JAVA_HOME goto findJavaFromJavaHome
goto execute

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe
goto execute

:execute
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% -jar "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" %*

:end
