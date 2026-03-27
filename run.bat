@echo off
setlocal

if not exist out mkdir out

echo Compiling sources...
javac -d out src\Main.java src\server.java src\client.java src\client2.java
if errorlevel 1 (
  echo.
  echo Compile failed. Keep this window open and check the errors above.
  pause
  exit /b 1
)

echo Starting ChatSpace Web on http://localhost:8080
start "ChatSpace Server" cmd /k "cd /d %~dp0 && java -cp out Main"
timeout /t 2 >nul
start "" http://localhost:8080

echo.
echo If the browser does not open, go to http://localhost:8080 manually.
pause
