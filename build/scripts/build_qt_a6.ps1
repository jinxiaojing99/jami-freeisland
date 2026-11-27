$ErrorActionPreference = "Continue"
. "D:\WorkbuddyTask\2026-11-01-17-16-35\toolchain\msvc-env.ps1"
$cmake = "D:\WorkbuddyTask\2026-11-01-17-16-35\toolchain\cmake3\cmake\data\bin\cmake.exe"
$build = "D:\WorkbuddyTask\2026-11-01-17-16-35\toolchain\build-gui-msvc"
$log = "D:\WorkbuddyTask\2026-11-01-17-16-35\jami-build\output\build_qt_a6.txt"
Set-Location $build
& $cmake --build $build --target Jami.exe *> $log
"EXITCODE=$LASTEXITCODE" | Out-File -Append -Encoding utf8 $log
