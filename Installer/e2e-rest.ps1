# Remaining E2E features: kill_process, service_action, input_mouse, stop_all,
# screen_rotate, play/stop audio, camera/mic (expected to fail gracefully on
# a desktop), and final online state.
$ErrorActionPreference = 'Continue'
$base = 'https://mimo-pro.onrender.com'
$hdr  = @{ 'x-admin-password' = 'Alok' }
$machine = 'desktop-79bjncg'
$pass = 0; $fail = 0

function Check($name, $ok, $detail = '') {
  if ($ok) { $script:pass++ ; $tag = 'PASS' } else { $script:fail++; $tag = 'FAIL' }
  Write-Host ("[{0}] {1} {2}" -f $tag, $name, $detail)
}

function Cmd($cmd, $params = @{}, $timeoutSec = 0) {
  $body = @{ cmd = $cmd; params = $params }
  if ($timeoutSec) { $body.params.timeoutSec = $timeoutSec }
  Invoke-RestMethod "$base/api/monitor/$machine/command" -Method Post -Headers $hdr -ContentType 'application/json' -Body ($body | ConvertTo-Json -Depth 6) -TimeoutSec 90
}

# 1. kill_process: spawn a long dummy ping, kill it by real pid.
$null = Cmd 'shell_exec' @{ command = 'cmd /c ping -n 900 127.0.0.1 >nul 2>&1'; timeoutSec = 600 }
Start-Sleep -Seconds 2
$procs = Cmd 'list_processes'
$target = $procs.data | Where-Object { $_.name -ieq 'ping' } | Select-Object -First 1
if ($target) {
  $r = Cmd 'kill_process' @{ pid = $target.pid }
  Check 'kill_process' ($r.success) ("killed pid $($target.pid) : " + "$($r.output)$($r.error)")
} else { Check 'kill_process' $false 'ping.exe not found' }

# 2. input_mouse (move + tiny wheel), non-click.
$r = Cmd 'input_mouse' @{ x = 960; y = 540; action = 'move' }
Check 'input_mouse-move' ($r.success) "$($r.output)$($r.error)"
$r = Cmd 'input_mouse' @{ x = 0; y = 0; action = 'wheel'; amount = 120 }
Check 'input_mouse-wheel' ($r.success) "$($r.output)$($r.error)"

# 3. screen_rotate 0 (no-op rotate).
$r = Cmd 'screen_rotate' @{ degrees = 0 }
Check 'screen_rotate' ($r.success) "$($r.output)$($r.error)"

# 4. play_audio + stop_audio: short beep WAV sent as base64.
$beep = [Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:WINDIR\Media\Windows.Notify.wav"))
$r = Cmd 'play_audio' @{ base64 = $beep; filename = 'notify.wav' }
Check 'play_audio' ($r.success) "$($r.output)$($r.error)"
$r = Cmd 'stop_audio'
Check 'stop_audio' ($r.success) "$($r.output)$($r.error)"

# 5. camera/mic on a desktop (graceful error expected).
$r = Cmd 'camera_photo' @{}
$extra = $r.error; if (-not $extra) { $extra = 'none' }
Check 'camera_photo' ($r.success -or ($r.error -and $r.error.Length -gt 0)) ("success=$($r.success) err: $extra")
$r = Cmd 'mic_record' @{ seconds = 2 }
$extra = $r.error; if (-not $extra) { $extra = 'none' }
Check 'mic_record' ($r.success -or ($r.error -and $r.error.Length -gt 0)) ("success=$($r.success) err: $extra")

# 6. stop_all.
$r = Cmd 'stop_all'
Check 'stop_all' ($r.success) "$($r.output)$($r.error)"

# 7. service_action restart on the agent's own service, then recovery.
$r = Cmd 'service_action' @{ name = 'MimoPro'; action = 'restart' }
Check 'service_action-restart' ($r.success) "$($r.output)$($r.error)"

Start-Sleep -Seconds 25
$ok = $false
for ($i = 0; $i -lt 12; $i++) {
  Start-Sleep -Seconds 5
  $h = Invoke-RestMethod "$base/api/health" -TimeoutSec 25
  if ($h.agents -ge 1) { $ok = $true; break }
}
Check 'agent-recovered-after-restart' $ok ("health agents=$((Invoke-RestMethod "$base/api/health" -TimeoutSec 25).agents)")

Write-Host "SUMMARY: pass=$pass fail=$fail"
