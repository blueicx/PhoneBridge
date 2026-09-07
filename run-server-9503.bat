@echo off
cd /d "%~dp0server"
set PHONEBRIDGE_PORT=9503
:loop
"C:\Program Files\nodejs\node.exe" index.js >> server_9503.out.log 2>&1
ping -n 2 127.0.0.1 >nul
goto loop
