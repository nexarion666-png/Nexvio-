@echo off
if exist "%~dp0gradle\wrapper\gradle-wrapper.jar" (java -classpath "%~dp0gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*) else (echo Gradle wrapper JAR is missing. Install Gradle 8.9 and run gradle %* & exit /b 1)
