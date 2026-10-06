# gradle-env.ps1 - shared helper for the build scripts.
#
# Why this exists: Gradle reads `systemProp.*.proxyHost` from
# `%USERPROFILE%\.gradle\gradle.properties` for EVERY build in every project on
# the machine. Developers who once set a local proxy (Clash / V2Ray on
# 127.0.0.1:7897) keep that file forever, and once the proxy is gone every
# Gradle invocation fails with "Connection refused" until the file is edited by
# hand. Rather than telling the user to delete their settings, this helper
# neutralises a *loopback* proxy for the duration of one build and restores the
# original bytes afterwards - even if the build fails.
#
# Escape hatch: set TYPEBIT_KEEP_GRADLE_PROXY=1 to keep the proxy (use that when
# the proxy is actually running and your network needs it).

function Invoke-TypeBitGradle {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string] $ProjectRoot,
        [Parameter(Mandatory = $true)][string[]] $Arguments
    )

    $gradleProps = Join-Path $env:USERPROFILE ".gradle\gradle.properties"
    $original = $null
    $neutralised = $false

    if ((Test-Path $gradleProps) -and (-not $env:TYPEBIT_KEEP_GRADLE_PROXY)) {
        $text = Get-Content -Raw -LiteralPath $gradleProps
        if ($text -match '(?m)^\s*systemProp\.(http|https)\.proxyHost\s*=\s*(127\.0\.0\.1|localhost|::1)\s*$') {
            $original = $text
            Set-Content -LiteralPath $gradleProps -Value "# temporarily cleared by scripts/lib/gradle-env.ps1 (loopback proxy detected)" -Encoding ascii
            $neutralised = $true
            Write-Host "==> neutralised the loopback proxy in $gradleProps for this build"
        }
    }

    try {
        Push-Location $ProjectRoot
        try {
            & .\gradlew.bat @Arguments --console=plain
            if ($LASTEXITCODE -ne 0) { throw "gradlew $($Arguments -join ' ') failed with exit code $LASTEXITCODE" }
        } finally {
            Pop-Location
        }
    } finally {
        if ($neutralised) {
            # Byte-exact restore, and explicitly WITHOUT a BOM: PowerShell 5.1's
            # `-Encoding utf8` writes one, and a BOM in gradle.properties makes
            # the first line unparsable for Java's Properties loader.
            [System.IO.File]::WriteAllText(
                $gradleProps,
                $original,
                (New-Object System.Text.UTF8Encoding($false))
            )
            Write-Host "==> restored $gradleProps"
        }
    }
}
