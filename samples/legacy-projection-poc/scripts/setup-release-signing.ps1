[CmdletBinding()]
param(
    [string]$KeyAlias = 'legacy-projection-poc',
    [string]$DistinguishedName = 'CN=Legacy Projection POC, OU=Development, O=AA Browser Motion, C=BR',
    [int]$ValidityDays = 10000,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = (Resolve-Path (Join-Path $scriptDirectory '..')).Path
$keystorePath = Join-Path $projectRoot 'release.keystore'
$localPropertiesPath = Join-Path $projectRoot 'local.properties'

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

    $command = Get-Command keytool.exe -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    throw 'keytool.exe was not found. Install Java 21 or Android Studio and try again.'
}

if ((Test-Path $keystorePath) -and -not $Force) {
    throw "A release keystore already exists at $keystorePath. Use -Force only to replace it intentionally."
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
Write-Host 'POC release signing is configured.' -ForegroundColor Green
Write-Host "Keystore: $keystorePath"
Write-Host "Properties: $localPropertiesPath"
Write-Host ''
Write-Host 'Back up both ignored files securely. Future updates must use the same keystore.' -ForegroundColor Yellow
