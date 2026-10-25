# Elevated cleanup + fresh install. Log to elevated-update2.log.
$ErrorActionPreference = 'Continue'
$log = 'E:\AI\Mimo\Installer\elevated-update2.log'
Start-Transcript -Path $log -Force | Out-Null

try {
  $msi = 'E:\AI\Mimo\Installer\MimoPro.msi'
  $dir = 'C:\Program Files\Mimo Pro'

  Write-Host "Uninstalling previous Mimo Pro versions..."
  foreach ($code in @('{985CD396-019D-4E00-977B-A449645364AD}', '{978169E2-D336-4FFB-90C0-F7C393295793}')) {
    $p = Start-Process msiexec.exe -ArgumentList "/x $code /qn /norestart" -Wait -PassThru
    Write-Host "uninstall $code exit: $($p.ExitCode)"
  }

  Write-Host "Fresh MSI install..."
  $p = Start-Process msiexec.exe -ArgumentList "/i `"$msi`" /qn /norestart" -Wait -PassThru
  Write-Host "msiexec exit code: $($p.ExitCode)"

  Write-Host "Installed exe version: $((Get-Item "$dir\MimoPro.exe").VersionInfo.ProductVersion)"

  Write-Host "Writing agent.config.json..."
  $config = @{
    ServerUrl         = 'wss://mimo-pro.onrender.com/ws/agent'
    Token             = ''
    ReconnectDelaySec = 5
    KeepAliveSec      = 8
  } | ConvertTo-Json
  Set-Content -Path "$dir\agent.config.json" -Value $config -Encoding ASCII

  icacls "$dir\agent.config.json" /grant 'SYSTEM:F' /grant 'Administrators:F' | Out-Host
  sc.exe failure MimoPro reset= 86400 actions= restart/5000/restart/10000/restart/30000 | Out-Host

  Write-Host "Starting service..."
  sc.exe start MimoPro | Out-Host

  Write-Host "Waiting up to 150s for registration..."
  $ok = $false
  for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 5
    try {
      $h = Invoke-WebRequest 'https://mimo-pro.onrender.com/api/health' -UseBasicParsing -TimeoutSec 15
      Write-Host "health: $($h.Content)"
      if ($h.Content -match '"agents":1') { $ok = $true; break }
    } catch { Write-Host "health check error: $($_.Exception.Message)" }
  }
  Write-Host "RESULT: $(if ($ok) { 'AGENT REGISTERED' } else { 'NOT REGISTERED YET' })"
} finally {
  Stop-Transcript | Out-Null
}
