# Freeisland V0.0.2 Android packaging (with build-number auto-increment)
# Pure ASCII: Windows PowerShell 5.1 reads .ps1 in system codepage (GBK).
$ErrorActionPreference = "Continue"
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17.0.4"
$env:ANDROID_HOME = "C:\Users\jin_x\AppData\Local\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$nodeDir = "C:\Users\jin_x\.workbuddy\binaries\node\versions\22.22.2-6"
$env:PATH = "$env:JAVA_HOME\bin;$nodeDir;$env:ANDROID_HOME\platform-tools;$env:PATH"
$proj = "D:\WorkbuddyTask\2026-11-01-17-16-35\jami-src\jami-client-android-master\jami-android"
$outdir = "D:\WorkbuddyTask\2026-11-01-17-16-35\jami-build\output\android"
$log = "D:\WorkbuddyTask\2026-11-01-17-16-35\jami-build\output\build_android_a6.txt"
$verFile = "$proj\app\src\main\res\values\strings.xml"
Set-Location $proj

# STEP0: bump Freeisland build number in freeisland_version (V0.0.2.<n>).
# Sequence lives in strings.xml itself; bump BEFORE compile so each successful
# build advances the number shown in About. Backup goes to output dir (never
# res/values -- a stray non-.xml there broke mergeNoPushDebugResources).
"=== STEP0 bump Freeisland build number (V0.0.2.<n>) ===" | Out-File -Encoding UTF8 $log
if (Test-Path $verFile) {
    $txt = Get-Content -LiteralPath $verFile -Raw -Encoding UTF8
    $m = [regex]::Match($txt, 'Freeisland V0\.0\.2\.(\d+)')
    if ($m.Success) {
        $n = [int]$m.Groups[1].Value + 1
        $old = $m.Value
        $new = "Freeisland V0.0.2.$n"
        Copy-Item -LiteralPath $verFile -Destination "$outdir\strings.xml.bak_build$n" -Force
        $txt2 = $txt.Replace($old, $new)
        [IO.File]::WriteAllText($verFile, $txt2, (New-Object Text.UTF8Encoding($false)))
        "VERSION_BUMPED=$new" | Out-File -Append -Encoding UTF8 $log
    } else {
        "VERSION_PATTERN_NOT_FOUND (expect Freeisland V0.0.2.<n>)" | Out-File -Append -Encoding UTF8 $log
    }
} else { "STRINGS_XML_MISSING" | Out-File -Append -Encoding UTF8 $log }

# STEP1: compile
& .\gradlew.bat assembleNoPushDebug -Parchs=arm64-v8a --no-daemon "-PnpmCommand=$nodeDir\npm.cmd" "-PnodeCommand=$nodeDir\node.exe" -x :app:installCollabEditor *> $log
"EXITCODE=$LASTEXITCODE" | Out-File -Append -Encoding utf8 $log
