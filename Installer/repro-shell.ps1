# Controlled repro: watch exactly when the connection dies relative to a
# long-running shell_exec spawn, capturing response bodies.
$ErrorActionPreference = 'Continue'
$base = 'https://mimo-pro.onrender.com'
$hdr  = @{ 'x-admin-password' = 'Alok' }
$machine = 'desktop-79bjncg'

function AgentHealth($t) {
  try { return ((Invoke-RestMethod "$base/api/health" -Headers $hdr -TimeoutSec 25).agents) } catch { return -1 }
}

function CmdBody($cmd, $params = @{}) {
  $body = @{ cmd = $cmd; params = $params } | ConvertTo-Json -Depth 6
  try {
    $r = Invoke-WebRequest "$base/api/monitor/$machine/command" -Method Post -Headers $hdr -ContentType 'application/json' -Body $body -TimeoutSec 90
    $c = $r.Content
    $len = [Math]::Min(220, $c.Length)
    Write-Host ("  [OK] " + $c.Substring(0, $len))
  } catch {
    $resp = $null
    try { $resp = $_.Exception.Response } catch {}
    $code = 0
    $txt = $_.Exception.Message
    if ($resp) {
      $code = [int]$resp.StatusCode
      try { $sr = New-Object IO.StreamReader($resp.GetResponseStream()); $txt = $sr.ReadToEnd() } catch { $txt = $_.Exception.Message }
    }
    Write-Host ("  [$code] " + $txt)
  }
}

$value = (Get-Service MimoPro).Status
if ($value -ne 'Running') {
  Write-Host 'SERVICE NOT RUNNING - starting'
  $p = Start-Process powershell -Verb RunAs -ArgumentList '-Command','Start-Service MimoPro' -Wait
  Start-Sleep -Seconds 15
}

Write-Host ("T+0   agents=" + (AgentHealth))
Write-Host 'T+0   spawn long shell (timeout 3s):'
CmdBody 'shell_exec' @{ command = 'cmd /c ping -n 900 127.0.0.1'; timeoutSec = 3 }
Write-Host ("T+6   agents=" + (AgentHealth))
Write-Host 'T+6   list_processes:'
CmdBody 'list_processes'
Start-Sleep -Seconds 10
Write-Host ("T+26  agents=" + (AgentHealth))
Start-Sleep -Seconds 15
Write-Host ("T+41  agents=" + (AgentHealth))
Start-Sleep -Seconds 20
Write-Host ("T+61  agents=" + (AgentHealth))
Write-Host '--- agent log tail ---'
Get-Content 'C:\Program Files\Mimo Pro\agent.service.log' -Tail 8
(Get-Service MimoPro).Status
