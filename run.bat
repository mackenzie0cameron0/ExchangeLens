@echo off
REM Launches RuneLite with the Exchange Lens plugin.
REM Sets JAVA_HOME to the bundled Temurin 11 JDK for this session, then runs Gradle.
set "JAVA_HOME=C:\Users\BWAmackenzie\.jdks\temurin-11.0.31"
call gradlew.bat run %*
