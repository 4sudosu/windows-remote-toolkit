# Relocates the RuntimeBroker agent out of the WinSysMonitor folder into its own
# Program Files\RuntimeBroker folder, re-points the RuntimeBroker service,
# then REMOVES the RuntimeBroker files from WinSysMonitor.
# WinSysMonitor.exe and its files are NOT touched. Run as Administrator.

$ErrorActionPreference = 'Stop'
$src = 'C:\Program Files\WinSysMonitor'
$dst = 'C:\Program Files\RuntimeBroker'
$svc = 'RuntimeBroker'

if (-not (Test-Path $src)) { Write-Error "Source folder not found: $src"; exit 1 }

New-Item -ItemType Directory -Path $dst -Force | Out-Null

$rbFiles = @(
  'RuntimeBroker.exe',
  'D3DCompiler_47_cor3.dll',
  'PenImc_cor3.dll',
  'PresentationNative_cor3.dll',
  'vcruntime140_cor3.dll',
  'wpfgfx_cor3.dll',
  'agent.config.json'
)

# 1. Copy RuntimeBroker files to their own folder
foreach ($f in $rbFiles) {
  Copy-Item -Path (Join-Path $src $f) -Destination $dst -Force
}

# 2. Lock config like the installer does
icacls.exe "$dst\agent.config.json" /inheritance:r /grant:r "SYSTEM:(F)" "Administrators:(F)" "BUILTIN\Users:(R)" | Out-Null

# 3. Re-point service
sc.exe stop $svc | Out-Null
Start-Sleep -Milliseconds 800
sc.exe config $svc binPath= "`"$dst\RuntimeBroker.exe`" --service" | Out-Null
sc.exe failure $svc reset= 86400 actions= restart/5000/restart/10000/restart/30000 | Out-Null
sc.exe start $svc | Out-Null

# 4. Remove RuntimeBroker duplicates from WinSysMonitor folder
foreach ($f in $rbFiles) {
  $path = Join-Path $src $f
  if (Test-Path $path) { Remove-Item -LiteralPath $path -Force }
}

Start-Sleep -Seconds 2
Get-Service $svc | Select-Object Name, Status, @{n='Path'; e={(Get-CimInstance Win32_Service -Filter "Name='$svc'").PathName}}
Write-Host 'RuntimeBroker relocated. Duplicates removed from WinSysMonitor folder. WinSysMonitor untouched.'