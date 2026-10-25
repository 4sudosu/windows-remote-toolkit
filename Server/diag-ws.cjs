// WS diagnostic: register as diag-client, then send a keepalive and wait
// for (a) the 'registered' reply, (b) a keepalive echo, for up to 30s.
const WebSocket = require('ws');
const ws = new WebSocket('wss://mimo-pro.onrender.com/ws/agent');
const got = { hello: false, registered: false, keepaliveEcho: false };
ws.on('open', () => {
  console.log('CONNECTED');
  ws.send(JSON.stringify({
    type: 'register',
    machineName: 'diag-client',
    hostname: 'diag-client',
    model: 'diag',
    serial: '',
    username: 'diag',
    os: 'test',
    version: 'diag'
  }));
  setTimeout(() => {
    ws.send(JSON.stringify({ type: 'keepalive', at: Math.floor(Date.now() / 1000) }));
    console.log('KEEPALIVE SENT');
  }, 1000);
});
ws.on('message', (data) => {
  const m = JSON.parse(data.toString());
  console.log('MSG type=' + (m.type || '?') + ' at=' + (m.at || ''));
  if (m.type === 'register' || m.type === 'registered') got.registered = true;
  if (m.type === 'keepalive') { got.keepaliveEcho = true; console.log('ECHO OK'); process.exit(0); }
});
ws.on('error', (e) => console.log('ERR', e.message));
ws.on('close', () => console.log('CLOSED'));
setTimeout(() => {
  console.log('TIMEOUT registered=' + got.registered + ' keepaliveEcho=' + got.keepaliveEcho);
  process.exit(1);
}, 30000);
