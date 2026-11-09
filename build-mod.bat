@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
title Container Bot Build

rem ===========================================================================
rem  Container Bot - pure batch builder (no PowerShell involved).
rem
rem  Why pure batch: the earlier PowerShell version broke twice on script-level
rem  issues ($PSScriptRoot resolving to the wrong directory, and Tee-Object
rem  parameter-set conflicts). Batch's "cd /d" cannot get this wrong.
rem
rem  Why it must run outside the DSH sandbox:
rem    The sandbox confines child processes with a restricted token plus a low
rem    integrity label, granting write access through a logon-session SID
rem    (S-1-4-...). Java's Files.isWritable() therefore returns false for every
rem    path, and jdk.nio.zipfs contains:
rem        this.readOnly = ... || !Files.isWritable(zfpath);
rem    so every jar/zip becomes read-only and Fabric Loom fails while writing
rem    mappings with ReadOnlyFileSystemException.
rem ===========================================================================

set "ROOT=D:\HuaweiMoveData\Users\HUAWEI\Documents\deepseek-harness\default-workspace\containerbot"
set "JAVA_HOME=%ROOT%\tools\jdk-dist\jdk-25.0.4.1+1"
set "GRADLE_USER_HOME=%ROOT%\.gradle-home"
set "GRADLE=%ROOT%\tools\gradle-dist\gradle-9.7.0\bin\gradle.bat"
set "MODS=D:\.minecraft\versions\1.21.8\mods"

echo ==========================================================
echo  Container Bot - build
echo ==========================================================
echo   project          : %ROOT%
echo   JAVA_HOME        : %JAVA_HOME%
echo   GRADLE_USER_HOME : %GRADLE_USER_HOME%
echo.

cd /d "%ROOT%"
if errorlevel 1 (
    echo ERROR: cannot change directory to %ROOT%
    goto :end_fail
)
echo   current dir      : %CD%
echo.

if not exist "%ROOT%\settings.gradle" (
    echo ERROR: settings.gradle not found in %ROOT%
    goto :end_fail
)
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo ERROR: JDK 25 not found at %JAVA_HOME%
    goto :end_fail
)
if not exist "%GRADLE%" (
    echo ERROR: Gradle not found at %GRADLE%
    goto :end_fail
)

call "%GRADLE%" --stop >nul 2>&1

set /a ATTEMPT=0

:loop
set /a ATTEMPT+=1
if %ATTEMPT% GTR 8 goto :failed

echo.
echo ############ attempt %ATTEMPT% / 8 ############
call "%GRADLE%" -p "%ROOT%" build --console=plain

if errorlevel 1 (
    echo --- attempt %ATTEMPT% failed, retrying in 5s ^(cached deps are kept^) ---
    timeout /t 5 /nobreak >nul 2>&1
    goto :loop
)

if not exist "%ROOT%\build\libs" (
    echo --- attempt %ATTEMPT% returned 0 but build\libs is missing, retrying ---
    timeout /t 5 /nobreak >nul 2>&1
    goto :loop
)

echo.
echo ==========================================================
echo  BUILD SUCCESSFUL
echo ==========================================================
echo.

set "JAR="
for %%F in ("%ROOT%\build\libs\containerbot-*.jar") do (
    echo %%~nxF | findstr /i /c:"sources" >nul
    if errorlevel 1 set "JAR=%%F"
)

if not defined JAR (
    echo Build succeeded but no containerbot-*.jar found. Contents of build\libs:
    dir /b "%ROOT%\build\libs"
    goto :end_fail
)

echo   artifact: !JAR!
echo.

if exist "%MODS%" (
    rem 必须先删掉旧版本：mods 目录里同时存在两个同 id 的 jar 会让 Fabric 拒绝启动
    del /q "%MODS%\containerbot-*.jar" >nul 2>&1
    copy /y "!JAR!" "%MODS%" >nul
    if errorlevel 1 (
        echo   WARNING: copy failed, please copy manually to:
        echo     %MODS%
    ) else (
        echo   installed to ^(previous versions removed^):
        echo     %MODS%
    )
) else (
    echo   mods dir not found, please copy manually to:
    echo     %MODS%
)

echo.
echo   archived release:
echo     %ROOT%\releases\

echo.
echo Next: launch the 1.21.8 world, then use these commands ^(needs OP / cheats^):
echo   /cbot spawn Bot                              spawn the fake player
echo   /cbot start Bot minecraft:diamond 64         collect 64 diamonds
echo   /cbot start Bot minecraft:iron_ingot 128 48  collect with radius 48
echo   /cbot status                                 show progress
echo   /cbot stop Bot                               stop the task
echo.
goto :end_ok

:failed
echo.
echo ==========================================================
echo  BUILD FAILED after %ATTEMPT% attempts
echo ==========================================================
echo.
echo Please copy the lines above - especially the text that appears
echo right after "* What went wrong:" - back to the assistant.
echo.

:end_fail
echo.
pause
exit /b 1

:end_ok
echo.
pause
exit /b 0
