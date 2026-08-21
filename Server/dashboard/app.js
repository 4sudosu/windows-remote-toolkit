// ── helpers ─────────────────────────────────────────────────────────────
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

// ── device polling ───────────────────────────────────────────────────────
let monitorSearchTimer = null;

function loadMonitor() {
  const q = encodeURIComponent($('deviceSearch').value.trim());
  fetch(`/api/agents${q ? '?q=' + q : ''}`)
    .then(r => r.json())
    .then(agents => {
      const online = agents.filter(a => a.online).length;
      $('agentsHeader').textContent = agents.length ? `● ${online} online · ${agents.length} devices` : '● No devices connected';
      $('agentsSummary').textContent = agents.length
        ? `${agents.length} device(s) · ${online} online${q ? '  ·  filtered by "' + $('deviceSearch').value.trim() + '"' : ''}`
        : q ? 'No devices match that search.' : 'No devices registered yet.';
      const tbody = $('monitorBody');
      if (!agents.length) {
        tbody.innerHTML = `<tr><td colspan="7" class="empty-row">No devices registered.</td></tr>`;
        return;
      }
      tbody.innerHTML = agents.map(a => `
        <tr>
          <td style="font-weight:700;">${esc(a.hostname || a.machineName)}</td>
          <td style="font-family:Consolas,monospace;">${esc(a.ip) || '—'}</td>
          <td style="font-family:Consolas,monospace;">${esc(a.serial) || '—'}</td>
          <td style="font-family:Consolas,monospace;">${esc(a.version) || '—'}</td>
          <td>${esc(a.model) || '—'}</td>
          <td><span class="badge ${a.online ? 'badge-resolved' : 'badge-offline'}">${a.online ? '● Online' : '● Offline'}</span></td>
          <td style="text-align:right;white-space:nowrap;">
            ${a.online
              ? `<button class="btn-fix" onclick="openMonitor('${esc(a.machineName)}','${esc(a.hostname || a.machineName)}')">📸 Capture</button>`
              : '<span class="muted">—</span>'}
          </td>
        </tr>
      `).join('');
    })
    .catch(() => {});
}

$('deviceSearch').addEventListener('input', () => {
  clearTimeout(monitorSearchTimer);
  monitorSearchTimer = setTimeout(loadMonitor, 300);
});

// ── screen capture viewer ────────────────────────────────────────────────
let monitorPassword = sessionStorage.getItem('monitorPassword') || '';
let monitorTarget = null;
let monitorTimer = null;
let monitorImageB64 = null;

function monitorPasswordPrompt() {
  const p = prompt('Enter the admin password to capture screenshots:') || '';
  if (!p) return null;
  monitorPassword = p;
  sessionStorage.setItem('monitorPassword', p);
  return p;
}

function openMonitor(machineName, hostname) {
  monitorTarget = { machineName, hostname };
  $('monitorModalTitle').textContent = `📷 ${hostname}`;
  $('monitorPlaceholder').style.display = 'block';
  $('monitorImg').style.display = 'none';
  $('monitorCaptureInfo').textContent = '—';
  $('monitorRefreshSel').value = '0';
  $('monitorModal').style.display = 'flex';
}

function closeMonitorModal() {
  $('monitorModal').style.display = 'none';
  stopMonitorRefresh();
}

$('monitorModal').addEventListener('click', e => { if (e.target === $('monitorModal')) closeMonitorModal(); });

function captureMonitorNow() {
  if (!monitorTarget) return;
  if (!monitorPassword) {
    const p = monitorPasswordPrompt();
    if (!p) return;
  }
  $('monitorCaptureInfo').textContent = '📸 Capturing…';
  fetch(`/api/monitor/${encodeURIComponent(monitorTarget.machineName)}/screenshot`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password: monitorPassword })
  })
    .then(r => r.json().then(d => ({ ok: r.ok, d })))
    .then(({ ok, d }) => {
      if (!ok) {
        if (d.error && d.error.includes('password')) {
          monitorPassword = '';
          sessionStorage.removeItem('monitorPassword');
          const p = monitorPasswordPrompt();
          if (!p) return;
          return captureMonitorNow();
        }
        $('monitorCaptureInfo').textContent = '❌ ' + (d.error || 'Capture failed');
        $('monitorPlaceholder').style.display = 'block';
        $('monitorImg').style.display = 'none';
        return;
      }
      monitorImageB64 = d.image;
      const img = $('monitorImg');
      img.src = 'data:image/png;base64,' + d.image;
      $('monitorPlaceholder').style.display = 'none';
      img.style.display = 'block';
      $('monitorCaptureInfo').textContent = `Captured ${d.at ? new Date(d.at).toLocaleTimeString() : 'now'}`;
    })
    .catch(() => { $('monitorCaptureInfo').textContent = '❌ Could not reach the server.'; });
}

function scheduleMonitorRefresh() {
  stopMonitorRefresh();
  const secs = parseInt($('monitorRefreshSel').value, 10) || 0;
  if (secs > 0 && monitorTarget) monitorTimer = setInterval(captureMonitorNow, secs * 1000);
}

function stopMonitorRefresh() {
  if (monitorTimer) { clearInterval(monitorTimer); monitorTimer = null; }
}

$('monitorRefreshSel').addEventListener('change', scheduleMonitorRefresh);

async function copyMonitorImage() {
  let img = $('monitorImg');
  const hasImage = !!(monitorImageB64 || (img && img.src && img.src.startsWith('data:image/')));

  if (!hasImage) {
    $('monitorCaptureInfo').textContent = '📸 No screenshot yet — capturing now…';
    await captureMonitorNow();
    img = $('monitorImg');
  }

  let dataUrl = img && img.src && img.src.startsWith('data:image/')
    ? img.src
    : (monitorImageB64 ? 'data:image/png;base64,' + monitorImageB64 : null);

  if (!dataUrl) {
    $('monitorCaptureInfo').textContent = '⚠️ No screenshot available to copy';
    return;
  }

  if (!monitorImageB64 && dataUrl.startsWith('data:image/')) {
    monitorImageB64 = dataUrl.slice(dataUrl.indexOf(',') + 1);
  }

  try {
    if (navigator.clipboard && window.ClipboardItem) {
      let blob;
      if (monitorImageB64) {
        const bin = atob(monitorImageB64);
        const arr = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
        blob = new Blob([arr], { type: 'image/png' });
      } else {
        blob = await (await fetch(dataUrl)).blob();
      }
      await navigator.clipboard.write([new ClipboardItem({ 'image/png': blob })]);
      $('monitorCaptureInfo').textContent = '✅ Image copied — paste into an image-capable app';
      return;
    }
  } catch (e) { /* fall through */ }

  try {
    const prevAlt = img.alt;
    const prevTitle = img.title;
    img.alt = '';
    img.title = '';
    const range = document.createRange();
    range.selectNode(img);
    const sel = window.getSelection();
    sel.removeAllRanges();
    sel.addRange(range);
    const ok = document.execCommand('copy');
    sel.removeAllRanges();
    img.alt = prevAlt;
    img.title = prevTitle;
    if (ok) {
      $('monitorCaptureInfo').textContent = '✅ Image copied — paste into Paint, Word, or a chat box';
      return;
    }
  } catch (e) { /* fall through */ }

  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(dataUrl);
      $('monitorCaptureInfo').textContent = '✅ Copied data URL — paste anywhere';
      return;
    }
  } catch (e) { /* fall through */ }

  try {
    const ta = document.createElement('textarea');
    ta.value = dataUrl;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.focus();
    ta.select();
    ta.setSelectionRange(0, ta.value.length);
    const ok = document.execCommand('copy');
    document.body.removeChild(ta);
    if (ok) {
      $('monitorCaptureInfo').textContent = '✅ Copied data URL — paste anywhere';
      return;
    }
  } catch (e) { /* fall through */ }

  $('monitorCaptureInfo').textContent = '⚠️ Copy failed — right-click the image and choose "Copy image"';
}

// ── start ────────────────────────────────────────────────────────────────
loadMonitor();
setInterval(loadMonitor, 5000);