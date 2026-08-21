# RuntimeBroker agent deploy (run as Administrator)
# Stops the service, swaps the EXE + config, starts it again.
$ErrorActionPreference = 'Stop'
$log = 'C:\Users\HP\Desktop\RuntimeBroker\deploy-agent.log'
Start-Transcript -Path $log -Force | Out-Null
try {
    $src = 'C:\Users\HP\Desktop\RuntimeBroker\publish-single'
    $dst = 'C:\Program Files\RuntimeBroker'
    if (-not (Test-Path (Join-Path $src 'RuntimeBroker.exe'))) { throw "Published EXE not found: $src" }

    Write-Host 'Stopping RuntimeBroker service...'
    Stop-Service RuntimeBroker -Force
    Start-Sleep -Seconds 3

    Write-Host 'Copying files...'
    Copy-Item -LiteralPath (Join-Path $src 'RuntimeBroker.exe') -Destination (Join-Path $dst 'RuntimeBroker.exe') -Force
    Copy-Item -LiteralPath (Join-Path $src 'agent.config.json') -Destination (Join-Path $dst 'agent.config.json') -Force

    Write-Host 'Starting RuntimeBroker service...'
    Start-Service RuntimeBroker
    Start-Sleep -Seconds 3
    $s = Get-Service RuntimeBroker
    Write-Host "Service status: $($s.Status)"
    Write-Host 'DEPLOY_OK'
} catch {
    Write-Host "DEPLOY_ERROR: $($_.Exception.Message)"
}
Stop-Transcript | Out-Null