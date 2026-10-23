@echo off >nul 2>nul
@echo off
cls
chcp 65001 >nul
if /i "%~1"=="--open-when-ready" goto :open_when_ready

title PCL Java21 Launcher
setlocal enabledelayedexpansion
cd /d "%~dp0"

rem ===== 目录约定：本脚本位于项目根，程序在 .\cpcl\ 下 =====
set "APP_HOME=%~dp0"
if "%APP_HOME:~-1%"=="\" set "APP_HOME=%APP_HOME:~0,-1%"
set "APP_DIR=%APP_HOME%\cpcl"
set "APP_JAR=%APP_DIR%\pcl-java21.jar"

if not exist "%APP_JAR%" (
    echo [错误] 找不到程序: %APP_JAR%
    echo        请先在 IDEA 中执行 Build Artifacts -^> pcl-java21:jar
    pause
    exit /b 1
)

rem ===== 首次运行时从 cpcl 模板补一份配置 =====
if not exist "%APP_HOME%\data\settings.json" (
    if exist "%APP_DIR%\data\settings.json" (
        if not exist "%APP_HOME%\data" mkdir "%APP_HOME%\data"
        copy /y "%APP_DIR%\data\settings.json" "%APP_HOME%\data\settings.json" >nul
    )
)

rem ===== 1. 先看 18080 是否已有本程序在跑 =====
set "PORT="
call :probe 18080
if "!RUNNING!"=="1" (
    echo.
    echo [提示] 程序已经在运行（端口 18080），不再重复启动。
    echo        正在打开 http://localhost:18080/
    start "" "http://localhost:18080/"
    ping -n 3 127.0.0.1 >nul
    exit /b 0
)

if "!BUSY!"=="1" (
    rem 18080 被别的程序占了，退到 18081
    call :probe 18081
    if "!RUNNING!"=="1" (
        echo.
        echo [提示] 程序已经在运行（端口 18081），不再重复启动。
        echo        正在打开 http://localhost:18081/
        start "" "http://localhost:18081/"
        ping -n 3 127.0.0.1 >nul
        exit /b 0
    )
    if "!BUSY!"=="1" (
        echo.
        echo [错误] 端口 18080 和 18081 都被其他程序占用，无法启动。
        echo        请关闭占用端口的程序后重试。
        echo.
        pause
        exit /b 1
    )
    set "PORT=18081"
    echo [提示] 端口 18080 被占用，改用 18081 启动。
)
if not defined PORT set "PORT=18080"
set "URL=http://localhost:!PORT!/"

rem ===== 2. 选择 Java 21（逐个候选验证版本）=====
set "JAVA_EXE="
call :pick_java
if defined JAVA_EXE goto :java_ok

echo.
echo [提示] 本机没有找到可用的 Java 21，需要先安装才能启动。
echo        即将打开随包附带的安装程序：jdk\jdk-21_windows-x64_bin.exe
echo        请按提示完成安装（默认位置即可），装完后重新双击 启动器.bat
echo.
choice /c YN /n /m "现在打开安装程序吗？[Y/N] "
if errorlevel 2 (
    echo 已取消。安装 Java 21 后才能使用启动器。
    pause
    exit /b 1
)
if not exist "%APP_HOME%\jdk\jdk-21_windows-x64_bin.exe" (
    echo [错误] 包内缺少 jdk\jdk-21_windows-x64_bin.exe
    echo        请到 https://www.oracle.com/java/technologies/downloads/ 下载安装 JDK 21
    pause
    exit /b 1
)
start "" "%APP_HOME%\jdk\jdk-21_windows-x64_bin.exe"
echo.
echo 安装完成后请重新双击 启动器.bat。
pause
exit /b 1

:java_ok
echo ============================================================
echo   Java : !JAVA_EXE!
echo   程序 : %APP_JAR%
echo   数据 : %APP_HOME%
echo   地址 : !URL!
echo ============================================================
echo.
echo 关闭本窗口即退出程序。
echo.

rem ===== 3. 后台守候：等端口真正起来再开浏览器 =====
start "" /b "%COMSPEC%" /c ""%~f0" --open-when-ready !PORT!"

"!JAVA_EXE!" -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dpcl.data-dir="%APP_HOME%" -Dserver.port=!PORT! -jar "%APP_JAR%"

echo.
echo 程序已退出（日志见 %APP_HOME%\logs）。
pause
exit /b 0


rem ================= 子过程 =================
rem :pick_java 依次尝试常见位置，找到 Java 21+ 时设置 JAVA_EXE
:pick_java
set "JAVA_EXE="
if exist "D:\java_21\bin\java.exe" call :test_java "D:\java_21\bin\java.exe"
if defined JAVA_EXE goto :eof
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" call :test_java "%JAVA_HOME%\bin\java.exe"
if defined JAVA_EXE goto :eof
call :scan_dir "%ProgramFiles%\Java\jdk-21*"
if defined JAVA_EXE goto :eof
call :scan_dir "%ProgramFiles%\Eclipse Adoptium\jdk-21*"
if defined JAVA_EXE goto :eof
call :scan_dir "%ProgramFiles%\Microsoft\jdk-21*"
if defined JAVA_EXE goto :eof
for /f "delims=" %%i in ('where java 2^>nul') do (
    if not defined JAVA_EXE call :test_java "%%i"
)
goto :eof

rem :scan_dir 通配符目录扫描
:scan_dir
for /d %%d in ("%~1") do (
    if not defined JAVA_EXE if exist "%%d\bin\java.exe" call :test_java "%%d\bin\java.exe"
)
goto :eof

rem :test_java 校验 java 主版本为 21 或更高，通过则设置 JAVA_EXE
:test_java
set "JV_FILE=%TEMP%\pcl_java_version.txt"
"%~1" -version > "%JV_FILE%" 2>&1
findstr /c:"version" "%JV_FILE%" 2>nul | findstr /r "2[1-9]\." >nul 2>&1
if errorlevel 1 goto :test_java_done
set "JAVA_EXE=%~1"
:test_java_done
if exist "%JV_FILE%" del "%JV_FILE%" >nul 2>&1
goto :eof

rem :probe 端口：RUNNING=1 表示本程序在跑，BUSY=1 表示端口被别人占
:probe
set "RUNNING=0"
set "BUSY=0"
set "PROBE_FILE=%TEMP%\pcl_probe_%~1.txt"
if exist "%PROBE_FILE%" del "%PROBE_FILE%" >nul 2>&1
netstat -ano | findstr /c:":%~1 " | findstr /i "LISTENING" >nul 2>&1
if errorlevel 1 goto :probe_end

set "BUSY=1"
rem 端口有人监听，再看是不是本程序的接口（本程序 /api/instances 返回 200）
where curl.exe >nul 2>&1
if errorlevel 1 goto :probe_ps
curl.exe -s -f --max-time 3 -o "%PROBE_FILE%.body" "http://127.0.0.1:%~1/api/instances" >nul 2>&1
if not errorlevel 1 set "RUNNING=1"
goto :probe_clean

:probe_ps
powershell -NoProfile -Command "try { $r = Invoke-WebRequest -Uri 'http://127.0.0.1:%~1/api/instances' -UseBasicParsing -TimeoutSec 3; Write-Output $r.StatusCode } catch { Write-Output 0 }" > "%PROBE_FILE%" 2>nul
findstr /c:"200" "%PROBE_FILE%" >nul 2>&1
if not errorlevel 1 set "RUNNING=1"

:probe_clean
if exist "%PROBE_FILE%" del "%PROBE_FILE%" >nul 2>&1
if exist "%PROBE_FILE%.body" del "%PROBE_FILE%.body" >nul 2>&1

:probe_end
goto :eof

rem 等待端口就绪（最多 180 秒）后打开浏览器
:open_when_ready
setlocal
set "P=%~2"
for /l %%i in (1,1,180) do (
    netstat -ano | findstr /c:":%P% " | findstr /i "LISTENING" >nul 2>&1
    if not errorlevel 1 (
        start "" "http://localhost:%P%/"
        exit /b 0
    )
    ping -n 2 127.0.0.1 >nul
)
exit /b 0
