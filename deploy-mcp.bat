@echo off
rem Deploys only the crewsyncMcp Cloud Function (the Claude connector). Output goes to logs\deploy-mcp-log.txt
cd /d "%~dp0"
if not exist logs mkdir logs
call :main > logs\deploy-mcp-log.txt 2>&1
type logs\deploy-mcp-log.txt
timeout /t 15
exit /b

:main
pushd functions
call npm.cmd install --no-audit --no-fund
popd
call firebase deploy --only functions:crewsyncMcp --non-interactive
echo DEPLOY EXIT CODE %ERRORLEVEL%
exit /b 0
