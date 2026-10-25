# Elevated one-shot: install the rebuilt Mimo Pro agent MSI, restore the
# correct server config, ACL-lock it, set crash recovery, start the service.
# Mirrors what MimoSetup.exe does after the MSI finishes. Writes a log.

$ErrorActionPreference = 'Continue'
$log = 'E:\AI\Mimo\Installer\elevated-update.log'
Start-Transcript -Path $log -Force | Out-Null

try {
  $msi = 'E:\AI\Mimo\Installer\MimoPro.msi'
  $dir = 'C:\Program Files\Mimo Pro'

  Write-Host "Running msiexec (silent, upgrade in place)..."
  $p = Start-Process msiexec.exe -ArgumentList "/i `"$msi`" /qn /norestart" -Wait -PassThru
  Write-Host "msiexec exit code: $($p.ExitCode)"

  Write-Host "Writing agent.config.json..."
  $config = @{
    ServerUrl         = 'wss://mimo-pro.onrender.com/ws/agent'
    Token             = ''
    ReconnectDelaySec = 5
    KeepAliveSec      = 8
  } | ConvertTo-Json
  Set-Content -Path "$dir\agent.config.json" -Value $config -Encoding ASCII

  Write-Host "Locking config ACL..."
  icacls "$dir\agent.config.json" /grant 'SYSTEM:F' /grant 'Administrators:F' | Out-Host

  Write-Host "Configuring service failure recovery..."
  sc.exe failure MimoPro reset= 86400 actions= restart/5000/restart/10000/restart/30000 | Out-Host

  Write-Host "Starting service..."
  sc.exe start MimoPro | Out-Host

  Write-Host "Waiting up to 120s for registration..."
  $ok = $false
  for ($i = 0; $i -lt 24; $i++) {
    Start-Sleep -Seconds 5
    try {
      $h = Invoke-WebRequest 'https://mimo-pro.onrender.com/api/health' -UseBasicParsing -TimeoutSec 15
      Write-Host "health: $($h.Content)"
      if ($h.Content -match '"agents":1') { $ok = $true; break }
    } catch { Write-Host "health check error: $($_.Exception.Message)" }
  }
  Write-Host "RESULT: $(if ($ok) { 'AGENT REGISTERED' } else { 'NOT REGISTERED YET (may need a moment)' })"
} finally {
  Stop-Transcript | Out-Null
}
