# Development Guide

## Build and run

Windows (PowerShell):
```
.\gradlew.bat bootRun
```

Linux/macOS:
```
./gradlew bootRun
```

## Run a specific edition
```
./gradlew -Pedition=professional bootRun
```

## Build a distributable jar
```
./gradlew bootJar
```

## Environment variables
See docs/engineering/CONFIGURATION.md for the full list.

## Local dependencies
- MongoDB on localhost:27017
- Ollama on localhost:11434
