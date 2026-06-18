# SkinStudio — lokalny build JAR (Windows)
# Użycie: .\build.ps1

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not $env:JAVA_HOME) {
    $candidates = @(
        "C:\Program Files\Java\jdk-26.0.1",
        "C:\Program Files\Java\jdk-21",
        "C:\Program Files\Eclipse Adoptium\jdk-21*",
        "C:\Program Files\Microsoft\jdk-21*"
    )
    foreach ($pattern in $candidates) {
        $found = Get-Item $pattern -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found -and (Test-Path "$found\bin\java.exe")) {
            $env:JAVA_HOME = $found.FullName
            break
        }
    }
}

if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    Write-Error "JAVA_HOME nie ustawione. Ustaw na JDK 21+ (np. C:\Program Files\Java\jdk-26.0.1)"
}

$argsList = if ($args.Count -gt 0) { $args } else { @("clean", "package") }
Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "mvnw $($argsList -join ' ')"

& "$PSScriptRoot\mvnw.cmd" @argsList
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$jar = Get-ChildItem "$PSScriptRoot\target\*.jar" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch 'original|sources|javadoc' } |
    Select-Object -First 1

if ($jar) {
    Write-Host ""
    Write-Host "OK: $($jar.FullName)" -ForegroundColor Green
    Write-Host "Wgraj do plugins/ na serwerze."
} else {
    Write-Warning "Build zakonczyl sie, ale nie znaleziono JAR w target/"
}
