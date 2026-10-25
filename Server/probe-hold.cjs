// Probe: register and hold the connection for 45s so the health counter
// should read 1 the whole time. If some health polls read 0, Render must be
// running more than one instance.
const WebSocket = require('ws');
const ws = new WebSocket('wss://mimo-pro.onrender.com/ws/agent');
ws.on('open', () => {
  console.log('CONNECTED');
  ws.send(JSON.stringify({
    type: 'register',
    machineName: 'probe-hold',
    hostname: 'probe-hold',
    model: 'probe',
    serial: '',
    username: 'probe',
    os: 'test',
    version: 'probe'
  }));
});
setInterval(() => {
  try { ws.send(JSON.stringify({ type: 'keepalive', at: Date.now() })); } catch {}
}, 5000);
ws.on('message', (d) => {
  const m = JSON.parse(d.toString());
  if (m.type !== 'keepalive') console.log('MSG ' + (m.type || '?'));
});
ws.on('error', (e) => console.log('ERR ' + e.message));
ws.on('close', () => console.log('CLOSED'));
setTimeout(() => process.exit(0), 45000);
