/* TypeBitTorrent WebUI client.
 *
 * Vanilla ES2020 — the server ships this file with a CSP that forbids inline
 * script, so everything lives here. Every action maps 1:1 to an /api endpoint
 * that executes a real engine command; nothing is simulated client-side.
 */
'use strict';

const $ = (id) => document.getElementById(id);
const state = {
  session: null,
  torrents: [],
  detailHash: null,
  detail: null,
  detailTab: 'files',
  settings: null,
  makeResult: null,
  view: 'transfers',
};

/* ---------------------------------------------------------------- helpers */

function fmtBytes(v) {
  if (v === null || v === undefined || v < 0) return '—';
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB', 'PiB'];
  let x = Number(v), i = 0;
  while (x >= 1024 && i < units.length - 1) { x /= 1024; i++; }
  return (i === 0 ? x.toFixed(0) : x.toFixed(2)) + ' ' + units[i];
}
function fmtSpeed(v) { return (!v || v <= 0) ? '0 B/s' : fmtBytes(v) + '/s'; }
function fmtPercent(p) { return (Math.max(0, Math.min(1, p || 0)) * 100).toFixed(1) + '%'; }
function fmtEta(s) {
  if (s === null || s === undefined || s < 0) return '—';
  if (s === 0) return '0s';
  const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60);
  if (d) return d + 'd ' + h + 'h';
  if (h) return h + 'h ' + m + 'm';
  if (m) return m + 'm';
  return Math.floor(s) + 's';
}
function fmtDate(sec) {
  if (!sec) return '—';
  return new Date(sec * 1000).toLocaleString();
}
function esc(s) {
  return String(s === null || s === undefined ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}
function el(tag, attrs, children) {
  const node = document.createElement(tag);
  if (attrs) for (const [k, v] of Object.entries(attrs)) {
    if (k === 'class') node.className = v;
    else if (k === 'text') node.textContent = v;
    else if (k.startsWith('on') && typeof v === 'function') node.addEventListener(k.slice(2), v);
    else if (v !== null && v !== undefined) node.setAttribute(k, v);
  }
  (children || []).forEach((c) => node.appendChild(c));
  return node;
}
let toastTimer = null;
function toast(msg) {
  const t = $('toast');
  t.textContent = msg;
  t.classList.remove('hidden');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.add('hidden'), 3200);
}
function statusLabel(s) {
  return { FETCHING_METADATA: '获取元数据', DOWNLOADING: '下载中', SEEDING: '做种中', PAUSED: '已暂停', STOPPED: '已停止', FAILED: '出错' }[s] || s;
}
function statusClass(s) {
  return { DOWNLOADING: 'downloading', SEEDING: 'seeding', PAUSED: 'paused', FAILED: 'error' }[s] || '';
}

/* ------------------------------------------------------------------- api */

async function api(path, options) {
  const opts = options || {};
  const headers = Object.assign({ 'X-TypeBit': '1' }, opts.headers || {});
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(path, {
    method: opts.method || (opts.body !== undefined ? 'POST' : 'GET'),
    headers,
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
    credentials: 'same-origin',
  });
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (_) { data = { ok: false, message: text }; }
  if (res.status === 401) { state.session = Object.assign({}, state.session, { authenticated: false }); showGate(); }
  return { status: res.status, data };
}

/* ------------------------------------------------------------------- gate */

function showGate() {
  $('gate').classList.remove('hidden');
  $('app').classList.add('hidden');
}
function hideGate() {
  $('gate').classList.add('hidden');
  $('app').classList.remove('hidden');
}

$('gate-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const body = { username: $('gate-user').value, password: $('gate-pass').value };
  const { status, data } = await api('/api/login', { body });
  if (status === 200 && data && data.ok) {
    $('gate-error').textContent = '';
    $('gate-pass').value = '';
    await refreshSession();
    if (state.session.authenticated) { hideGate(); startPolling(); }
  } else {
    $('gate-error').textContent = (data && data.message) || '登录失败';
  }
});

$('logout').addEventListener('click', async () => {
  await api('/api/logout', { body: {} });
  state.session = { authenticated: false };
  showGate();
});

async function refreshSession() {
  const { data } = await api('/api/session');
  state.session = data || { authenticated: false };
  $('session-info').textContent = state.session.authenticated
    ? (state.session.username || 'admin') + ' · ' + state.session.platform
    : '未登录';
  return state.session;
}

/* ------------------------------------------------------------------ views */

$('tabs').addEventListener('click', (e) => {
  const btn = e.target.closest('button[data-view]');
  if (!btn) return;
  state.view = btn.dataset.view;
  document.querySelectorAll('#tabs button').forEach((b) => b.classList.toggle('active', b === btn));
  document.querySelectorAll('.view').forEach((v) => v.classList.toggle('active', v.id === 'view-' + state.view));
  if (state.view === 'settings' && state.settings) renderSettings(state.settings);
  if (state.view === 'stats') refreshStats();
  if (state.view === 'logs') refreshLogs();
});

$('detail-tabs').addEventListener('click', (e) => {
  const btn = e.target.closest('button[data-dtab]');
  if (!btn) return;
  state.detailTab = btn.dataset.dtab;
  document.querySelectorAll('#detail-tabs button').forEach((b) => b.classList.toggle('active', b === btn));
  ['files', 'peers', 'trackers', 'info', 'receipts'].forEach((t) => {
    $('dtab-' + t).classList.toggle('active', t === state.detailTab);
  });
});

/* --------------------------------------------------------------- transfers */

async function refreshState() {
  const { data } = await api('/api/state');
  if (!data) return;
  state.torrents = data.torrents || [];
  $('engine-state').textContent = data.engineRunning ? '引擎运行中' : '引擎未运行';
  $('engine-state').style.color = data.engineRunning ? 'var(--ok)' : 'var(--err)';
  $('rates').textContent =
    '↓ ' + fmtSpeed(data.downRate) + ' · ↑ ' + fmtSpeed(data.upRate) +
    ' · DHT ' + data.dhtNodes + ' · 端口 ' + (data.listenPort || '—') +
    (data.extIp ? ' · 外网 ' + data.extIp + ':' + data.extPort : '') +
    ' · 累计 ↓' + fmtBytes(data.totalDownloaded) + ' ↑' + fmtBytes(data.totalUploaded);
  renderTorrents();
  if (state.detailHash) await refreshDetail(state.detailHash);
}

function renderTorrents() {
  const filter = ($('filter').value || '').trim().toLowerCase();
  const rows = state.torrents.filter((t) =>
    !filter || t.name.toLowerCase().includes(filter) || t.hash.includes(filter));
  const tbody = $('torrents');
  tbody.textContent = '';
  $('empty-list').classList.toggle('hidden', rows.length !== 0);
  for (const t of rows) {
    const progress = el('div', { class: 'progress' }, [el('span', {})]);
    progress.firstChild.style.width = fmtPercent(t.progress);
    const actions = el('td', { class: 'row' }, [
      el('button', {
        class: 'ghost',
        text: t.status === 'PAUSED' ? '继续' : '暂停',
        onclick: (ev) => { ev.stopPropagation(); torrentAction(t.hash, t.status === 'PAUSED' ? 'resume' : 'pause'); },
      }),
      el('button', {
        class: 'ghost',
        text: '删除',
        onclick: (ev) => {
          ev.stopPropagation();
          if (confirm('删除任务「' + t.name + '」？（已下载的数据不会被删除）')) torrentAction(t.hash, 'remove');
        },
      }),
    ]);
    const tr = el('tr', {
      class: 'clickable',
      onclick: () => openDetail(t.hash),
    }, [
      el('td', { text: t.name }),
      el('td', {}, [el('span', { class: 'tag ' + statusClass(t.status), text: statusLabel(t.status) })]),
      el('td', { class: 'num' }, [progress, el('div', { class: 'muted', text: fmtPercent(t.progress) })]),
      el('td', { class: 'num', text: fmtBytes(t.selectedBytes || t.sizeBytes) }),
      el('td', { class: 'num', text: fmtSpeed(t.downSpeed) }),
      el('td', { class: 'num', text: fmtSpeed(t.upSpeed) }),
      el('td', { class: 'num', text: t.seeds + '/' + t.peers }),
      el('td', { class: 'num', text: t.isComplete ? '完成' : fmtEta(t.etaSeconds) }),
      actions,
    ]);
    tbody.appendChild(tr);
  }
}

async function torrentAction(hash, action) {
  const { data } = await api('/api/torrents/action', { body: { hash, action } });
  if (data && data.ok) toast(data.message || '已执行'); else toast((data && data.message) || '操作失败');
  await refreshState();
}

$('filter').addEventListener('input', renderTorrents);

/* add panel */
$('add-toggle').addEventListener('click', () => $('add-panel').classList.toggle('hidden'));
$('add-submit').addEventListener('click', async () => {
  const fileInput = $('add-file');
  const body = {
    magnet: $('add-magnet').value.trim(),
    savePath: $('add-path').value.trim(),
    paused: $('add-paused').checked,
    torrentBase64: '',
    fileName: '',
  };
  if (fileInput.files && fileInput.files.length) {
    const file = fileInput.files[0];
    const buf = await file.arrayBuffer();
    body.torrentBase64 = base64FromBytes(new Uint8Array(buf));
    body.fileName = file.name;
  }
  if (!body.magnet && !body.torrentBase64) { $('add-msg').textContent = '请填写磁力链接或选择 .torrent 文件'; return; }
  const { data } = await api('/api/torrents/add', { body });
  $('add-msg').textContent = (data && data.message) || '';
  if (data && data.ok) {
    $('add-magnet').value = '';
    fileInput.value = '';
    toast('已添加');
    await refreshState();
  }
});

function base64FromBytes(bytes) {
  let s = '';
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    s += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
  }
  return btoa(s);
}
function bytesFromBase64(b64) {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/* ----------------------------------------------------------------- detail */

async function openDetail(hash) {
  state.detailHash = hash;
  state.detailTab = 'files';
  $('detail').classList.remove('hidden');
  document.querySelectorAll('#detail-tabs button').forEach((b) =>
    b.classList.toggle('active', b.dataset.dtab === 'files'));
  ['files', 'peers', 'trackers', 'info', 'receipts'].forEach((t) => $('dtab-' + t).classList.toggle('active', t === 'files'));
  await refreshDetail(hash);
  $('detail').scrollIntoView({ behavior: 'smooth', block: 'start' });
}
$('detail-close').addEventListener('click', () => {
  state.detailHash = null;
  state.detail = null;
  $('detail').classList.add('hidden');
});

async function refreshDetail(hash) {
  const { data } = await api('/api/detail?hash=' + encodeURIComponent(hash));
  if (!data || !data.hash) return;
  state.detail = data;
  $('detail-name').textContent = data.name;
  $('files-summary').textContent = data.files.length + ' 个文件 · ' + fmtBytes(data.sizeBytes) +
    ' · 分块 ' + data.pieceCount + ' × ' + fmtBytes(data.pieceLength);
  renderDetailFiles(data);
  renderDetailPeers(data);
  renderDetailTrackers(data);
  renderDetailInfo(data);
  renderDetailReceipts(data);
}

function renderDetailFiles(data) {
  const tbody = $('detail-files');
  tbody.textContent = '';
  for (const f of data.files) {
    const select = el('select', {
      onchange: () => setPriority(f.index, Number(select.value)),
    }, ['0', '1', '2'].map((v) =>
      el('option', { value: v, text: { 0: '不下载', 1: '正常', 2: '高优先级' }[v], selected: String(f.priority) === v ? 'selected' : null })));
    tbody.appendChild(el('tr', {}, [
      el('td', { text: f.path }),
      el('td', { class: 'num', text: fmtBytes(f.length) }),
      el('td', {}, [select]),
      el('td', {}, [el('button', {
        class: 'ghost',
        text: '重命名',
        onclick: async () => {
          const name = prompt('新的文件名（相对路径）', f.path);
          if (!name) return;
          const { data: r } = await api('/api/torrents/rename', { body: { hash: data.hash, name, fileIndex: f.index } });
          toast((r && r.message) || '');
          refreshDetail(data.hash);
        },
      })]),
    ]));
  }
}

async function setPriority(index, priority) {
  const hash = state.detailHash;
  if (!hash) return;
  const priorities = {};
  priorities[String(index)] = priority;
  const { data } = await api('/api/torrents/priorities', { body: { hash, priorities } });
  toast((data && data.message) || '');
  setTimeout(() => refreshDetail(hash), 300);
}

$('bulk-prio').addEventListener('change', async (e) => {
  const value = e.target.value;
  e.target.value = '';
  if (value === '' || !state.detail) return;
  const priorities = {};
  state.detail.files.forEach((f) => { priorities[String(f.index)] = Number(value); });
  const { data } = await api('/api/torrents/priorities', { body: { hash: state.detailHash, priorities } });
  toast((data && data.message) || '');
  setTimeout(() => refreshDetail(state.detailHash), 300);
});

function renderDetailPeers(data) {
  const tbody = $('detail-peers');
  tbody.textContent = '';
  $('peers-empty').classList.toggle('hidden', data.peers.length !== 0);
  const phases = ['连接中', '握手中', '已连接', '已断开'];
  for (const p of data.peers) {
    tbody.appendChild(el('tr', {}, [
      el('td', { text: p.addr }),
      el('td', { text: p.client || '未知' }),
      el('td', { text: p.cc || '—' }),
      el('td', { text: (phases[p.phase] || '—') + (p.seed ? ' · 做种' : '') }),
      el('td', { class: 'num', text: fmtSpeed(p.down) }),
      el('td', { class: 'num', text: fmtSpeed(p.up) }),
      el('td', { class: 'num', text: String(p.inflight) }),
    ]));
  }
}

function renderDetailTrackers(data) {
  const tbody = $('detail-trackers');
  tbody.textContent = '';
  for (const t of data.trackers) {
    tbody.appendChild(el('tr', {}, [
      el('td', { text: t.url }),
      el('td', { text: t.status }),
      el('td', { class: 'num', text: String(t.seeds) }),
      el('td', { class: 'num', text: String(t.leeches) }),
      el('td', {}, [el('button', {
        class: 'ghost',
        text: '移除',
        onclick: async () => {
          const { data: r } = await api('/api/torrents/trackers', { body: { hash: data.hash, remove: t.url } });
          toast((r && r.message) || '');
          refreshDetail(data.hash);
        },
      })]),
    ]));
  }
}

$('tracker-add').addEventListener('click', async () => {
  const url = $('tracker-url').value.trim();
  if (!url || !state.detailHash) return;
  const { data } = await api('/api/torrents/trackers', { body: { hash: state.detailHash, add: url } });
  $('tracker-url').value = '';
  toast((data && data.message) || '');
  refreshDetail(state.detailHash);
});

$('rename-torrent-btn').addEventListener('click', async () => {
  const name = $('rename-torrent').value.trim();
  if (!name || !state.detailHash) return;
  const { data } = await api('/api/torrents/rename', { body: { hash: state.detailHash, name } });
  toast((data && data.message) || '');
  $('rename-torrent').value = '';
  refreshState();
});

function renderDetailInfo(data) {
  const box = $('detail-info');
  box.textContent = '';
  const rows = [
    ['信息哈希', data.hash], ['保存目录', data.saveDir], ['类型', data.kind],
    ['总大小', fmtBytes(data.sizeBytes)], ['分块', data.pieceCount + ' × ' + fmtBytes(data.pieceLength)],
    ['已验证分块', String(data.havePieces)], ['私有种子', data.isPrivate ? '是' : '否'],
    ['创建者', data.createdBy || '—'], ['创建时间', fmtDate(data.createdAt)], ['注释', data.comment || '—'],
  ];
  for (const [k, v] of rows) {
    box.appendChild(el('div', { class: 'item' }, [el('span', { class: 'k', text: k }), el('span', { class: 'v', text: v })]));
  }
}

function renderDetailReceipts(data) {
  const list = $('receipt-list');
  list.textContent = '';
  for (const r of data.receipts) {
    list.appendChild(el('li', {}, [
      el('span', { text: r.name }),
      el('span', { class: 'muted', text: ' ' + fmtBytes(r.bytes) }),
    ]));
  }
}

$('receipt-export').addEventListener('click', async () => {
  if (!state.detailHash) return;
  const { data } = await api('/api/torrents/receipt?hash=' + encodeURIComponent(state.detailHash), { body: {} });
  $('receipt-msg').textContent = (data && data.message) || '';
  refreshDetail(state.detailHash);
});

/* ----------------------------------------------------------------- create */

function updatePieceHint() {
  // Auto piece size is computed server-side; the hint here just explains it.
  const manual = $('mk-piece').value;
  $('mk-msg').textContent = manual === '0'
    ? '分块自动：目标约 2000 块（16 KiB ~ 16 MiB）'
    : '手工分块：' + fmtBytes(Number(manual)) + '（大文件更省校验开销）';
}
$('mk-piece').addEventListener('change', updatePieceHint);
updatePieceHint();

let makeTimer = null;

$('mk-run').addEventListener('click', async () => {
  const body = {
    directory: $('mk-dir').value.trim(),
    files: [],
    name: $('mk-name').value.trim(),
    pieceLength: Number($('mk-piece').value) || 0,
    trackers: $('mk-trackers').value.split('\n').map((s) => s.trim()).filter(Boolean),
    comment: $('mk-comment').value.trim(),
    source: $('mk-source').value.trim(),
    isPrivate: $('mk-private').checked,
  };
  if (!body.directory) { $('mk-msg').textContent = '请填写服务器上的目录路径（必须存在）'; return; }
  $('mk-run').disabled = true;
  $('mk-cancel').disabled = false;
  $('mk-add').disabled = true;
  $('mk-save').disabled = true;
  $('mk-progress-wrap').classList.remove('hidden');
  $('mk-result').classList.add('hidden');
  $('mk-msg').textContent = '正在计算分块校验…';
  startMakePolling();
  try {
    const { data } = await api('/api/make-torrent', { body });
    state.makeResult = data;
    if (data && data.ok) {
      $('mk-msg').textContent = data.message;
      $('mk-add').disabled = false;
      $('mk-save').disabled = false;
      const box = $('mk-result');
      box.textContent = '';
      box.classList.remove('hidden');
      const rows = [
        ['名称', data.name], ['信息哈希', data.hash], ['大小', fmtBytes(data.sizeBytes)],
        ['分块', data.pieceCount + ' × ' + fmtBytes(data.pieceLength)], ['文件数', String(data.fileCount)],
      ];
      for (const [k, v] of rows) {
        box.appendChild(el('div', { class: 'item' }, [el('span', { class: 'k', text: k }), el('span', { class: 'v', text: v })]));
      }
      toast('制作完成');
    } else {
      $('mk-msg').textContent = (data && data.message) || '制作失败';
    }
  } finally {
    stopMakePolling();
    $('mk-run').disabled = false;
    $('mk-cancel').disabled = true;
  }
});

function startMakePolling() {
  stopMakePolling();
  makeTimer = setInterval(async () => {
    const { data } = await api('/api/make-torrent/progress');
    if (!data) return;
    const frac = data.totalBytes > 0 ? data.doneBytes / data.totalBytes : 0;
    $('mk-bar').style.width = (frac * 100).toFixed(1) + '%';
    $('mk-progress-text').textContent = fmtBytes(data.doneBytes) + ' / ' + fmtBytes(data.totalBytes) +
      ' (' + (frac * 100).toFixed(0) + '%)' + (data.cancelled ? ' · 正在取消' : '');
  }, 250);
}
function stopMakePolling() {
  if (makeTimer) { clearInterval(makeTimer); makeTimer = null; }
}

$('mk-cancel').addEventListener('click', async () => {
  const { data } = await api('/api/make-torrent/cancel', { body: {} });
  toast((data && data.message) || '');
});

$('mk-add').addEventListener('click', async () => {
  if (!state.makeResult || !state.makeResult.base64) return;
  const { data } = await api('/api/torrents/add', {
    body: { torrentBase64: state.makeResult.base64, fileName: state.makeResult.name, paused: false },
  });
  toast((data && data.message) || '');
  await refreshState();
});

$('mk-save').addEventListener('click', () => {
  if (!state.makeResult || !state.makeResult.base64) return;
  const bytes = bytesFromBase64(state.makeResult.base64);
  const blob = new Blob([bytes], { type: 'application/x-bittorrent' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = state.makeResult.name || 'new.torrent';
  document.body.appendChild(a);
  a.click();
  URL.revokeObjectURL(a.href);
  a.remove();
});

/* ----------------------------------------------------------------- search */

$('search-run').addEventListener('click', async () => {
  const q = $('search-q').value.trim();
  if (!q) return;
  $('search-msg').textContent = '搜索中…（引擎顺序执行，可能需要十几秒）';
  $('search-results').textContent = '';
  const { data } = await api('/api/search?q=' + encodeURIComponent(q));
  $('search-msg').textContent = (data && data.message) || '';
  if (!data || !data.results) return;
  for (const r of data.results) {
    $('search-results').appendChild(el('tr', {}, [
      el('td', { text: r.title }),
      el('td', { text: r.source || '—' }),
      el('td', { class: 'num', text: r.sizeText || '—' }),
      el('td', { class: 'num', text: String(r.seeds) }),
      el('td', { class: 'num', text: String(r.leeches) }),
      el('td', {}, [el('button', {
        class: 'primary',
        text: '添加',
        onclick: async () => {
          const { data: res } = await api('/api/torrents/add', { body: { magnet: r.magnet } });
          toast((res && res.message) || '');
          await refreshState();
        },
      })]),
    ]));
  }
});

/* -------------------------------------------------------------------- rss */

async function refreshRss() {
  const { data: feeds } = await api('/api/rss');
  const list = $('rss-feeds');
  list.textContent = '';
  for (const f of (feeds || [])) {
    list.appendChild(el('li', {}, [
      el('span', { text: f.title || f.url }),
      el('span', { class: 'muted', text: f.error ? '（' + f.error + '）' : ' · ' + f.items + ' 条' }),
      el('button', {
        class: 'ghost',
        text: '移除',
        onclick: async () => {
          await api('/api/rss', { body: { action: 'remove', url: f.url } });
          refreshRss();
        },
      }),
    ]));
  }
  const { data: items } = await api('/api/rss/items');
  const tbody = $('rss-items');
  tbody.textContent = '';
  for (const it of (items || []).slice(0, 200)) {
    tbody.appendChild(el('tr', {}, [
      el('td', {}, [it.link ? el('a', { href: it.link, text: it.title, target: '_blank', rel: 'noreferrer noopener' }) : el('span', { text: it.title })]),
      el('td', { text: it.feed }),
      el('td', { text: it.pubDate || '—' }),
      el('td', {}, it.magnet ? [el('button', {
        class: 'primary',
        text: '添加磁力',
        onclick: async () => {
          const { data: res } = await api('/api/torrents/add', { body: { magnet: it.magnet } });
          toast((res && res.message) || '');
          await refreshState();
        },
      })] : [el('span', { class: 'muted', text: '无磁力' })]),
    ]));
  }
}

$('rss-add').addEventListener('click', async () => {
  const url = $('rss-url').value.trim();
  if (!url) return;
  const { data } = await api('/api/rss', { body: { action: 'add', url } });
  $('rss-msg').textContent = (data && data.message) || '';
  $('rss-url').value = '';
  refreshRss();
});
$('rss-refresh').addEventListener('click', refreshRss);

/* ------------------------------------------------------------------ stats */

async function refreshStats() {
  const { data } = await api('/api/state');
  const { data: stats } = await api('/api/stats');
  const cards = $('stat-cards');
  cards.textContent = '';
  if (data) {
    const items = [
      ['下载速率', fmtSpeed(data.downRate)], ['上传速率', fmtSpeed(data.upRate)],
      ['本次下载', fmtBytes(data.totalDownloaded)], ['本次上传', fmtBytes(data.totalUploaded)],
      ['任务数', String(data.torrents.length)], ['DHT 节点', String(data.dhtNodes)],
      ['LSD 收发', data.lsdSent + ' / ' + data.lsdRecv], ['LSD 发现', String(data.lsdPeers)],
      ['防吸血计数', String(data.antiLeechCount)],
    ];
    for (const [k, v] of items) {
      cards.appendChild(el('div', { class: 'card' }, [el('div', { class: 'k', text: k }), el('div', { class: 'v', text: v })]));
    }
    const net = $('net-stats');
    net.textContent = '';
    const rows = [
      ['监听端口', String(data.listenPort || '—')],
      ['外网地址', data.extIp ? data.extIp + ':' + data.extPort : '—'],
      ['UPnP/NAT-PMP', data.portMapPhase + (data.portMapPort ? ' · 端口 ' + data.portMapPort : '')],
      ['Peer ID', data.peerId || '—'],
      ['最近错误', data.lastError || '—'],
      ['防吸血客户端', (data.antiLeechClients || []).join('、') || '—'],
    ];
    for (const [k, v] of rows) {
      net.appendChild(el('div', { class: 'item' }, [el('span', { class: 'k', text: k }), el('span', { class: 'v', text: v })]));
    }
  }
  const box = $('engine-stats');
  box.textContent = '';
  if (stats) {
    for (const [k, v] of Object.entries(stats)) {
      box.appendChild(el('div', { class: 'item' }, [el('span', { class: 'k', text: k }), el('span', { class: 'v', text: String(v) })]));
    }
  }
}

/* --------------------------------------------------------------- settings */

async function loadSettings() {
  const { data } = await api('/api/settings');
  state.settings = data;
  renderSettings(data);
}

const SETTING_SECTIONS = [
  {
    title: '保存 / 下载', key: 'downloads',
    fields: [
      ['defaultSavePath', 'text', '默认保存目录'],
      ['addTorrentsInPause', 'bool', '添加后暂停'],
      ['preAllocateDisk', 'bool', '预分配磁盘空间'],
      ['maxActiveDownloads', 'num', '最大同时下载数'],
      ['maxActiveUploads', 'num', '最大同时做种数'],
      ['maxActiveTorrents', 'num', '最大活动任务数'],
    ],
  },
  {
    title: '连接', key: 'connection',
    fields: [
      ['port', 'num', '监听端口（TCP）'],
      ['randomPort', 'bool', '随机端口'],
      ['upnpEnabled', 'bool', '启用 UPnP / NAT-PMP'],
      ['dhtEnabled', 'bool', '启用 DHT'],
      ['pexEnabled', 'bool', '启用 PEX'],
      ['lsdEnabled', 'bool', '启用 LSD 局域网发现'],
      ['encryptionMode', 'num', '加密模式（0 关 1 允许 2 强制）'],
    ],
  },
  {
    title: '速度', key: 'speed',
    fields: [
      ['globalDownLimitKib', 'num', '全局下载限速 (KiB/s, 0=不限)'],
      ['globalUpLimitKib', 'num', '全局上传限速 (KiB/s, 0=不限)'],
    ],
  },
  {
    title: 'BitTorrent', key: 'bitTorrent',
    fields: [
      ['maxPeersPerTorrent', 'num', '每任务最大连接数'],
      ['requestPipeline', 'num', '请求流水线深度'],
      ['antiLeechEnabled', 'bool', '启用反吸血'],
      ['extraTrackers', 'text', '附加 Tracker 列表（每行一条）'],
    ],
  },
  {
    title: 'WebUI', key: 'webUi',
    fields: [
      ['username', 'text', '用户名'],
      ['port', 'num', 'WebUI 端口'],
      ['remoteAccess', 'bool', '允许局域网访问（桌面版，重启生效）'],
      ['sessionTimeoutMinutes', 'num', '会话超时（分钟）'],
      ['maxAuthFailCount', 'num', '允许的连续登录失败次数'],
      ['csrfProtection', 'bool', 'CSRF 防护'],
      ['clickjackingProtection', 'bool', '点击劫持防护'],
      ['localHostAuth', 'bool', '本机跳过验证'],
      ['hostHeaderValidation', 'bool', '校验 Host 头'],
      ['reverseProxyEnabled', 'bool', '位于反向代理之后'],
    ],
  },
];

function renderSettings(settings) {
  const form = $('settings-form');
  form.textContent = '';
  if (!settings) return;
  for (const section of SETTING_SECTIONS) {
    const obj = settings[section.key] || {};
    const fs = el('fieldset', {}, [el('legend', { text: section.title })]);
    for (const [field, kind, label] of section.fields) {
      const value = obj[field];
      if (kind === 'bool') {
        const cb = el('input', { type: 'checkbox' });
        cb.checked = !!value;
        cb.addEventListener('change', () => { settings[section.key][field] = cb.checked; });
        fs.appendChild(el('label', { class: 'row' },
          [cb, el('span', { text: label })]));
      } else {
        const input = el('input', { value: value === undefined || value === null ? '' : String(value) });
        input.addEventListener('change', () => {
          settings[section.key][field] = kind === 'num' ? Number(input.value) || 0 : input.value;
        });
        fs.appendChild(el('label', {}, [el('span', { text: label }), input]));
      }
    }
    form.appendChild(fs);
  }
  // Password change: hashed server-side, never stored in the page.
  const pw = el('fieldset', {}, [el('legend', { text: '修改 WebUI 密码' })]);
  const pwInput = el('input', { type: 'password', placeholder: '新密码（留空不改）' });
  // The plaintext is sent once over the (reverse-proxied or LAN) connection
  // and hashed with PBKDF2 on the server — never written to the settings file.
  const pwSave = el('button', {
    text: '设置新密码',
    onclick: async () => {
      const value = pwInput.value;
      if (value.length < 8) {
        $('settings-msg').textContent = '密码至少 8 位';
        return;
      }
      const { data } = await api('/api/settings/password', { body: { password: value } });
      $('settings-msg').textContent = (data && data.message) || '';
      pwInput.value = '';
      // All sessions (including this one) were invalidated server-side.
      state.session = { authenticated: false };
      showGate();
    },
  });
  pw.appendChild(el('label', {}, [el('span', { text: '新密码' }), pwInput]));
  pw.appendChild(pwSave);
  form.appendChild(pw);
}

$('settings-save').addEventListener('click', async () => {
  if (!state.settings) return;
  const { data } = await api('/api/settings', { body: state.settings });
  $('settings-msg').textContent = (data && data.message) || '';
  toast('设置已保存');
});

/* ------------------------------------------------------------------- logs */

async function refreshLogs() {
  const { data } = await api('/api/logs?after=0');
  if (!Array.isArray(data)) return;
  $('logs').textContent = data.slice(-500).map((l) => '[' + l.level + '] ' + l.message).join('\n');
}

/* ------------------------------------------------------------------ boot */

let pollTimer = null;
function startPolling() {
  if (pollTimer) return;
  refreshState();
  refreshSession();
  loadSettings();
  refreshRss();
  pollTimer = setInterval(() => {
    if (state.view === 'transfers') refreshState(); else refreshSession();
  }, 1000);
}

(async function boot() {
  const session = await refreshSession();
  if (session.authenticated) {
    hideGate();
    startPolling();
  } else {
    showGate();
    $('gate-hint').textContent = session.passwordRequired
      ? '请输入 WebUI 账户密码'
      : 'WebUI 尚未设置密码：请在服务器上用 --password 或 TYPEBIT_PASSWORD 设置后重启。';
  }
})();
