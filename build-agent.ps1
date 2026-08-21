# RuntimeBroker - Standalone Build & Installer Script
# Run this after making any changes in this source folder.
# It bumps the version, publishes a self-contained single-file agent, drops the
# ready-to-push EXE into "Ready to Push", AND compiles a versioned setup
# installer into "installer-output".
#
# Usage:  powershell -ExecutionPolicy Bypass -File .\build-agent.ps1
# Output: ..\Ready to Push\RuntimeBroker.0.0.0.X.exe
#         ..\installer-output\RuntimeBroker-Setup-0.0.0.X.exe

$ErrorActionPreference = 'Stop'

$csproj = Join-Path $PSScriptRoot 'Agent\RuntimeBroker.csproj'
$dotnet = $env:DOTNET_PATH
if (-not $dotnet -or -not (Test-Path $dotnet))
{
    $candidates = @(
        'C:\Program Files\dotnet\dotnet.exe',
        (Join-Path $env:LOCALAPPDATA 'Microsoft\dotnet\dotnet.exe'),
        (Join-Path $env:USERPROFILE '.dotnet\dotnet.exe')
    )
    foreach ($c in $candidates)
    {
        if (Test-Path $c) { $dotnet = $c; break }
    }
}
if (-not $dotnet -or -not (Test-Path $dotnet)) { throw "dotnet SDK not found - install it or set env:DOTNET_PATH" }
Write-Host "Using dotnet: $dotnet"
$outDir = Join-Path $PSScriptRoot 'Ready to Push'
$publishDir = Join-Path $PSScriptRoot 'publish-single'
$installerOut = Join-Path $PSScriptRoot 'installer-output'

# -- 1. Read current version from csproj ------------------------------------
$xml = [xml](Get-Content -LiteralPath $csproj -Raw)
$ver = $xml.Project.PropertyGroup.Version

# -- 2. Bump version (0.0.0.1 -> 0.0.0.100 -> 0.0.1.0 scheme) ---------------
$parts = @($ver.Split('.'))
while ($parts.Count -lt 4) { $parts += '0' }
$parts = @($parts | ForEach-Object { [int]$_ })
$parts[3] += 1
$i = 3
while ($i -gt 0 -and $parts[$i] -gt 100) { $parts[$i] = 0; $parts[$i - 1] += 1; $i -= 1 }
$newVer = ($parts -join '.')

# -- 3. Write new version back to csproj ------------------------------------
$xml.Project.PropertyGroup.Version = $newVer
$xml.Project.PropertyGroup.InformationalVersion = $newVer
$xml.Save($csproj)

Write-Host "Version bumped: $ver  ->  $newVer"

# -- 4. Publish self-contained single-file build ----------------------------
if (Test-Path $publishDir) { Remove-Item -LiteralPath $publishDir -Recurse -Force }
& $dotnet publish $csproj -c Release -r win-x64 --self-contained true `
    -p:PublishSingleFile=true -p:DebugType=None -o $publishDir
if ($LASTEXITCODE -ne 0) { throw "dotnet publish failed (exit $LASTEXITCODE)" }

$singleExe = Join-Path $publishDir 'RuntimeBroker.exe'
if (-not (Test-Path $singleExe)) { throw "Publish output not found: $singleExe" }

# -- 5. Ship the agent config so the EXE can start out of the box -----------
$agentConfig = Join-Path $PSScriptRoot 'Agent\agent.config.json'
if (Test-Path $agentConfig)
{
    Copy-Item -LiteralPath $agentConfig -Destination (Join-Path $publishDir 'agent.config.json') -Force
}

# -- 6. Copy to "Ready to Push" with versioned name -------------------------
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }
$final = Join-Path $outDir "RuntimeBroker.$newVer.exe"
Copy-Item -LiteralPath $singleExe -Destination $final -Force

# -- 7. Compile the versioned installer (Inno Setup 6) ----------------------
$iscc = 'C:\Users\YO\AppData\Local\Programs\Inno Setup 6\ISCC.exe'
if (-not (Test-Path $iscc)) { $iscc = "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe" }
if (-not (Test-Path $iscc)) { $iscc = 'C:\Program Files (x86)\Inno Setup 6\ISCC.exe' }
if (Test-Path $iscc)
{
    $installerIss = Join-Path $PSScriptRoot 'Installer\installer.iss'
    if (-not (Test-Path $installerOut)) { New-Item -ItemType Directory -Path $installerOut -Force | Out-Null }
    $setupName = "RuntimeBroker-Setup-$newVer"
    & $iscc $installerIss "/DMyAppVersion=$newVer" "/DMyOutputBaseFilename=$setupName" | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "ISCC installer compile failed (exit $LASTEXITCODE)" }

    $setupExe = Join-Path $installerOut "$setupName.exe"
    if (Test-Path $setupExe)
    {
        Write-Host ""
        Write-Host "=== INSTALLER READY ==="
        Write-Host "Installer: $setupExe"
        Write-Host "Size     : $([math]::Round((Get-Item $setupExe).Length/1MB,1)) MB"
    }
    else
    {
        throw "Installer output not found: $setupExe"
    }
}
else
{
    Write-Host "WARNING: Inno Setup (ISCC.exe) not found - installer NOT built."
}

Write-Host ""
Write-Host "=== READY TO PUSH ==="
Write-Host "Version : $newVer"
Write-Host "File    : $final"
Write-Host "Size    : $([math]::Round((Get-Item $final).Length/1MB,1)) MB"
Write-Host ""
Write-Host "Next step: run the installer on target machines, or push via the admin dashboard."