# Build Jami daemon (MSVC ABI) and the Qt GUI with the portable MSVC kit.
# Delegates to the watchdog-driven builder: this host randomly suspends
# processes inside nested build trees, so every configure/build step is
# retried incrementally instead of hanging forever.
# The original single-pass implementation is kept as .ps1.original.
$m = 'D:\WorkbuddyTask\2026-11-01-17-16-35\toolchain'
. "$m\msvc-env.ps1"
$py = 'C:\Users\jin_x\AppData\Local\hermes\hermes-agent\venv\Scripts\python.exe'
& $py "$m\dl\run_build.py"
$result = "$m\build-msvc-result.txt"
if (-not (Test-Path $result)) { exit 1 }
$text = Get-Content $result -Raw
if ($text -notmatch 'ALL_DONE=True') { exit 1 }
if ($text -notmatch 'JAMI_EXE=True') { exit 1 }
if ($text -notmatch 'JAMI_CORE_LIB=True') { exit 1 }
exit 0
