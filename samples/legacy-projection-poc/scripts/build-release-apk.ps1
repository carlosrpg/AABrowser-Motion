[CmdletBinding()]
param(
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = (Resolve-Path (Join-Path $scriptDirectory '..')).Path
$gradleWrapper = Join-Path $projectRoot 'gradlew.bat'
$keystorePath = Join-Path $projectRoot 'release.keystore'
$localPropertiesPath = Join-Path $projectRoot 'local.properties'
$sourceApk = Join-Path $projectRoot 'app\build\outputs\apk\release\app-release.apk'
$distributionDirectory = Join-Path $projectRoot 'dist'
$distributionApk = Join-Path $distributionDirectory 'LegacyProjectionPoc-1.0-release.apk'

function Invoke-Gradle {
    param([string[]]$Tasks)

    & $gradleWrapper -p $projectRoot @Tasks --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle failed with exit code $LASTEXITCODE."
    }
}

function Resolve-ApkSigner {
    param([string]$AndroidSdk)

    $buildToolsDirectory = Join-Path $AndroidSdk 'build-tools'
    $apkSigner = Get-ChildItem $buildToolsDirectory -Filter 'apksigner.bat' -Recurse -File |
        Sort-Object FullName -Descending |
        Select-Object -First 1
    if (-not $apkSigner) {
        throw "apksigner.bat was not found under $buildToolsDirectory."
    }
    return $apkSigner.FullName
}

if (-not (Test-Path $gradleWrapper)) {
    throw "Gradle wrapper not found at $gradleWrapper."
}
if (-not (Test-Path $keystorePath) -or -not (Test-Path $localPropertiesPath)) {
    throw 'Release signing is not configured. Run .\scripts\setup-release-signing.ps1 first.'
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

if (-not $env:JAVA_HOME) {
    $androidStudioJdk = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Path (Join-Path $androidStudioJdk 'bin\java.exe')) {
        $env:JAVA_HOME = $androidStudioJdk
        $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
    }
}

Push-Location $projectRoot
try {
    if ($Clean) {
        Write-Host 'Cleaning previous POC build outputs...'
        Invoke-Gradle -Tasks @('app:clean')
    }

    Write-Host 'Building signed release APK...'
    Invoke-Gradle -Tasks @('app:assembleRelease')

    if (-not (Test-Path $sourceApk)) {
        throw "Build completed, but no release APK was found at $sourceApk."
    }

    New-Item -ItemType Directory -Force -Path $distributionDirectory | Out-Null
    Copy-Item -LiteralPath $sourceApk -Destination $distributionApk -Force

    $apkSigner = Resolve-ApkSigner -AndroidSdk $androidSdk
    & $apkSigner verify --verbose --print-certs $distributionApk
    if ($LASTEXITCODE -ne 0) {
        throw 'The release APK was built but failed signature verification.'
    }

    Write-Host ''
    Write-Host 'Signed release APK ready to share:' -ForegroundColor Green
    Write-Host $distributionApk -ForegroundColor Cyan
    Write-Host ''
    Write-Host "Install with: adb install -r `"$distributionApk`""
    Write-Host 'If a differently signed build is installed, uninstall it once before installing this release.' -ForegroundColor Yellow
} finally {
    Pop-Location
}
