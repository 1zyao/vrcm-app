# Cross-compiles the VRCX-0 Rust core into src/androidMain/jniLibs.
#
# This exists because the APK silently keeps a stale libvrcx_0_mobile.so otherwise,
# and every newly registered command then answers
# "not available on this transport" - which reads like a bug but is just an old
# binary. Doing this in Gradle was attempted and abandoned: the same cargo command
# that works from a shell exits 101 in Gradle's exec environment, and tracking that
# down costs more than this script.
#
# Usage:  powershell -File scripts/build-core.ps1 [-Abi x86_64|arm64-v8a|all]
param(
    [string]$Abi = 'all',
    [string]$CoreRepo = "$PSScriptRoot/../../../vrcx0/mobile",
    [string]$Ndk = $env:ANDROID_NDK_HOME,
    [string]$Msvc = 'D:/Microsoft Visual Studio/18/Community/VC/Tools/MSVC/14.51.36231/bin/Hostx64/x64',
    # NDK wrapper needs the API level in its name: ndk bin has both
    # <target>-clang (generic) and <target><api>-clang; only the latter links.
    [string]$ApiLevel = '24'
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path $CoreRepo)) { throw "VRCX-0 core repo not found: $CoreRepo" }
if (-not $Ndk) { throw 'Set ANDROID_NDK_HOME to the NDK root' }

$toolchain = Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin'
# A list of pairs, not a hashtable: filtering a hashtable yields an array, and
# `$_.Keys` on an array is empty - which made `-Abi x86_64` silently build nothing
# and still exit 0.
$all = @(
    @{ Abi = 'x86_64';    Target = 'x86_64-linux-android' },
    @{ Abi = 'arm64-v8a'; Target = 'aarch64-linux-android' }
)
$targets = if ($Abi -eq 'all') { $all } else { $all | Where-Object { $_.Abi -eq $Abi } }
if (-not $targets) { throw "unknown ABI: $Abi (expected x86_64, arm64-v8a or all)" }

$env:PATH = "$Msvc;$env:PATH"
$env:CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_LINKER = 'link.exe'

foreach ($entry in $targets) {
    $abi = $entry.Abi
    $target = $entry.Target
    $key = $target.Replace('-', '_')
    Set-Item -Path "env:CC_$key" -Value (Join-Path $toolchain "$target$ApiLevel-clang.cmd")
    Set-Item -Path "env:AR_$key" -Value (Join-Path $toolchain 'llvm-ar.exe')
    Set-Item -Path "env:CARGO_TARGET_$($key.ToUpper())_LINKER" -Value (Join-Path $toolchain "$target$ApiLevel-clang.cmd")

    Write-Host "==> cargo build --target $target"
    # Invoked through cmd so cargo's own output is captured verbatim; a direct
    # & call in PowerShell 5.1 can lose stderr and report a bare exit code.
    $log = Join-Path $env:TEMP "vrcx-core-$abi.log"
    Push-Location $CoreRepo
    try {
        cmd /c "cargo build --target $target -p vrcx-0-mobile-android > `"$log`" 2>&1"
        $code = $LASTEXITCODE
    } finally { Pop-Location }
    if ($code -ne 0) {
        Get-Content $log | Select-String -Pattern '^error|error\[' | Select-Object -First 10 | ForEach-Object { Write-Host "    $($_.Line)" }
        throw "cargo failed for $target (exit $code), log: $log"
    }

    $source = Join-Path $CoreRepo "target/$target/debug/libvrcx_0_mobile.so"
    $dest = Join-Path $PSScriptRoot "../composeApp/src/androidMain/jniLibs/$abi"
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Copy-Item $source $dest -Force
    $size = [math]::Round((Get-Item (Join-Path $dest 'libvrcx_0_mobile.so')).Length / 1MB, 1)
    Write-Host "    -> $abi/libvrcx_0_mobile.so ($size MB)"
}



