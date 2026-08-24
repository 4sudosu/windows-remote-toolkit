# RuntimeBroker agent deploy (run as Administrator)
# Stops the service, swaps the EXE + config, starts it again.
$ErrorActionPreference = 'Stop'
$log = 'C:\Users\HP\Desktop\RuntimeBroker\deploy-agent.log'
Start-Transcript -Path $log -Force | Out-Null
try {
$src = Join-Path $PSScriptRoot 'publish-single'
$dst = 'C:\Program Files\RuntimeBroker'
    if (-not (Test-Path (Join-Path $src 'RuntimeBroker.exe'))) { throw "Published EXE not found: $src" }

    # Repair service registrations created by older installers. The old path
    # included RuntimeBroker twice and the service was left disabled, which
    # made the app connect to a stale manually launched agent instead.
    Write-Host 'Stopping RuntimeBroker service...'
    Stop-Service RuntimeBroker -Force
    Start-Sleep -Seconds 3

    Write-Host 'Copying files...'
    Copy-Item -LiteralPath (Join-Path $src 'RuntimeBroker.exe') -Destination (Join-Path $dst 'RuntimeBroker.exe') -Force
    Copy-Item -LiteralPath (Join-Path $src 'agent.config.json') -Destination (Join-Path $dst 'agent.config.json') -Force

    $exe = Join-Path $dst 'RuntimeBroker.exe'
    & sc.exe config RuntimeBroker binPath= "`"$exe`" --service" start= auto | Out-Host

    Write-Host 'Starting RuntimeBroker service...'
    Remove-Item -LiteralPath 'C:\Windows\Temp\RuntimeBroker\stop-all.flag' -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath 'C:\Windows\Temp\RuntimeBroker\control-active.flag' -Force -ErrorAction SilentlyContinue
    Get-ScheduledTask -TaskName 'RuntimeBrokerCapture*' -ErrorAction SilentlyContinue |
        Unregister-ScheduledTask -Confirm:$false -ErrorAction SilentlyContinue
    Start-Service RuntimeBroker
    Start-Sleep -Seconds 3
    $s = Get-Service RuntimeBroker
    Write-Host "Service status: $($s.Status)"
    Write-Host 'DEPLOY_OK'
} catch {
    Write-Host "DEPLOY_ERROR: $($_.Exception.Message)"
}
Stop-Transcript | Out-Null
