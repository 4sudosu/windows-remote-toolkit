/* End-to-end verification v2 ??? resilient (one failure doesn't stop the run). */
const WebSocket = require('ws');
const http = require('http');
const fs = require('fs');

const BASE = 'http://127.0.0.1:4778';
const WS_BASE = 'ws://127.0.0.1:4778';
const PW = '.\\itdtpadmin';

function post(path, body, timeoutMs = 300000) {
  return new Promise((resolve) => {
    const data = JSON.stringify(body);
    const req = http.request(BASE + path, { method: 'POST', headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data) } }, res => {
      let buf = '';
      res.on('data', c => buf += c);
      res.on('end', () => { try { resolve({ status: res.statusCode, json: JSON.parse(buf) }); } catch (e) { resolve({ status: res.statusCode, json: {} }); } });
    });
    req.on('error', (e) => resolve({ status: 0, json: { success: false, error: e.message } }));
    req.setTimeout(timeoutMs, () => { req.destroy(new Error('client timeout')); });
    req.write(data); req.end();
  });
}

function get(path) {
  return new Promise((resolve) => {
    http.get(BASE + path, res => {
      let buf = '';
      res.on('data', c => buf += c);
      res.on('end', () => { try { resolve(JSON.parse(buf)); } catch (e) { resolve({}); } });
    }).on('error', () => resolve({}));
  });
}

function makeWav(seconds = 0.4, freq = 440) {
  const rate = 8000, n = Math.floor(rate * seconds);
  const dataSize = n * 2;
  const buf = Buffer.alloc(44 + dataSize);
  buf.write('RIFF', 0); buf.writeUInt32LE(36 + dataSize, 4); buf.write('WAVE', 8);
  buf.write('fmt ', 12); buf.writeUInt32LE(16, 16); buf.writeUInt16LE(1, 20);
  buf.writeUInt16LE(1, 22); buf.writeUInt32LE(rate, 24); buf.writeUInt32LE(rate * 2, 28);
  buf.writeUInt16LE(2, 32); buf.writeUInt16LE(16, 34);
  buf.write('data', 36); buf.writeUInt32LE(dataSize, 40);
  for (let i = 0; i < n; i++) buf.writeInt16LE(Math.round(Math.sin(2 * Math.PI * freq * i / rate) * 12000), 44 + i * 2);
  return buf;
}

async function cmd(machine, command, params = {}, timeoutMs = 300000) {
  return post(`/api/monitor/${machine}/command`, { password: PW, cmd: command, params }, timeoutMs);
}

function liveFrame(machine, intervalMs = 400) {
  return new Promise((resolve) => {
    const ws = new WebSocket(`${WS_BASE}/ws/live/${machine}?token=${encodeURIComponent(PW)}&interval=${intervalMs}`);
    let frames = 0, totalBytes = 0, firstSize = 0, jpeg = true, done = false;
    const t0 = Date.now();
    const finish = () => { if (!done) { done = true; try { ws.close(); } catch (e) {} resolve({ ok: frames > 0 && jpeg, frames, avgKB: Math.round(totalBytes / Math.max(frames,1) / 1024), firstKB: Math.round(firstSize/1024), isJpeg: jpeg, elapsedMs: Date.now() - t0 }); } };
    ws.on('message', (d) => {
      try {
        const m = JSON.parse(d.toString());
        if (m.type === 'frame') {
          frames++;
          const bytes = Buffer.from(m.image, 'base64');
          totalBytes += bytes.length;
          if (frames === 1) firstSize = bytes.length;
          if (!(bytes[0] === 0xFF && bytes[1] === 0xD8)) jpeg = false;
        }
        if (m.type === 'error') finish();
      } catch (e) {}
      if (frames >= 5 || Date.now() - t0 > 15000) finish();
    });
    ws.on('error', () => finish());
    setTimeout(() => finish(), 20000);
  });
}

(async () => {
  const results = [];
  const log = (name, pass, detail) => { results.push(pass); console.log(`${pass ? 'PASS' : 'FAIL'} | ${name} | ${detail}`); };

  const health = await get('/api/health');
  log('server-health', health.ok === true, `agents=${health.agents}`);
  await new Promise(r => setTimeout(r, 4000));
  const agents = await get('/api/agents');
  const machine = Array.isArray(agents) && agents.length ? agents[0].machineName : null;
  log('agent-online', !!machine, machine || 'no agents');
  if (!machine) process.exit(2);

  try {
    const live = await liveFrame(machine);
    log('live-stream-jpeg', live.ok, `${live.frames} frames first=${live.firstKB}KB avg=${live.avgKB}KB jpeg=${live.isJpeg} ${live.elapsedMs}ms`);
  } catch (e) { log('live-stream-jpeg', false, e.message); }

  try {
    const shot = await post(`/api/monitor/${machine}/screenshot`, { password: PW });
    const kb = shot.json.image ? Math.round(Buffer.byteLength(shot.json.image, 'base64') / 1024) : 0;
    log('screenshot-fullres', shot.json.success === true && kb > 50, `${kb}KB`);
  } catch (e) { log('screenshot-fullres', false, e.message); }

  try {
    const sh = await cmd(machine, 'shell_exec', { command: 'echo hello-from-verify' });
    log('shell-exec', sh.json.success && String(sh.json.output).includes('hello-from-verify'), String(sh.json.output).slice(0, 40));
  } catch (e) { log('shell-exec', false, e.message); }

  try {
    const pr = await cmd(machine, 'list_processes');
    const pc = Array.isArray(pr.json.data) ? pr.json.data.length : 0;
    log('list-processes', pc > 0, `${pc} procs`);
  } catch (e) { log('list-processes', false, e.message); }

  try {
    const sv = await cmd(machine, 'list_services');
    const sc = Array.isArray(sv.json.data) ? sv.json.data.length : 0;
    log('list-services', sc > 50, `${sc} services`);
  } catch (e) { log('list-services', false, e.message); }

  try {
    const cam = await cmd(machine, 'camera_photo', { timeoutSec: 150 }, 180000);
    const kb = cam.json.output ? Math.round(Buffer.byteLength(cam.json.output, 'base64') / 1024) : 0;
    log('camera-photo', cam.json.success === true && kb > 5, cam.json.error || `${kb}KB image`);
  } catch (e) { log('camera-photo', false, e.message); }

  try {
    const mic = await cmd(machine, 'mic_record', { seconds: 3, timeoutSec: 90 }, 120000);
    const kb = mic.json.output ? Math.round(Buffer.byteLength(mic.json.output, 'base64') / 1024) : 0;
    log('mic-record', mic.json.success === true && kb > 1, mic.json.error || `${kb}KB audio`);
  } catch (e) { log('mic-record', false, e.message); }

  try {
    const wav = makeWav();
    fs.writeFileSync('test-beep.wav', wav);
    const play = await cmd(machine, 'play_audio', { audio_base64: wav.toString('base64'), filename: 'beep.wav', timeoutSec: 60 }, 90000);
    log('play-audio', play.json.success === true, play.json.error || String(play.json.output).slice(0, 40));
    const stopA = await cmd(machine, 'stop_audio');
    log('stop-audio', stopA.json.success === true, String(stopA.json.output || stopA.json.error).slice(0, 30));
  } catch (e) { log('play-audio', false, e.message); }

  try {
    const tf = await cmd(machine, 'transfer_file', { file_base64: Buffer.from('runtimebroker-transfer-test-123').toString('base64'), filename: 'verify-test.txt', timeoutSec: 180 }, 200000);
    log('transfer-file', tf.json.success === true, tf.json.error || String(tf.json.output).slice(0, 70));
  } catch (e) { log('transfer-file', false, e.message); }

  try {
    const para = await cmd(machine, 'input_paragraph', { text: ('The quick brown fox jumps over the lazy dog. ').repeat(20), wpm: 900, addEnter: false, async: true });
    log('paragraph-start', para.json.success === true, para.json.error || 'async started');
    await new Promise(r => setTimeout(r, 2500));
    const st = await cmd(machine, 'stop_typing');
    log('stop-typing', st.json.success === true, st.json.error || String(st.json.output).slice(0, 30));
  } catch (e) { log('paragraph-start', false, e.message); }

  const passed = results.filter(Boolean).length;
  console.log(`\nSUMMARY: ${passed}/${results.length} passed`);
  process.exit(passed === results.length ? 0 : 2);
})();

