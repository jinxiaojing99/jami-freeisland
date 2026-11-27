$ErrorActionPreference = "Continue"
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17.0.4"
$env:ANDROID_HOME = "C:\Users\jin_x\AppData\Local\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$nodeDir = "C:\Users\jin_x\.workbuddy\binaries\node\versions\22.22.2-6"
$env:PATH = "$env:JAVA_HOME\bin;$nodeDir;$env:ANDROID_HOME\platform-tools;$env:PATH"
$proj = "D:\WorkbuddyTask\2026-11-01-17-16-35\jami-src\jami-client-android-master\jami-android"
$log = "D:\WorkbuddyTask\2026-11-01-17-16-35\jami-build\output\build_android_a6.txt"
Set-Location $proj
& .\gradlew.bat assembleNoPushDebug -Parchs=arm64-v8a --no-daemon "-PnpmCommand=$nodeDir\npm.cmd" "-PnodeCommand=$nodeDir\node.exe" -x :app:installCollabEditor *> $log
"EXITCODE=$LASTEXITCODE" | Out-File -Append -Encoding utf8 $log
