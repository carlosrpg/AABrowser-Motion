[CmdletBinding()]
param(
    [string]$KeyAlias = 'aabrowser-motion',
    [string]$DistinguishedName = 'CN=carlosrpg, OU=AA Browser Motion, O=carlosrpg, C=BR',
    [int]$ValidityDays = 10000,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptDir '..')).Path
$keystorePath = Join-Path $repoRoot 'release.keystore'
$localPropertiesPath = Join-Path $repoRoot 'local.properties'

function New-RandomPassword {
    param([int]$Length = 40)

    $alphabet = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'.ToCharArray()
    $characters = for ($index = 0; $index -lt $Length; $index++) {
        $alphabet[[System.Security.Cryptography.RandomNumberGenerator]::GetInt32($alphabet.Length)]
    }
    return -join $characters
}

function Resolve-Keytool {
    if ($env:JAVA_HOME) {
        $javaHomeKeytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
        if (Test-Path $javaHomeKeytool) {
            return $javaHomeKeytool
        }
    }

    $androidStudioKeytool = 'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe'
    if (Test-Path $androidStudioKeytool) {
        return $androidStudioKeytool
    }

    $microsoftJdks = Get-ChildItem 'C:\Program Files\Microsoft' -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending
    foreach ($jdk in $microsoftJdks) {
        $keytool = Join-Path $jdk.FullName 'bin\keytool.exe'
        if (Test-Path $keytool) {
            return $keytool
        }
    }

    $command = Get-Command keytool.exe -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    throw 'keytool.exe was not found. Install Java 21 or Android Studio and try again.'
}

if ((Test-Path $keystorePath) -and -not $Force) {
    throw "A release keystore already exists at $keystorePath. Use -Force only if you intentionally want to replace it."
}

$keytool = Resolve-Keytool
$password = New-RandomPassword

if (Test-Path $keystorePath) {
    Remove-Item -LiteralPath $keystorePath -Force
}

& $keytool `
    -genkeypair `
    -noprompt `
    -keystore $keystorePath `
    -storetype PKCS12 `
    -storepass $password `
    -keypass $password `
    -alias $KeyAlias `
    -keyalg RSA `
    -keysize 4096 `
    -validity $ValidityDays `
    -dname $DistinguishedName

if ($LASTEXITCODE -ne 0 -or -not (Test-Path $keystorePath)) {
    throw 'Failed to generate the release keystore.'
}

$signingKeys = @(
    'RELEASE_STORE_FILE',
    'RELEASE_STORE_PASSWORD',
    'RELEASE_KEY_ALIAS',
    'RELEASE_KEY_PASSWORD'
)

$preservedLines = if (Test-Path $localPropertiesPath) {
    Get-Content $localPropertiesPath | Where-Object {
        $line = $_
        -not ($signingKeys | Where-Object { $line -match "^\s*$([regex]::Escape($_))\s*=" })
    }
} else {
    @()
}

$updatedLines = @(
    $preservedLines
    'RELEASE_STORE_FILE=release.keystore'
    "RELEASE_STORE_PASSWORD=$password"
    "RELEASE_KEY_ALIAS=$KeyAlias"
    "RELEASE_KEY_PASSWORD=$password"
)

Set-Content -Path $localPropertiesPath -Value $updatedLines -Encoding UTF8

Write-Host ''
Write-Host 'Local release signing is configured.' -ForegroundColor Green
Write-Host "Keystore: $keystorePath"
Write-Host "Properties: $localPropertiesPath"
Write-Host ''
Write-Host 'Back up both ignored files securely. Losing this keystore prevents future APKs from updating installed releases signed with it.' -ForegroundColor Yellow
Write-Host ''
Write-Host 'Release certificate:'
& $keytool -list -keystore $keystorePath -storepass $password -alias $KeyAlias
if ($LASTEXITCODE -ne 0) {
    throw 'The keystore was generated, but its certificate could not be read.'
}
