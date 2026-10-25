# Elevated: install the rebuilt MSI over the existing MimoPro service, restore
# config (8s keepalive), ACL, recovery, start service. Log to elevated-update4.log.
$ErrorActionPreference = 'Continue'
$log = 'E:\AI\Mimo\Installer\elevated-update4.log'
Start-Transcript -Path $log -Force | Out-Null

try {
  $msi = 'E:\AI\Mimo\Installer\MimoPro.msi'
  $dir = 'C:\Program Files\Mimo Pro'

  Write-Host "Enumerating installed Mimo Pro products..."
  $products = Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*' -ErrorAction SilentlyContinue |
    Where-Object { $_.DisplayName -like 'Mimo*' -and $_.UninstallString -like 'MsiExec.exe /I*' } |
    ForEach-Object { [regex]::Match($_.UninstallString, '\{[0-9A-Fa-f-]+\}').Value } | Select-Object -Unique
  foreach ($code in $products) {
    Write-Host "Uninstalling $code ..."
    $p = Start-Process msiexec.exe -ArgumentList "/x $code /qn /norestart" -Wait -PassThru
    Write-Host "exit: $($p.ExitCode)"
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
  Start-Sleep -Seconds 20
  Write-Host "Service state: $((Get-Service MimoPro).Status)"
} finally {
  Stop-Transcript | Out-Null
}
