// Probe with agent-identical payload: hold for 40s while /api/health is polled.
const WebSocket = require('ws');
const ws = new WebSocket('wss://mimo-pro.onrender.com/ws/agent?token=');
ws.on('open', () => {
  console.log('CONNECTED');
  ws.send(JSON.stringify({
    type: 'register',
    machineName: 'DESKTOP-79BJNCG',
    hostname: 'DESKTOP-79BJNCG',
    model: 'Sword 16 HX B13VFKG',
    serial: 'WS-DIAG',
    username: 'MVP',
    user: 'MVP',
    os: 'Windows 10 Pro',
    ip: '1.2.3.4',
    version: '1.0.0.3'
  }));
});
setInterval(() => {
  try { ws.send(JSON.stringify({ type: 'keepalive', at: Math.floor(Date.now() / 1000) })); } catch {}
}, 4000);
ws.on('message', (d) => {
  const m = JSON.parse(d.toString());
  if (m.type !== 'keepalive') console.log('MSG ' + (m.type || '?') + ' ' + JSON.stringify(m).slice(0, 120));
});
ws.on('error', (e) => console.log('ERR ' + e.message));
ws.on('close', (c, r) => console.log('CLOSED ' + c + ' ' + (r || '').toString()));
setTimeout(() => process.exit(0), 40000);
