@echo off
rem Deploys the deleteProject + crewsyncMcp + dailyReportPdf + recordSiteWeather Cloud Functions and the Firestore rules, then builds Crewsync for Android and installs it if a phone is connected.
rem Output goes to logs\deploy-log.txt and logs\build-log.txt
cd /d "%~dp0"
if not exist logs mkdir logs
echo Installing function dependencies...
pushd functions
call npm.cmd install --no-audit --no-fund > ..\logs\npm-log.txt 2>&1
popd
echo Deploying Cloud Functions (deleteProject, crewsyncMcp, dailyReportPdf, recordSiteWeather) and Firestore rules...
call firebase deploy --only functions:deleteProject,functions:crewsyncMcp,functions:dailyReportPdf,functions:recordSiteWeather,firestore:rules --non-interactive > logs\deploy-log.txt 2>&1
echo DEPLOY EXIT CODE %ERRORLEVEL% >> logs\deploy-log.txt
type logs\deploy-log.txt
echo.
echo Building the Android app (and installing it if a phone is connected)...
call gradlew.bat :composeApp:assembleDebug > logs\build-log.txt 2>&1
echo BUILD EXIT CODE %ERRORLEVEL% >> logs\build-log.txt
call gradlew.bat :composeApp:installDebug >> logs\build-log.txt 2>&1
echo INSTALL EXIT CODE %ERRORLEVEL% >> logs\build-log.txt
echo Done. Logs are in the logs folder.
timeout /t 15
