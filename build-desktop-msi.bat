@echo off
rem Builds the Crewsync Windows installer (MSI) and logs to logs\desktop-msi.log.
cd /d "%~dp0"
if not exist logs mkdir logs
if not defined JAVA_HOME (
  if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
)
echo JAVA_HOME=%JAVA_HOME% > logs\desktop-msi.log
echo Building the Crewsync installer... this takes a few minutes.
call gradlew.bat :composeApp:packageMsi --console=plain >> logs\desktop-msi.log 2>&1
echo EXIT CODE %ERRORLEVEL% >> logs\desktop-msi.log
echo Done. Exit code %ERRORLEVEL%. This window closes in 10 seconds.
timeout /t 10 >nul
