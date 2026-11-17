@echo off
setlocal
set "GRADLE_VERSION=9.5.0"
set "BASE=%USERPROFILE%\.gradle\jacket-bootstrap"
set "DIST=%BASE%\gradle-%GRADLE_VERSION%"
set "GRADLE=%DIST%\bin\gradle.bat"
set "ZIP=%BASE%\gradle-%GRADLE_VERSION%-bin.zip"

if exist "%GRADLE%" goto run
if not exist "%BASE%" mkdir "%BASE%"
if not exist "%ZIP%" powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ZIP%'"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Force '%ZIP%' '%BASE%'"

:run
call "%GRADLE%" %*
endlocal
