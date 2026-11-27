@echo off
REM One-click Jami Windows (MSVC ABI) build.
REM Run this in a NORMAL desktop CMD/PowerShell session, not inside the AI
REM sandbox: this host suspends nested build subprocesses in sandboxed shells.
REM All prerequisites are portable and already prepared under toolchain\.
setlocal
set "ROOT=D:\WorkbuddyTask\2026-11-01-17-16-35"
set "M=%ROOT%\toolchain"

call "%M%\msvc-env.cmd"
if errorlevel 1 goto fail

echo === [0/2] Cleaning stale vcpkg source trees ===
"C:\Users\jin_x\AppData\Local\hermes\hermes-agent\venv\Scripts\python.exe" "%M%\dl\clean_buildtrees.py"

echo === [1/2] Installing Jami official vcpkg manifest (48 deps) ===
powershell -NoProfile -ExecutionPolicy Bypass -File "%M%\install_jami_deps.ps1"
if errorlevel 1 goto fail
findstr /C:"VCPKG_EXIT=0" "%M%\jami_deps_install.log" >nul
if errorlevel 1 goto fail

echo === [2/2] Building daemon jami-core and Qt GUI jami.exe ===
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\build-jami-msvc.ps1"
if errorlevel 1 goto fail

echo ALL_DONE=1
echo Artifacts: %M%\build-gui-msvc\jami.exe and %M%\build-daemon-msvc\jami-core.lib
exit /b 0

:fail
echo BUILD_FAILED - check %M%\jami_deps_install.log and %M%\build-msvc.log
exit /b 1
