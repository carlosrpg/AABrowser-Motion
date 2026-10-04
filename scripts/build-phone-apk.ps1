[CmdletBinding()]
param(
    [switch]$Clean,
    [switch]$RunTests
)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptDir '..')).Path
$gradleWrapper = Join-Path $repoRoot 'gradlew.bat'
$variant = 'release'
$assembleTask = 'app:assembleRelease'
$apkDirectory = Join-Path $repoRoot "app\build\renamedApks\$variant"
$distDirectory = Join-Path $repoRoot 'dist'

function Resolve-Java21Home {
    $candidates = @()
    if ($env:JAVA_HOME) {
        $candidates += $env:JAVA_HOME
    }
    $candidates += @(
        'C:\Program Files\Android\Android Studio\jbr',
        'C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot',
        'C:\Program Files\Android\openjdk'
    )
    $candidates += Get-ChildItem 'C:\Program Files\Microsoft' -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty FullName
    $candidates += Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty FullName

    foreach ($candidate in $candidates | Select-Object -Unique) {
        $java = Join-Path $candidate 'bin\java.exe'
        if (-not (Test-Path $java)) {
            continue
        }
        $versionOutput = (& $java -version 2>&1 | Select-Object -First 1) -join ''
        if ($versionOutput -match 'version "(?:1\.)?(?<major>\d+)' -and [int]$Matches.major -ge 21) {
            return $candidate
        }
    }

    throw 'Java 21 was not found. Install JDK 21 or Android Studio before building.'
}

function Resolve-ApkSigner {
    param([string]$AndroidSdk)

    $buildTools = Get-ChildItem (Join-Path $AndroidSdk 'build-tools') -Directory -ErrorAction SilentlyContinue |
        Sort-Object { [version]$_.Name } -Descending
    foreach ($buildTool in $buildTools) {
        $apkSigner = Join-Path $buildTool.FullName 'apksigner.bat'
        if (Test-Path $apkSigner) {
            return $apkSigner
        }
    }

    throw "apksigner.bat was not found under $AndroidSdk\build-tools."
}

function Resolve-Aapt {
    param([string]$AndroidSdk)

    $buildTools = Get-ChildItem (Join-Path $AndroidSdk 'build-tools') -Directory -ErrorAction SilentlyContinue |
        Sort-Object { [version]$_.Name } -Descending
    foreach ($buildTool in $buildTools) {
        $aapt = Join-Path $buildTool.FullName 'aapt.exe'
        if (Test-Path $aapt) {
            return $aapt
        }
    }

    throw "aapt.exe was not found under $AndroidSdk\build-tools."
}

function Resolve-ApkAnalyzer {
    param([string]$AndroidSdk)

    $apkAnalyzer = Get-ChildItem (Join-Path $AndroidSdk 'cmdline-tools') -Recurse `
        -Filter 'apkanalyzer.bat' -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if ($apkAnalyzer) {
        return $apkAnalyzer
    }

    throw "apkanalyzer.bat was not found under $AndroidSdk\cmdline-tools."
}

function Invoke-Gradle {
    param([string[]]$Tasks)

    & $gradleWrapper @Tasks --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle failed with exit code $LASTEXITCODE."
    }
}

if (-not (Test-Path $gradleWrapper)) {
    throw "Gradle wrapper not found at $gradleWrapper."
}

if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    $defaultSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path $defaultSdk) {
        $env:ANDROID_HOME = $defaultSdk
        $env:ANDROID_SDK_ROOT = $defaultSdk
    }
}

$androidSdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
if (-not $androidSdk -or -not (Test-Path $androidSdk)) {
    throw 'Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT before running this script.'
}

if (-not (Get-ChildItem (Join-Path $androidSdk 'platforms') -Directory -Filter 'android-37*' -ErrorAction SilentlyContinue |
    Where-Object { Test-Path (Join-Path $_.FullName 'android.jar') } |
    Select-Object -First 1)) {
    throw "Android SDK platform 37 was not found under $androidSdk\platforms."
}

$env:JAVA_HOME = Resolve-Java21Home
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$apkSigner = Resolve-ApkSigner -AndroidSdk $androidSdk
$aapt = Resolve-Aapt -AndroidSdk $androidSdk
$apkAnalyzer = Resolve-ApkAnalyzer -AndroidSdk $androidSdk

Push-Location $repoRoot
try {
    Write-Host "Android SDK: $androidSdk"
    Write-Host "Java 21: $env:JAVA_HOME"

    if ($Clean) {
        Write-Host 'Cleaning previous build outputs...'
        Invoke-Gradle -Tasks @('app:clean')
    }

    if ($RunTests) {
        Write-Host 'Running debug unit tests...'
        Invoke-Gradle -Tasks @('app:testDebugUnitTest')
    }

    if (-not (Test-Path (Join-Path $repoRoot 'release.keystore')) -or -not (Test-Path (Join-Path $repoRoot 'local.properties'))) {
        throw 'Release signing is not configured. Run .\scripts\setup-release-signing.ps1 first.'
    }

    Write-Host "Building installable $variant APK..."
    Invoke-Gradle -Tasks @($assembleTask)

    $apk = Get-ChildItem -Path $apkDirectory -Filter '*.apk' -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if (-not $apk) {
        throw "Build completed, but no APK was found in $apkDirectory."
    }

    & $apkSigner verify --verbose $apk.FullName
    if ($LASTEXITCODE -ne 0) {
        throw "APK signature verification failed for $($apk.FullName)."
    }

    $manifestDump = (& $aapt dump xmltree $apk.FullName AndroidManifest.xml 2>&1) -join "`n"
    $requiredManifestEntries = @(
        'com.google.android.gms.car.application',
        'com.kododake.aabrowser.car.SplitScreenProjectionService',
        'com.kododake.aabrowser.car.FullscreenCarService',
        'com.google.android.gms.car.category.CATEGORY_PROJECTION',
        'com.google.android.gms.car.category.CATEGORY_PROJECTION_OEM',
        'com.kododake.aabrowser.MainActivity',
        'android.intent.category.LAUNCHER'
    )
    foreach ($entry in $requiredManifestEntries) {
        if ($manifestDump -notmatch [regex]::Escape($entry)) {
            throw "Packaged manifest is missing required entry: $entry"
        }
    }

    $automotiveDescriptorPath = (
        & $apkAnalyzer resources value --config default --type xml `
            --name automotive_app_desc $apk.FullName 2>&1
    ) -join ''
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($automotiveDescriptorPath)) {
        throw 'Packaged automotive_app_desc.xml could not be resolved.'
    }
    $automotiveDescriptorDump = (
        & $apkAnalyzer resources xml --file $automotiveDescriptorPath.Trim() $apk.FullName 2>&1
    ) -join "`n"
    foreach ($capability in @('service', 'projection')) {
        if ($automotiveDescriptorDump -notmatch $capability) {
            throw "Packaged automotive_app_desc.xml does not declare the $capability capability."
        }
    }

    New-Item -ItemType Directory -Force $distDirectory | Out-Null
    $distApk = Join-Path $distDirectory $apk.Name
    Copy-Item $apk.FullName $distApk -Force

    Write-Host ''
    Write-Host "Signed $variant APK ready for Android 15/API 35 or newer:" -ForegroundColor Green
    Write-Host $distApk -ForegroundColor Cyan
    Write-Host ''
    Write-Host "Install with: adb install -r `"$distApk`""
    Write-Host ''
    Write-Host 'If a differently signed build is currently installed, uninstall it once before installing this release:' -ForegroundColor Yellow
    Write-Host 'adb uninstall com.kododake.aabrowser'
    Write-Host "adb install `"$distApk`""
} finally {
    Pop-Location
}
