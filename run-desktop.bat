@echo off
cd /d "%~dp0"
echo Starting Wvusic...
call gradlew.bat :desktop:run
pause
