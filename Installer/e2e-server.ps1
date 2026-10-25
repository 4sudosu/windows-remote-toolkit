# Non-intrusive E2E tests of every remote feature that does not need the
# user's active input. Results: PASS/FAIL per line.
$ErrorActionPreference = 'Continue'
$base = 'https://mimo-pro.onrender.com'
$hdr  = @{ 'x-admin-password' = 'Alok' }
$machine = 'desktop-79bjncg'
$pass = 0; $fail = 0

function Check($name, $ok, $detail = '') {
  $script:pass++ ; $script:fail++ ; if ($ok) { $script:fail-- } else { $script:pass-- }
  $tag = if ($ok) { 'PASS' } else { 'FAIL' }
  Write-Host ("[{0}] {1} {2}" -f $tag, $name, $detail)
}

function Cmd($cmd, $params = @{}, $timeoutSec = 0) {
  $body = @{ cmd = $cmd; params = $params }
  if ($timeoutSec) { $body.params.timeoutSec = $timeoutSec }
  Invoke-RestMethod "$base/api/monitor/$machine/command" -Method Post -Headers $hdr -ContentType 'application/json' -Body ($body | ConvertTo-Json -Depth 6) -TimeoutSec 60
}

# 0. Device online?
$agents = Invoke-RestMethod "$base/api/agents" -Headers $hdr -TimeoutSec 30
Check 'device-online' ($agents.Count -ge 1 -and $agents[0].online) "$($agents[0].machineName) $($agents[0].model) v$($agents[0].version)"

# 1. Screenshot
try {
  $s = Invoke-RestMethod "$base/api/monitor/$machine/screenshot" -Method Post -Headers $hdr -TimeoutSec 60
  Check 'screenshot' ($s.success -and $s.image.Length -gt 1000) ("image bytes: " + [Math]::Round($s.image.Length * 0.75))
} catch { Check 'screenshot' $false $_.Exception.Message }

# 2. shell_exec
$r = Cmd 'shell_exec' @{ command = 'cmd /c echo hello-mimo-%COMPUTERNAME%'; timeoutSec = 20 }
Check 'shell_exec' ($r.success -and "$($r.output)" -match 'hello-mimo') "$($r.output) | exit $($r.exitCode)"

# 3. list_processes
$r = Cmd 'list_processes'
Check 'list_processes' ($r.success -and $r.data.Count -gt 20) ("procs: " + ($r.data | Measure-Object).Count)

# 4. list_services
$r = Cmd 'list_services'
Check 'list_services' ($r.success -and $r.data.Count -gt 10) ("svcs: " + ($r.data | Measure-Object).Count)

# 5. list_files
$r = Cmd 'list_files' @{ path = 'C:\Program Files\Mimo Pro' }
Check 'list_files' ($r.success -and ($r.data | Measure-Object).Count -ge 2) ("entries: " + ($r.data | Measure-Object).Count)

# 6. write_file + read_file round trip
$payload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("mimo-e2e-roundtrip"))
$path = "C:\Windows\Temp\mimo-e2e-$(Get-Date -Format HHmmss).txt"
$r = Cmd 'write_file' @{ path = $path; base64 = $payload }
Check 'write_file' $r.success ($r.output + $r.error)
$r = Cmd 'read_file' @{ path = $path }
$back = ''
try { $back = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($r.output)) } catch {}
Check 'read_file' ($r.success -and $back -eq 'mimo-e2e-roundtrip') ("back: " + $back)
Cmd 'shell_exec' @{ command = "cmd /c del `"$path`""; timeoutSec = 15 } | Out-Null

# 7. kill_process on a spawned dummy
$dummy = Cmd 'shell_exec' @{ command = 'cmd /c ping -n 999 127.0.0.1 >nul 2>&1'; timeoutSec = 5 }
Start-Sleep -Seconds 2
$procs = Cmd 'list_processes'
$target = ($procs.data | Where-Object { $_.name -ieq 'PING.EXE' -or $_.name -ieq 'ping.exe' } | Select-Object -First 1)
if ($target) {
  $r = Cmd 'kill_process' @{ pid = $target.pid }
  Check 'kill_process' $r.success ("pid $($target.pid)")
} else { Check 'kill_process' $false 'no dummy ping.exe found' }

# 8. service_action restart on agent's own MimoPro service (proves control + recovery)
$r = Cmd 'service_action' @{ name = 'MimoPro'; action = 'restart' } 0
Check 'service_action' $r.success "$($r.output) $($r.error)"

# 9. mouse move + wheel (non-click, non-disruptive)
$r = Cmd 'input_mouse' @{ x = 960; y = 540; action = 'move' }
Check 'input_mouse-move' $r.success ($r.error)
$r = Cmd 'input_mouse' @{ x = 0; y = 0; action = 'wheel'; amount = 120 }
Check 'input_mouse-wheel' $r.success ($r.error)

# 10. stop_all (control-plane noop here)
$r = Cmd 'stop_all'
Check 'stop_all' $r.success ($r.output)

Write-Host ""
Start-Sleep -Seconds 20
$h = Invoke-RestMethod "$base/api/health" -TimeoutSec 30
Check 'agent-online-after-restart' ($h.agents -ge 1) ("health agents=$($h.agents) (waited for service restart recovery)")
Write-Host "SUMMARY: pass=$pass fail=$fail"

