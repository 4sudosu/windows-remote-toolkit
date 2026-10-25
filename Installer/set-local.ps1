$cfg = 'C:\Program Files\Mimo Pro\agent.config.json'
$config = @{
  ServerUrl         = 'wss://mimo-pro.onrender.com/ws/agent'
  Token             = ''
  ReconnectDelaySec = 5
  KeepAliveSec      = 8
} | ConvertTo-Json
Set-Content -Path $cfg -Value $config -Encoding ASCII
Restart-Service MimoPro -Force
Write-Host "config set to render w/ 8s keepalive, service restarted"
