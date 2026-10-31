@echo off
rem Minimal Maven wrapper for Windows: downloads Maven 3.9.9 once into %USERPROFILE%\.m2\wrapper
rem and runs it. Needs only Java 21 on the PATH (or JAVA_HOME set).
setlocal
set MVN_VERSION=3.9.9
set MVN_HOME=%USERPROFILE%\.m2\wrapper\apache-maven-%MVN_VERSION%
if not exist "%MVN_HOME%\bin\mvn.cmd" (
  echo Downloading Maven %MVN_VERSION% ...
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference='Stop';" ^
    "$dir = Join-Path $env:USERPROFILE '.m2\wrapper';" ^
    "New-Item -ItemType Directory -Force -Path $dir | Out-Null;" ^
    "$zip = Join-Path $dir 'maven.zip';" ^
    "Invoke-WebRequest -UseBasicParsing -Uri 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MVN_VERSION%/apache-maven-%MVN_VERSION%-bin.zip' -OutFile $zip;" ^
    "Expand-Archive -Force -Path $zip -DestinationPath $dir;" ^
    "Remove-Item $zip"
  if errorlevel 1 (
    echo Could not download Maven. Check your internet connection or install Maven manually.
    exit /b 1
  )
)
call "%MVN_HOME%\bin\mvn.cmd" %*
