// SSE listener: record agent-online/agent-offline events with timestamps.
const https = require('https');
const req = https.get('https://mimo-pro.onrender.com/api/events', { timeout: 0 }, (res) => {
  console.log('SSE STATUS ' + res.statusCode);
  res.setEncoding('utf8');
  let buf = '';
  res.on('data', (chunk) => {
    buf += chunk;
    let idx;
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const block = buf.slice(0, idx);
      buf = buf.slice(idx + 2);
      const m = block.match(/data: (.*)/);
      if (m) {
        try {
          const ev = JSON.parse(m[1]);
          console.log(new Date().toISOString() + ' ' + JSON.stringify(ev));
        } catch {}
      }
    }
  });
  res.on('error', (e) => console.log('SSE ERR ' + e.message));
});
req.on('error', (e) => console.log('REQ ERR ' + e.message));
setTimeout(() => process.exit(0), Number(process.argv[2] || 180000));
