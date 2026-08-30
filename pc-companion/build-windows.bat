@echo off
setlocal
set CGO_ENABLED=0
go build -trimpath -ldflags="-s -w -H windowsgui" -o Corex-PC-Companion-v1.1.1.exe .
if errorlevel 1 exit /b 1
echo Built Corex-PC-Companion-v1.1.1.exe
