/* ══════════════════════════════════════════════════════
   LUMEN · 流明   app.js
   ══════════════════════════════════════════════════════ */
(() => {
'use strict';

const $ = s => document.querySelector(s);
const $$ = s => [...document.querySelectorAll(s)];
/* 后端接口：全部走本地 HTTP（不再依赖 WebView 的 JS 桥） */
const _apiCache = {};
const api = () => new Proxy(_apiCache, {
  get(_, name) {
    if (typeof name !== 'string') return undefined;
    if (!(name in _apiCache)) {
      _apiCache[name] = (...args) => fetch('/api?name=' + encodeURIComponent(name), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(args),
      }).then(r => r.json()).catch(() => null);
    }
    return _apiCache[name];
  },
});

/* 运行时错误收集（开发排查用） */
window.__errs = [];
function report(payload) {
  if (location.search.indexOf('selftest') < 0) return;
  try {
    const q = encodeURIComponent(JSON.stringify(payload));
    fetch('/report?d=' + q, { cache: 'no-store' });
  } catch (e) {}
}
function _reportErr(msg) {
  window.__errs.push(msg);
  report({ jsError: msg });
}
window.addEventListener('error', e => {
  _reportErr((e.message || 'err') + ' @' + (e.filename || '').split('/').pop() + ':' + e.lineno);
});
window.addEventListener('unhandledrejection', e => {
  _reportErr('promise: ' + (e.reason && e.reason.message || e.reason));
});

/* 演示模式（浏览器里直接打开 index.html?demo=1 可预览全套界面） */
const QS = new URLSearchParams(location.search);
const DEMO = QS.has('demo');
const SELFTEST = QS.has('selftest');


/* 脚本一加载就 ping 一下，便于确认页面在宿主里真的执行了 */
report({ ping: 'script-loaded' });

/* 自检：把运行结果回报给宿主 */
async function reportSelfTest(extra) {
  const out = Object.assign({
    bridge: true,
    apiFns: 0,
    errs: window.__errs,
  }, extra || {});
  try {
    const AC = window.AudioContext || window.webkitAudioContext;
    const ac = new AC();
    out.sampleRate = ac.sampleRate;
    out.audioState = ac.state;
    try { ac.createScriptProcessor(1024, 2, 2); out.scriptProc = true; }
    catch (e) { out.scriptProc = false; }
    ac.close();
  } catch (e) { out.audioErr = e.message; }
  const el = Engine.audio;
  out.preservesPitch = ('preservesPitch' in el);
  report(out);
}
const DEMO_TRACKS = [
  { path: 'demo:1', name: 'moonlight.mp3', title: '月光落在琴键上',
    artist: '浮光 Ensemble', album: 'Nocturne I', duration: 254, hasCover: false,
    grad: 'linear-gradient(150deg,#141021 0%,#2e2145 38%,#6b4a72 68%,#c19a72 100%)' },
  { path: 'demo:2', name: 'tide.mp3', title: '潮汐与旧信',
    artist: '林间电台', album: '海边的房间', duration: 213, hasCover: false,
    grad: 'linear-gradient(150deg,#0e1620 0%,#20384a 45%,#4a7a8c 75%,#a8c8c8 100%)' },
  { path: 'demo:3', name: 'ember.mp3', title: '琥珀色的黄昏',
    artist: 'Vesper', album: 'Slow Light', duration: 298, hasCover: false,
    grad: 'linear-gradient(150deg,#1c1410 0%,#402818 40%,#8a5a38 70%,#e0b070 100%)' },
  { path: 'demo:4', name: 'glass.mp3', title: '玻璃与霜',
    artist: '浮光 Ensemble', album: 'Nocturne II', duration: 187, hasCover: false,
    grad: 'linear-gradient(150deg,#0c1116 0%,#1e2c34 42%,#4a6a72 72%,#b8d0d4 100%)' },
];
const DEMO_LRC = `[00:03.00]夜色把琴键染成深蓝
[00:09.20]指尖落下 风就停了
[00:15.60]你说月亮是会呼吸的
[00:22.00]每一次潮汐都在数着心跳
[00:28.40]我把没说完的话
[00:33.80]折成一小片光
[00:39.20]放进你衬衫的口袋里
[00:45.60]等某个失眠的夜晚
[00:51.80]它自己会亮起来
[00:58.20]像一句迟到的晚安
[01:04.60]窗外的风铃替我回答
[01:11.00]说这样的夜 还很长
[01:17.40]月光落在琴键上
[01:23.80]落成一条安静的河
[01:30.20]我们坐在河的这边
[01:36.60]听时间慢慢地流过
[01:43.00]如果明天来得太快
[01:49.40]就把这一小节留下
[01:55.80]让它在没有人的房间里
[02:02.20]轻轻地 再响一遍`;


/* ───────────── 状态 ───────────── */
const S = {
  tracks: [],
  shown: [],
  idx: -1,
  playing: false,
  volume: 82,
  muted: false,
  speed: 1,
  pitch: 0,
  eq: [0, 0, 0],
  eqPreset: 'flat',
  width: 50,
  mode: 'order',          // order | shuffle | one
  shuffleBag: [],
  selSet: new Set(),
  lastSel: null,
  multi: false,          // 多选模式：单击=切换选中，双击可进入
  lyrics: [],
  lyrIndex: -1,
  lyrOn: true,
  vizOn: true,
  crossfade: true,
  folder: '',
  lyricsCache: {},
  queueOpen: false,
  tuneOpen: false,
  playlists: [],
  view: null,          // null = 全部音乐，否则歌单 id
  dragFrom: null,
  dragTo: null,
  // 设置页
  wallMode: 'cycle',   // cycle | single | custom
  wallIdx: 1,
  wallOn: false,       // 手机版默认不显示壁纸，用户主动选过才开
  wallBright: 110,     // 壁纸亮度 %
  wallBlur: 0,         // 壁纸模糊 px
  downloadDir: '',    // 搜索下载保存目录（空=跟随曲库目录）
  lyrColor: '',        // 歌词自定义颜色（空=跟随主题）
  lyrSize: 0,          // 歌词字号 px（0=自动）
  plDesc: false,       // 歌单倒序显示
  autoDownloadMissing: false,  // 同步时自动下载歌单里本机没有的歌
  customWallUrl: null,
  sleepMode: 'off',    // off | songs | minutes
  sleepVal: 30,
  sleepRemain: 0,
  setOpen: false,
  theme: 'dark',       // dark | light
  lyricOverlay: false, // 安卓悬浮歌词
  wxRoot: '',          // 微信文件根目录（手动指定过才有值）
  recent: [],          // 最近播放（localStorage 持久化）
};

/* 最近播放：启动时从 localStorage 恢复 */
try { S.recent = JSON.parse(localStorage.getItem('luvsic_recent') || '[]') || []; } catch (e) { S.recent = []; }

/* 记录一次播放（去重移到最前，最多 100 条） */
function recordRecent(t) {
  if (!t || !t.path) return;
  try {
    const arr = (S.recent || []).filter(x => x.path !== t.path);
    arr.unshift({ path: t.path, title: t.title || t.name || '', artist: t.artist || '', ts: Date.now() });
    S.recent = arr.slice(0, 100);
    localStorage.setItem('luvsic_recent', JSON.stringify(S.recent));
  } catch (e) {}
}

/* 拼音首字母：pinyin_dict_firstletter.all 按 Unicode(0x4E00 起) 顺序存每个汉字的首字母。
   「青花瓷」-> "qhc"，搜索框输入纯字母时也能搜到。 */
const PY = (() => {
  let all = '';
  try {
    if (typeof pinyin_dict_firstletter !== 'undefined' && pinyin_dict_firstletter.all) {
      all = pinyin_dict_firstletter.all;
    }
  } catch (e) {}
  function first(ch) {
    const idx = ch.charCodeAt(0) - 0x4E00;
    if (idx < 0 || idx >= all.length) return '';
    return all.charAt(idx).toLowerCase();
  }
  function ofText(str) {
    let out = '';
    for (const ch of String(str || '')) {
      if (/[a-z0-9]/i.test(ch)) { out += ch.toLowerCase(); continue; }
      const f = first(ch);
      if (f) out += f;
    }
    return out;
  }
  return { first, ofText };
})();

/* ───────────── 工具 ───────────── */
/* 右键菜单统一入口：电脑用右键，手机用长按 0.6 秒，同一套菜单。
   opts.onDragStart(touchPoint) 返回 true 时，长按不弹菜单而是进入拖拽排序模式，
   后续由 opts.onDragMove(e) / opts.onDragEnd() 接管。 */
function bindCtx(el, onCtx, opts) {
  el.addEventListener('contextmenu', e => {
    e.preventDefault();
    onCtx(e.clientX, e.clientY);
  });
  if (!document.body.classList.contains('android')) return;
  let timer = null, startX = 0, startY = 0, dragging = false;
  el.addEventListener('touchstart', e => {
    if (e.touches.length !== 1) return;
    const tp = e.touches[0];
    startX = tp.clientX;
    startY = tp.clientY;
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => {
      timer = null;
      try { if (navigator.vibrate) navigator.vibrate(18); } catch (err) {}
      el.__lpFired = true;
      if (opts && opts.onDragStart && opts.onDragStart(tp)) {
        dragging = true;
        return;
      }
      onCtx(tp.clientX, tp.clientY);
    }, 600);
  }, { passive: true });
  el.addEventListener('touchmove', e => {
    if (dragging) {
      if (e.cancelable) e.preventDefault();
      try { opts.onDragMove(e); } catch (err) {}
      return;
    }
    const tp = e.touches[0];
    if (tp && timer && (Math.abs(tp.clientY - startY) > 8 || Math.abs(tp.clientX - startX) > 8)) {
      clearTimeout(timer);
      timer = null;
    }
  }, { passive: false });
  const stop = () => {
    if (timer) { clearTimeout(timer); timer = null; }
    if (dragging) {
      dragging = false;
      try { opts.onDragEnd(); } catch (err) {}
    }
  };
  el.addEventListener('touchend', stop);
  el.addEventListener('touchcancel', stop);
}
/* 长按已弹菜单后，紧随其后的 click 要吃掉，避免误触播放/切歌 */
function longPressConsumed(el) {
  if (el.__lpFired) { el.__lpFired = false; return true; }
  return false;
}
/* 拖动排序时给目标行画一条插入线 */
function markDropLine(to) {
  const list = document.getElementById('libList');
  if (!list) return;
  [...list.children].forEach((node, k) => node.classList.toggle('drop-line', k === to));
}
/* 拖动到列表上下边缘时自动滚动（长歌单也能拖到远处）；onTick 用于滚动后重算目标位置 */
let _dragScroll = null;
function autoScrollDuringDrag(clientY, listEl, onTick) {
  if (!listEl) return;
  const r = listEl.getBoundingClientRect();
  const EDGE = 90;
  let speed = 0;
  if (clientY < r.top + EDGE) speed = -Math.ceil((r.top + EDGE - clientY) / 5);
  else if (clientY > r.bottom - EDGE) speed = Math.ceil((clientY - (r.bottom - EDGE)) / 5);
  speed = Math.max(-24, Math.min(24, speed));
  if (onTick) {
    if (!_dragScroll) _dragScroll = { raf: 0, el: listEl, speed: 0, onTick: onTick };
    _dragScroll.onTick = onTick;
  }
  if (!speed) { stopAutoScroll(); return; }
  if (!_dragScroll) _dragScroll = { raf: 0, el: listEl, speed: speed, onTick: onTick };
  _dragScroll.el = listEl;
  _dragScroll.speed = speed;
  if (_dragScroll.raf) return;
  const tick = () => {
    if (!_dragScroll) return;
    _dragScroll.el.scrollTop += _dragScroll.speed;
    try { if (_dragScroll.onTick) _dragScroll.onTick(); } catch (e) {}
    _dragScroll.raf = requestAnimationFrame(tick);
  };
  _dragScroll.raf = requestAnimationFrame(tick);
}
function stopAutoScroll() {
  if (_dragScroll) {
    if (_dragScroll.raf) cancelAnimationFrame(_dragScroll.raf);
    _dragScroll = null;
  }
}
/* 手指/鼠标当前落在列表第几行（比按行高换算准，且滚动后自动跟着变） */
function rowIndexAt(clientY) {
  const list = document.getElementById('libList');
  if (!list) return -1;
  const rows = [...list.children];
  if (!rows.length) return -1;
  for (let k = 0; k < rows.length; k++) {
    const r = rows[k].getBoundingClientRect();
    if (clientY < r.bottom) return k;
  }
  return rows.length - 1;
}
/* 手机拖动排序：行跟着手指走，目标位置按「手指下的那一行」计算 */
function applySortDrag(el, st) {
  if (!st || !el) return;
  const listEl = document.getElementById('libList');
  const scrollDelta = listEl ? (listEl.scrollTop - st.startScroll) : 0;
  const eff = (st.lastY - st.startY) + scrollDelta;
  if (Math.abs(eff) > 4) st.moved = true;
  el.style.transform = 'translateY(' + eff + 'px)';
  const max = Math.max(0, S.shown.length - 1);
  const under = rowIndexAt(st.lastY);
  const to = under >= 0 ? under : st.from;
  st.to = Math.max(0, Math.min(max, to));
  markDropLine(st.to);
}
const fmt = t => {
  if (!isFinite(t) || t < 0) t = 0;
  const m = Math.floor(t / 60), s = Math.floor(t % 60);
  return `${m}:${String(s).padStart(2, '0')}`;
};
/* 好友备注本地缓存：设完备注立刻生效，不用等 Hub 同步 */
function friendNickMap() {
  try { return JSON.parse(localStorage.getItem('luvsic_friend_nicks') || '{}') || {}; }
  catch (e) { return {}; }
}
function setFriendNickCache(id, nick) {
  try {
    const m = friendNickMap();
    if (nick && String(nick).trim()) m[id] = String(nick).trim();
    else delete m[id];
    localStorage.setItem('luvsic_friend_nicks', JSON.stringify(m));
  } catch (e) {}
}
/* 全局时间格式化（好友页等模块也要用；Sync 模块内有同名局部版本互不影响） */
function fmtTime(ts) {
  if (!ts) return '—';
  const d = new Date(ts * 1000);
  const p = n => (n < 10 ? '0' + n : '' + n);
  return (d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
}
const esc = s => String(s || '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

let toastTimer = null;
function toast(msg, ms = 2000) {
  const el = $('#toast');
  el.textContent = msg;
  el.classList.add('on');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('on'), ms);
}

/* ══════════════════════════════════════════
   一、启动动画
   ══════════════════════════════════════════ */
const Boot = (() => {
  const canvas = $('#bootCanvas');
  const ctx = canvas.getContext('2d');
  let W = 0, H = 0, bars = [], raf = 0, t0 = 0, alive = true;

  const N = 72;

  function resize() {
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    W = canvas.width = innerWidth * dpr;
    H = canvas.height = innerHeight * dpr;
    canvas.style.width = innerWidth + 'px';
    canvas.style.height = innerHeight + 'px';
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    W = innerWidth; H = innerHeight;
    bars = [];
    const span = Math.min(W * 0.42, 420);
    for (let i = 0; i < N; i++) {
      const x = W / 2 - span / 2 + (span / (N - 1)) * i;
      bars.push({ x, h: 0, target: 0, seed: Math.random() * 100 });
    }
  }

  function frame(ts) {
    if (!alive) return;
    if (!t0) t0 = ts;
    const el = (ts - t0) / 1000;
    ctx.clearRect(0, 0, W, H);

    const cy = H / 2 + 84;
    // 频谱条
    for (const b of bars) {
      const life = Math.min(1, Math.max(0, (el - 0.35) / 1.4));
      b.target = (Math.sin(el * 2.1 + b.seed) * 0.5 + 0.5) *
                 (0.35 + 0.65 * Math.abs(Math.sin(el * 1.3 + b.seed * 0.7))) * 54 * life;
      b.h += (b.target - b.h) * 0.16;
      const h = Math.max(1, b.h);
      const dist = Math.abs(b.x - W / 2) / (W / 2);
      const a = (0.30 - dist * 0.22) * life;
      if (a <= 0.004) continue;
      const g = ctx.createLinearGradient(b.x, cy, b.x, cy - h);
      g.addColorStop(0, `rgba(156,140,234,${a * 0.30})`);
      g.addColorStop(0.55, `rgba(228,194,154,${a})`);
      g.addColorStop(1, `rgba(250,233,206,${a * 1.5})`);
      ctx.fillStyle = g;
      ctx.fillRect(b.x, cy - h, 1.4, h);
      ctx.fillRect(b.x, cy + 6, 1.4, h * 0.34);
    }

    // 地平线
    const lg = ctx.createLinearGradient(W / 2 - 260, 0, W / 2 + 260, 0);
    lg.addColorStop(0, 'rgba(228,194,154,0)');
    lg.addColorStop(0.5, `rgba(228,194,154,${0.16 * Math.min(1, el / 1.2)})`);
    lg.addColorStop(1, 'rgba(228,194,154,0)');
    ctx.fillStyle = lg;
    ctx.fillRect(W / 2 - 260, cy + 5, 520, 1);

    raf = requestAnimationFrame(frame);
  }

  const LOGS = [
    '· 载入音频引擎', '· 扫描本地曲库', '· 初始化声场',
    '· 连接歌词服务', '· 校准频率响应', '· 就绪'
  ];

  function start() {
    resize();
    addEventListener('resize', resize);
    raf = requestAnimationFrame(frame);

    const bar = $('#bootBar'), pct = $('#bootPct'), log = $('#bootLog');
    let p = 0, li = 0;

    const step = () => {
      if (!alive) return;
      const inc = 1.2 + Math.random() * 3.6;
      p = Math.min(100, p + inc);
      bar.style.width = p + '%';
      pct.textContent = String(Math.floor(p)).padStart(2, '0');
      const next = Math.min(LOGS.length - 1, Math.floor(p / 100 * LOGS.length));
      if (next !== li) { li = next; log.textContent = LOGS[li]; }
      if (p < 100) {
        setTimeout(step, 60 + Math.random() * 120);
      } else {
        log.textContent = LOGS[LOGS.length - 1];
        setTimeout(finish, 380);
      }
    };
    setTimeout(step, 620);
  }

  let done = false;
  function finish() {
    if (!done) {
      done = true;
      alive = false;
      cancelAnimationFrame(raf);
      $('#boot').classList.add('gone');
      $('#app').classList.add('in');
      setTimeout(() => { $('#boot').style.display = 'none'; }, 950);
      setTimeout(() => { if (S.tracks.length) Art.reveal(); }, 400);
    }
  }

  function bind() {
    $('#boot').addEventListener('click', () => {
      // 允许跳过，但至少 0.7s
      finish();
    });
  }

  return { start, bind, finish };
})();

/* ══════════════════════════════════════════
   二、音频引擎
   ══════════════════════════════════════════ */
const Engine = (() => {
  const audio = $('#audio');
  let ctx = null, src = null, shifter = null, eqNodes = [], gainNode = null,
      analyser = null, freqData = null, built = false, shifterOK = false;

  const EQ_FREQ = [160, 1000, 4000];
  const EQ_TYPE = ['lowshelf', 'peaking', 'highshelf'];

  /* ── 变调器：双延迟线交叉淡化（与倍速完全解耦）── */
  function buildPitchShifter(ac, semitones) {
    const BLOCK = 1024;
    const node = ac.createScriptProcessor(BLOCK, 2, 2);
    const W = 2048;                    // 单侧最大延迟（样本）
    const BUFS = 8192;
    const bufL = new Float32Array(BUFS);
    const bufR = new Float32Array(BUFS);
    let wp = 0, phase = 0;

    node.__setPitch = st => {
      node.__ratio = Math.pow(2, st / 12);
    };
    node.__ratio = Math.pow(2, semitones / 12);
    node.__width = 1;

    node.onaudioprocess = e => {
      const inL = e.inputBuffer.getChannelData(0);
      const inR = e.inputBuffer.numberOfChannels > 1
        ? e.inputBuffer.getChannelData(1) : inL;
      const outL = e.outputBuffer.getChannelData(0);
      const outR = e.outputBuffer.getChannelData(1);
      const ratio = node.__ratio || 1;
      const wid = node.__width || 1;
      const n = inL.length;

      // 无变调时直通（保留宽度处理）
      const active = Math.abs(ratio - 1) > 1e-4;

      for (let i = 0; i < n; i++) {
        const l = inL[i], r = inR[i];
        let oL, oR;

        if (active) {
          bufL[wp] = l; bufR[wp] = r;
          phase += (1 - ratio) / W;
          if (phase >= 1) phase -= 1;
          else if (phase < 0) phase += 1;

          const t1 = phase, t2 = (phase + 0.5) % 1;
          const g1 = Math.sin(Math.PI * t1), g2 = Math.sin(Math.PI * t2);

          const d1 = t1 * W, d2 = t2 * W;
          const p1 = (wp - d1 + BUFS * 2) % BUFS;
          const p2 = (wp - d2 + BUFS * 2) % BUFS;
          const i1 = p1 | 0, i2 = p2 | 0;
          const f1 = p1 - i1, f2 = p2 - i2;
          const n1 = (i1 + 1) % BUFS, n2 = (i2 + 1) % BUFS;

          const s1L = bufL[i1] + (bufL[n1] - bufL[i1]) * f1;
          const s1R = bufR[i1] + (bufR[n1] - bufR[i1]) * f1;
          const s2L = bufL[i2] + (bufL[n2] - bufL[i2]) * f2;
          const s2R = bufR[i2] + (bufR[n2] - bufR[i2]) * f2;

          oL = s1L * g1 + s2L * g2;
          oR = s1R * g1 + s2R * g2;
          wp = (wp + 1) % BUFS;
        } else {
          oL = l; oR = r;
        }

        // 立体声宽度（M/S）
        if (Math.abs(wid - 1) > 1e-3) {
          const m = (oL + oR) * 0.5, s = (oL - oR) * 0.5 * wid;
          oL = m + s; oR = m - s;
        }
        outL[i] = oL;
        outR[i] = oR;
      }
    };
    return node;
  }

  async function build() {
    if (built) return;
    built = true;
    const AC = window.AudioContext || window.webkitAudioContext;
    ctx = new AC({ latencyHint: 'playback' });

    src = ctx.createMediaElementSource(audio);

    // 优先 AudioWorklet：跑在音频线程，界面再忙也不会欠载出电音
    try {
      await ctx.audioWorklet.addModule('/worklet.js');
      shifter = new AudioWorkletNode(ctx, 'lumen-pitch', {
        numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [2],
      });
      shifter.port.postMessage({
        ratio: Math.pow(2, S.pitch / 12),
        width: S.width / 50,
      });
      shifter.worklet = true;
      shifterOK = true;
    } catch (err) {
      console.warn('AudioWorklet unavailable, fallback to ScriptProcessor', err);
      try {
        shifter = buildPitchShifter(ctx, S.pitch);
        shifter.worklet = false;
        shifterOK = true;
      } catch (err2) {
        shifterOK = false;
        console.warn('pitch shifter unavailable', err2);
      }
    }

    eqNodes = EQ_TYPE.map((type, i) => {
      const f = ctx.createBiquadFilter();
      f.type = type;
      f.frequency.value = EQ_FREQ[i];
      if (type === 'peaking') { f.Q.value = 0.9; f.gain.value = 0; }
      else f.gain.value = 0;
      return f;
    });

    gainNode = ctx.createGain();
    gainNode.gain.value = volumeToGain(S.volume);

    analyser = ctx.createAnalyser();
    analyser.fftSize = 1024;
    analyser.smoothingTimeConstant = 0.82;
    freqData = new Uint8Array(analyser.frequencyBinCount);

    let node = src;
    if (shifterOK) { node.connect(shifter); node = shifter; }
    for (const f of eqNodes) { node.connect(f); node = f; }
    node.connect(gainNode);
    gainNode.connect(analyser);
    analyser.connect(ctx.destination);

    applyEQ();
    if (shifterOK && !shifter.worklet) shifter.__width = S.width / 50;
    window.__audioWorklet = !!(shifterOK && shifter.worklet);
  }

  const volumeToGain = v => {
    const x = Math.max(0, Math.min(1, v / 100));
    return Math.pow(x, 1.75);
  };

  function applyEQ() {
    if (!eqNodes.length) return;
    EQ_TYPE.forEach((_, i) => {
      const target = S.eq[i];
      const t = ctx.currentTime;
      eqNodes[i].gain.cancelScheduledValues(t);
      eqNodes[i].gain.linearRampToValueAtTime(target, t + 0.12);
    });
  }

  function setVolume(v) {
    S.volume = v;
    if (gainNode) {
      const t = ctx.currentTime;
      gainNode.gain.cancelScheduledValues(t);
      gainNode.gain.linearRampToValueAtTime(
        S.muted ? 0 : volumeToGain(v), t + 0.08);
    }
    audio.volume = 1; // 由 gainNode 控制
    const el = $('#volume');
    el.value = v;
    el.style.setProperty('--p', v + '%');
    $('#volNum').textContent = v;
    $('#btnMute').classList.toggle('muted', S.muted || v === 0);
  }

  function toggleMute() {
    S.muted = !S.muted;
    setVolume(S.volume);
    toast(S.muted ? '已静音' : '取消静音', 1100);
  }

  function setSpeed(v) {
    S.speed = v;
    audio.playbackRate = v;
    audio.preservesPitch = true;
    if ('webkitPreservesPitch' in audio) audio.webkitPreservesPitch = true;
    $('#speedRange').value = Math.round(v * 100);
    $('#speedRange').style.setProperty('--p', ((v * 100 - 50) / 150 * 100) + '%');
    $('#speedVal').textContent = v.toFixed(2) + '×';
    const pill = $('#pillSpeed');
    pill.textContent = v.toFixed(2) + '×';
    pill.classList.toggle('accent', Math.abs(v - 1) > 0.001);
  }

  function setPitch(st) {
    S.pitch = st;
    if (shifterOK) {
      if (shifter.worklet) shifter.port.postMessage({ ratio: Math.pow(2, st / 12) });
      else shifter.__setPitch(st);
    }
    const lab = st === 0 ? '原调' : (st > 0 ? '+' + st : String(st)) + ' 半音';
    const pel = $('#pitchRange');
    pel.value = st;
    bipolarVar(pel, st);
    $('#pitchVal').textContent = lab;
    const pill = $('#pillPitch');
    pill.textContent = st === 0 ? '0 st' : (st > 0 ? '+' + st : st) + ' st';
    pill.classList.toggle('accent', st !== 0);
  }

  function setWidth(w) {
    S.width = w;
    if (shifterOK) {
      if (shifter.worklet) shifter.port.postMessage({ width: w / 50 });
      else shifter.__width = w / 50;
    }
    $('#stWidth').value = w;
    $('#stWidth').style.setProperty('--p', w + '%');
    $('#widthVal').textContent = w < 40 ? '单声道感' : w <= 62 ? '自然' : w < 85 ? '开阔' : '极宽';
  }

  async function resume() {
    await build();
    if (ctx.state === 'suspended') { try { await ctx.resume(); } catch (e) {} }
  }

  function getSpectrum() {
    if (!analyser) return null;
    analyser.getByteFrequencyData(freqData);
    return freqData;
  }

  return {
    audio, build, resume, setVolume, toggleMute, setSpeed, setPitch,
    setWidth, applyEQ, getSpectrum,
    get ctx() { return ctx; },
    get analyser() { return analyser; },
    get shifterOK() { return shifterOK; },
  };
})();

/* ══════════════════════════════════════════
   三、封面 / 可视化
   ══════════════════════════════════════════ */
const Art = (() => {
  const box = $('#artBox'), img = $('#artImg'), bgArt = $('#bgArt'),
        halo = $('#artHalo'), empty = $('#artEmpty'),
        cv = $('#viz'), cctx = cv.getContext('2d');
  let raf = 0, phase = 0, ready = false;

  const GRADS = [
    ['#2b2233', '#4a3a4e', '#8a6f7d'],
    ['#1d2430', '#2f4152', '#6f8fa8'],
    ['#2a2418', '#4a3e2a', '#9d8250'],
    ['#241d2e', '#3a2e55', '#7d6bb0'],
    ['#1e2a26', '#2f4a40', '#6f9c8a'],
  ];

  function gradFor(text) {
    let h = 0;
    for (let i = 0; i < text.length; i++) h = (h * 31 + text.charCodeAt(i)) >>> 0;
    const g = GRADS[h % GRADS.length];
    return `linear-gradient(145deg, ${g[0]} 0%, ${g[1]} 52%, ${g[2]} 100%)`;
  }

  function drawViz() {
    const on = S.playing && S.vizOn && S.tracks[S.idx];
    if (!on) { cv.classList.remove('on'); raf = 0; return; }
    cv.classList.add('on');

    const data = Engine.getSpectrum();
    const W = cv.width, H = cv.height;
    cctx.clearRect(0, 0, W, H);
    if (data) {
      const bars = 56;
      const step = Math.floor(data.length * 0.62 / bars);
      for (let i = 0; i < bars; i++) {
        let sum = 0;
        for (let j = 0; j < step; j++) sum += data[i * step + j];
        let v = (sum / step) / 255;
        v = Math.pow(v, 1.5);
        const x = (i + 0.5) / bars * W;
        const h = Math.max(2, v * H * 0.34);
        const a = 0.05 + v * 0.30;
        const g = cctx.createLinearGradient(x, H, x, H - h);
        g.addColorStop(0, `rgba(156,140,234,0)`);
        g.addColorStop(0.35, `rgba(228,194,154,${a * 0.7})`);
        g.addColorStop(1, `rgba(250,233,206,${a * 1.4})`);
        cctx.fillStyle = g;
        cctx.fillRect(x - 1.6, H, 3.2, -h);
        // 顶部镜像
        cctx.globalAlpha = 0.35;
        cctx.fillRect(x - 1.6, 0, 3.2, h * 0.24);
        cctx.globalAlpha = 1;
      }
    }
    raf = requestAnimationFrame(drawViz);
  }

  function sync() {
    const t = S.tracks[S.idx];
    if (!t) {
      img.classList.remove('show');
      empty.classList.remove('hide');
      bgArt.style.backgroundImage = 'none';
      box.style.backgroundImage = gradFor('lumen');
      return;
    }
    box.style.backgroundImage = gradFor(t.title + t.artist);
    if (t.demo && t.grad) {
      img.style.backgroundImage = t.grad;
      img.classList.add('show');
      empty.classList.add('hide');
      bgArt.style.backgroundImage = t.grad;
      return;
    }
    const url = `/cover?p=${encodeURIComponent(t.path)}`;
    const probe = new Image();
    probe.onload = () => {
      img.style.backgroundImage = `url("${url}")`;
      img.classList.add('show');
      empty.classList.add('hide');
      bgArt.style.backgroundImage = `url("${url}")`;
    };
    probe.onerror = () => {
      img.classList.remove('show');
      empty.classList.remove('hide');
      bgArt.style.backgroundImage = 'none';
    };
    probe.src = url;
  }

  function reveal() {
    box.style.opacity = 0;
    box.style.transform = 'translateY(14px) scale(.97)';
    requestAnimationFrame(() => {
      box.style.transition = 'opacity .9s var(--ease) .1s, transform .9s var(--ease) .1s';
      box.style.opacity = 1;
      box.style.transform = 'none';
    });
  }

  function setPlaying(on) {
    box.classList.toggle('playing', on);
    if (on && !raf) raf = requestAnimationFrame(drawViz);
    if (!on && raf) { cancelAnimationFrame(raf); raf = 0; cv.classList.remove('on'); }
  }

  return { sync, setPlaying, reveal };
})();

/* ══════════════════════════════════════════
   四、歌词
   ══════════════════════════════════════════ */
const Lyrics = (() => {
  const scroll = $('#lyrScroll'), empty = $('#lyrEmpty'),
        srcTag = $('#lyrSrc'), body = $('#lyrBody');
  let lines = [], active = -1, tickRaf = 0;
  let lastY = null;            // 当前 translateY
  let manualUntil = 0;         // 手动浏览期间暂停自动跟随
  let dragging = false, dragY0 = 0, dragBase = 0, suppressClick = false, needResume = false;

  /* 增强型 LRC（A2 逐字时间轴）→ 普通 LRC：
     形如 "<00:01.464>-<00:01.830> <00:02.196>周<00:02.562>杰"，每行取第一个时间为行时间，
     其余 <mm:ss.xxx> 标记全部剥掉。LRCLIB 歌词就是这个格式。 */
  function normalizeLRC(src) {
    if (!src || src.indexOf('<') < 0) return src;
    const stampRe = /<\s*(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\s*>/g;
    const out = [];
    src.split(/\r?\n/).forEach(line => {
      const stamps = [...line.matchAll(stampRe)];
      if (!stamps.length) { out.push(line); return; }
      const head = line.match(/^\s*\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]/);
      const pick = head
        ? { m: head, i: 0 }
        : { m: stamps[0], i: 0 };
      const g = pick.m;
      const t = (+g[1]) * 60 + (+g[2]) + (g[3] ? +('0.' + g[3]) : 0);
      const txt = line
        .replace(stampRe, '')
        .replace(/^\s*(\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?\])+\s*/, '')
        .trim();
      if (!txt) return;
      const mm = String(Math.floor(t / 60)).padStart(2, '0');
      const ss = String(Math.floor(t % 60)).padStart(2, '0');
      const ms = String(Math.round((t - Math.floor(t)) * 1000)).padStart(3, '0');
      out.push(`[${mm}:${ss}.${ms}]${txt}`);
    });
    return out.join('\n');
  }

  function parseLRC(text, transText) {
    if (!text) return [];
    text = normalizeLRC(text);
    transText = normalizeLRC(transText);
    const transMap = {};
    if (transText) {
      const re2 = /\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\](.*)/g;
      let m2;
      while ((m2 = re2.exec(transText))) {
        const t = (+m2[1]) * 60 + (+m2[2]) + (m2[3] ? +('0.' + m2[3]) : 0);
        transMap[t.toFixed(2)] = m2[4].trim();
      }
    }
    const out = [];
    const re = /\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\](.*)/g;
    let m;
    while ((m = re.exec(text))) {
      const t = (+m[1]) * 60 + (+m[2]) + (m[3] ? +('0.' + m[3]) : 0);
      const txt = m[4].trim();
      out.push({ t, text: txt, trans: transMap[t.toFixed(2)] || '' });
    }
    out.sort((a, b) => a.t - b.t);
    // 合并同一时间点的空行
    return out.filter(l => l.text || l.trans);
  }

  function fmtTimeShort(sec) {
    sec = Math.max(0, Math.round(sec || 0));
    const m = Math.floor(sec / 60), ss = String(sec % 60);
    return m + ':' + (ss.length < 2 ? '0' + ss : ss);
  }

  function plain(text) {
    return text.split(/\r?\n/).filter(l => l.trim())
      .map((l, i) => ({ t: -1, text: l.trim(), trans: '', i }));
  }

  function render(data) {
    lines = [];
    active = -1;
    scroll.innerHTML = '';
    const isSynced = !!data.synced;
    const arr = isSynced ? parseLRC(data.synced, data.trans) : plain(data.plain || '');
    lines = arr;
    if (!arr.length) {
      scroll.innerHTML = '';
      empty.classList.remove('hide');
      $('#lyrEmpty p').textContent = '这首歌的歌词是空的';
      $('#lyrEmpty small').textContent = '点右上角 ↻ 换一个源再试';
      return;
    }
    empty.classList.add('hide');
    srcTag.textContent = data.source || '本地';
    const frag = document.createDocumentFragment();
    arr.forEach((l, i) => {
      const d = document.createElement('div');
      d.className = 'lyr-line';
      if (l.t < 0) {
        // 纯文本
        d.textContent = l.text;
      } else {
        d.textContent = l.text || '· · ·';
        if (l.trans) {
          const s = document.createElement('span');
          s.className = 'trans';
          s.textContent = l.trans;
          d.appendChild(s);
        }
        d.addEventListener('click', () => {
          if (suppressClick) return;          // 刚才是在拖歌词，不当点击
          jumpToLine(d);
        });

      }
      frag.appendChild(d);
    });
    scroll.appendChild(frag);
    active = -1; lastY = null;
    // 同步歌词先从头显示、随后由 sync 定位；纯文本则把第一行顶到框顶
    applyY(isSynced ? bounds().max : -(body.offsetHeight || 0) / 2, false);
    requestAnimationFrame(() => sync(0, true));
    setTimeout(() => { lastY = null; sync(Engine.audio.currentTime || 0, true); }, 260);
    setTimeout(() => { lastY = null; sync(Engine.audio.currentTime || 0, true); }, 900);
    if (!isSynced) srcTag.textContent = (data.source || '本地') + ' · 静态';
  }

  function clear() {
    lines = []; active = -1; lastY = 0; manualUntil = 0; dragging = false;
    scroll.innerHTML = '';
    empty.classList.remove('hide');
    srcTag.textContent = '—';
    scroll.style.transition = '';
    scroll.style.transform = 'translateY(0)';
  }

  /* 手动滚动：把 translateY 夹在内容两端之间，避免滚飞 */
  function bounds() {
    const H = scroll.offsetHeight || 0, boxH = body.offsetHeight || 0;
    if (!H || !boxH) return { min: -1e4, max: 1e4 };
    if (H <= boxH) return { min: 0, max: 0 };
    // max=0 保留原本「首行落在中线」的观感；min 到底时内容底部贴框底
    return { min: boxH / 2 - H, max: 0 };
  }

  function applyY(y, smooth) {
    const b = bounds();
    y = Math.min(b.max, Math.max(b.min, y));
    scroll.style.transition = smooth === false ? 'none' : '';
    scroll.style.transform = `translateY(${y}px)`;
    lastY = y;
    return y;
  }

  function suspendAuto(ms) { manualUntil = performance.now() + (ms || 3500); needResume = true; }

  function sync(time, force) {
    if (!lines.length) return;
    if (lines[0].t < 0) { // 纯文本：没有时间轴，交给用户自己拖
      return;
    }
    if (performance.now() < manualUntil) return;   // 手动浏览期间不许抢
    if (needResume) { needResume = false; force = true; }  // 松手到点后强制回位
    let idx = -1;
    for (let i = 0; i < lines.length; i++) {
      if (lines[i].t <= time + 0.18) idx = i; else break;
    }
    if (idx === active && !force) return;
    active = idx;
    if (performance.now() >= manualUntil) clearPicked();
    S.lyrIndex = idx;
    const kids = scroll.children;
    for (let i = 0; i < kids.length; i++) {
      kids[i].classList.toggle('active', i === idx);
      kids[i].classList.toggle('past', i < idx);
    }
    const cur = kids[idx];
    if (cur) {
      const h = cur.offsetHeight;
      const y = -(cur.offsetTop + h / 2);
      if (lastY === null || Math.abs(y - lastY) > 2 || force) applyY(y);
    }
    pushOverlayLyric();
  }

  function loop() {
    tickRaf = requestAnimationFrame(function f() {
      if (S.playing) sync(Engine.audio.currentTime);
      tickRaf = requestAnimationFrame(f);
    });
  }

  /* 换一版：依次切换歌词来源（默认 → 酷狗 → 网易云 → LRCLIB），绕开缓存重新匹配 */
  let srcCycle = ['', 'kugou', 'netease', 'lrclib'];
  let srcCycleIdx = 0;
  function alt(track) {
    if (!track) return;
    srcCycleIdx = (srcCycleIdx + 1) % srcCycle.length;
    const prefer = srcCycle[srcCycleIdx];
    delete S.lyricsCache[track.path];
    const label = { '': '自动', kugou: '酷狗', netease: '网易云', lrclib: 'LRCLIB' }[prefer] || prefer;
    toast('换一版歌词（优先' + label + '）…', 1800);
    load(track, true, prefer);
  }

  async function load(track, force, prefer) {
    if (!track) return;
    if (!force && !prefer && S.lyricsCache[track.path]) {
      const c = S.lyricsCache[track.path];
      render(c);
      return;
    }
    if (!S.lyrOn) { clear(); return; }
    const offlineNow = (typeof navigator !== 'undefined' && navigator.onLine === false);
    $('#lyrEmpty p').textContent = offlineNow ? '网络未连接，正在查找本地缓存…' : '正在搜索歌词…';
    $('#lyrEmpty small').textContent = offlineNow
      ? '联网时匹配过的歌词已保存在本地' : '正在联网匹配，稍等一下';
    if (!force && !prefer) srcTag.textContent = '搜索中…';
    const a = api();
    if (!a) { clear(); return; }
    try {
      // 9 秒兜底：断网/歌词服务无响应时不能一直转圈
      const lyricTimeout = new Promise((_, rej) => setTimeout(() => rej(new Error('lyric-timeout')), 9000));
      const res = await Promise.race([a.get_lyrics(
        track.title || '', track.artist || '', track.path, track.duration || 0,
        prefer || '', !!force), lyricTimeout]);
      if (S.tracks[S.idx] !== track && S.tracks[S.idx]?.path !== track.path) return;
      if (res && res.instrumental) {
        S.lyricsCache[track.path] = res;
        clear();
        srcTag.textContent = '纯音乐';
        $('#lyrEmpty p').textContent = '纯音乐';
        $('#lyrEmpty small').textContent = '这首歌没有歌词，安心听';
      } else if (res && (res.synced || res.plain)) {
        S.lyricsCache[track.path] = res;
        render(res);
      } else {
        clear();
        $('#lyrEmpty p').textContent = '这次没搜到歌词';
        $('#lyrEmpty small').textContent = '点右上角 ↻ 再匹配一次';
        if (force) toast('没有找到这首歌的歌词');
      }
    } catch (e) {
      // 断网/超时：如果内存里已有这首歌的歌词缓存，直接用
      const cached = S.lyricsCache[track.path];
      if (cached && (cached.synced || cached.plain)) {
        render(cached);
        srcTag.textContent = '本地缓存';
        toast('网络未连接，已显示之前缓存的歌词', 2400);
        return;
      }
      clear();
      const offline = (typeof navigator !== 'undefined' && navigator.onLine === false);
      $('#lyrEmpty p').textContent = offline ? '网络未连接，无法匹配歌词' : '歌词服务没响应';
      $('#lyrEmpty small').textContent = offline
        ? '这首歌之前联网匹配过的话会显示缓存；联网后会自动重新匹配'
        : '点右上角 ↻ 重试';
      if (force) toast(offline ? '网络未连接，无法匹配歌词' : '歌词服务暂时不可用');
    }
  }

  /* ── 手动滚动歌词：滚轮 + 拖动（手机 touch 连续跟手；松手 3.5 秒恢复跟随） ── */
  let pickBar = null;
  let pickIdx = -1;
  let pickRaf = 0;

  function ensurePickBar() {
    if (pickBar) return pickBar;
    const panel = document.querySelector('.lyrics-panel');
    if (!panel) return null;
    pickBar = document.createElement('div');
    pickBar.className = 'lyr-pickbar';
    pickBar.innerHTML = '<span class="pk-time">0:00</span>' +
      '<button class="pk-play" type="button" aria-label="从这句播放">' +
      '<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg></button>';
    pickBar.querySelector('.pk-play').addEventListener('click', ev => {
      ev.stopPropagation();
      const idx = pickIdx >= 0 ? pickIdx : pickCenter();
      const l = lines[idx];
      if (l && l.t >= 0) jumpToLineByIndex(idx);
    });
    panel.appendChild(pickBar);
    return pickBar;
  }

  function showPickBar(show) {
    const bar = ensurePickBar();
    if (!bar) return;
    bar.classList.toggle('on', !!show);
  }

  function jumpToLineByIndex(idx) {
    const l = lines[idx];
    if (!l || !(l.t >= 0)) return;
    try {
      Engine.audio.currentTime = l.t;
      try { Engine.audio.play(); } catch (e) {}
      if (!S.playing) Player.toggle();
    } catch (e) {}
    manualUntil = 0;
    needResume = true;
    showPickBar(false);
    clearPicked();
    sync(Engine.audio.currentTime || l.t, true);
  }

  function inLyricArea(t) {
    const pn = document.querySelector('.lyrics-panel');
    return !!(pn && t && pn.contains(t));
  }

  /** 找当前居中（用户想选）的那一行 */
  function pickCenter() {
    const kids = scroll.children;
    if (!kids.length) return -1;
    const cy = (body.offsetHeight || 0) / 2;
    let best = -1, bestD = 1e9;
    for (let i = 0; i < kids.length; i++) {
      const k = kids[i];
      const center = (scroll.offsetTop || 0) + k.offsetTop + k.offsetHeight / 2 + (lastY || 0);
      const d = Math.abs(center - cy);
      if (d < bestD) { bestD = d; best = i; }
    }
    return best;
  }

  function markPicked() {
    const idx = pickCenter();
    pickIdx = idx;
    const kids = scroll.children;
    for (let i = 0; i < kids.length; i++) kids[i].classList.toggle('picked', i === idx);
    if (idx >= 0 && lines[idx] && lines[idx].t >= 0) {
      const bar = ensurePickBar();
      if (bar) {
        const tm = bar.querySelector('.pk-time');
        if (tm) tm.textContent = fmtTimeShort(lines[idx].t);
        bar.classList.add('on');
      }
    } else {
      showPickBar(false);
    }
    return idx;
  }

  function requestPickUpdate() {
    if (pickRaf) return;
    pickRaf = requestAnimationFrame(() => {
      pickRaf = 0;
      markPicked();
    });
  }

  function clearPicked() {
    for (const el of scroll.querySelectorAll('.lyr-line.picked')) el.classList.remove('picked');
    showPickBar(false);
  }

  function jumpToLine(kid) {
    const i = Array.prototype.indexOf.call(scroll.children, kid);
    jumpToLineByIndex(i);
  }

  /* 滚轮（桌面） */
  body.addEventListener('wheel', e => {
    if (!lines.length) return;
    e.preventDefault();
    applyY((lastY === null ? bounds().max : lastY) - e.deltaY * 0.9, false);
    suspendAuto(3500);
    requestPickUpdate();
  }, { passive: false });

  /* 手机：touch 连续跟手拖动（preventDefault 防止页面滚动抢） */
  let tDrag = { active: false, y0: 0, x0: 0, base: 0, dir: 0 };
  body.addEventListener('touchstart', e => {
    if (!lines.length || !e.touches || e.touches.length !== 1) return;
    tDrag.active = true;
    tDrag.y0 = e.touches[0].clientY;
    tDrag.x0 = e.touches[0].clientX;
    tDrag.base = (lastY === null ? bounds().max : lastY);
    tDrag.dir = 0;
  }, { passive: true });

  body.addEventListener('touchmove', e => {
    if (!tDrag.active || !e.touches || !e.touches.length) return;
    const t = e.touches[0];
    const dy = t.clientY - tDrag.y0;
    const dx = t.clientX - tDrag.x0;
    if (!tDrag.dir) {
      if (Math.abs(dy) < 5 && Math.abs(dx) < 5) return;
      if (Math.abs(dx) > Math.abs(dy)) { tDrag.active = false; return; }  // 横向手势让给切页
      tDrag.dir = 1;
    }
    if (e.cancelable) e.preventDefault();     // 关键：别让页面滚动/合成器抢走手势
    if (Math.abs(dy) > 4) suppressClick = true;
    applyY(tDrag.base + dy, false);
    suspendAuto(3500);
    requestPickUpdate();
  }, { passive: false });

  body.addEventListener('touchend', () => {
    if (!tDrag.active) return;
    const wasDrag = tDrag.dir === 1;
    tDrag.active = false;
    if (wasDrag) setTimeout(() => { suppressClick = false; markPicked(); }, 0);
  }, { passive: true });
  body.addEventListener('touchcancel', () => { tDrag.active = false; }, { passive: true });

  /* 桌面：鼠标拖动（手机走上面的 touch，避免两套事件打架） */
  let dragX0 = 0;
  body.addEventListener('pointerdown', e => {
    if (!lines.length || e.button !== 0) return;
    if (document.body.classList.contains('android')) return;   // 手机用 touch
    if (!inLyricArea(e.target)) return;
    dragging = true;
    dragY0 = e.clientY;
    dragX0 = e.clientX;
    dragBase = (lastY === null ? bounds().max : lastY);
    try { body.setPointerCapture(e.pointerId); } catch (_) {}
  });

  body.addEventListener('pointermove', e => {
    if (!dragging) return;
    const dy = e.clientY - dragY0;
    const dx = e.clientX - dragX0;
    if (Math.abs(dx) > 12 && Math.abs(dx) > Math.abs(dy) * 1.2) return;
    if (Math.abs(dy) > 4) suppressClick = true;
    applyY(dragBase + dy, false);
    suspendAuto(3500);
    requestPickUpdate();
  });

  function endDrag(e) {
    if (!dragging) return;
    dragging = false;
    try { body.releasePointerCapture(e.pointerId); } catch (_) {}
    setTimeout(() => { suppressClick = false; markPicked(); }, 0);
  }
  body.addEventListener('pointerup', endDrag);
  body.addEventListener('pointercancel', endDrag);

  return { load, alt, render, clear, sync, loop, stopLoop: () => cancelAnimationFrame(tickRaf) };
})();

/* ══════════════════════════════════════════
   五、播放器
   ══════════════════════════════════════════ */
const Player = (() => {
  const audio = Engine.audio;

  function setTrack(i, autoplay = true) {
    if (i < 0 || i >= S.tracks.length) return;
    S.idx = i;
    try { recordRecent(S.tracks[i]); } catch (e) {}
    const t = S.tracks[i];
    if (!t.demo) {
      if (t.offline) {
        // 本地没有这首歌：清掉旧音源
        try { audio.pause(); } catch (e) {}
        audio.removeAttribute('src');
        try { audio.load(); } catch (e) {}
      } else {
        audio.src = `/music?p=${encodeURIComponent(t.path)}`;
      }
    }
    audio.playbackRate = S.speed;
    audio.preservesPitch = true;

    $('#mTitle').textContent = t.title || t.name;
    $('#mArtist').textContent = t.artist || '未知艺术家';
    $('#mAlbum').textContent = t.album || '';
    $('#tDur').textContent = t.duration ? fmt(t.duration) : '0:00';
    $('#tCur').textContent = '0:00';
    $('#srcTag').textContent = t.artist ? '本地曲库' : '本地文件';
    Art.sync();
    if (!t.demo) Lyrics.load(t);
    Queue.markCurrent();
    updateNowBar();
    saveConfig();
    pushMedia(true);
    if (autoplay && !t.demo) {
      play();
    }
  }

  /* ── 断点续播：记住上次听的歌和位置，重开接上 ── */
  const RESUME_KEY = 'luvsic_play_state';
  let resumeSavedAt = 0;
  let resumedOnce = false;
  function savePlayState() {
    try {
      const t = S.tracks[S.idx];
      if (!t || !t.path) return;
      localStorage.setItem(RESUME_KEY, JSON.stringify({
        path: t.path, pos: audio.currentTime || 0, view: S.view || '',
        mode: S.mode, vol: S.volume, muted: S.muted, updatedAt: Date.now(),
      }));
    } catch (e) {}
  }
  function savePlayStateThrottled() {
    const now = Date.now();
    if (now - resumeSavedAt < 3000) return;
    resumeSavedAt = now;
    savePlayState();
  }
  function loadPlayState() {
    try { return JSON.parse(localStorage.getItem(RESUME_KEY) || 'null'); } catch (e) { return null; }
  }
  /** 恢复上次曲目/位置/歌单视图（不自动播放，点播放从断点继续） */
  function resumeLast() {
    if (resumedOnce) return;
    resumedOnce = true;
    const st = loadPlayState();
    if (!st || !st.path) return;
    if (Date.now() - (st.updatedAt || 0) > 30 * 24 * 3600 * 1000) return;  // 超过 30 天不恢复
    if (st.view) { try { if (Playlists.get(st.view)) S.view = st.view; } catch (e) {} }
    const i = S.tracks.findIndex(t => t.path === st.path);
    if (i < 0) return;
    setTrack(i, false);
    const applyPos = () => {
      try { if (st.pos > 3) audio.currentTime = st.pos; } catch (e) {}
      try { toast('上次听到 ' + fmt(st.pos) + '，点播放继续', 2800); } catch (e) {}
      audio.removeEventListener('loadedmetadata', applyPos);
    };
    if (audio.readyState >= 1) applyPos();
    else audio.addEventListener('loadedmetadata', applyPos);
  }

  async function play() {
    if (S.idx < 0) {
      if (S.tracks.length) setTrack(0, true);
      else { setView('lib'); toast('先在曲库里选一个音乐文件夹'); }
      return;
    }
    await Engine.resume();
    try {
      await audio.play();
    } catch (e) {
      toast('无法播放这个文件');
      console.warn(e);
    }
  }

  function pause() { audio.pause(); }

  function toggle() {
    if (audio.paused) play(); else pause();
  }

  function next(manual = true) {
    if (!S.tracks.length) return;
    const seq = Queue.currentList().map(x => x.i);
    if (S.mode === 'shuffle') { setTrack(pickShuffle(seq)); return; }
    if (S.view && seq.length) {
      const pos = seq.indexOf(S.idx);
      const n = pos >= 0 ? (pos + 1) % seq.length : 0;
      setTrack(seq[n]);
      return;
    }
    let i = S.idx + 1;
    if (i >= S.tracks.length) i = 0;
    setTrack(i);
  }

  function prev() {
    if (!S.tracks.length) return;
    if (audio.currentTime > 3.2) { audio.currentTime = 0; return; }
    const seq = Queue.currentList().map(x => x.i);
    if (S.mode === 'shuffle') { setTrack(pickShuffle(seq)); return; }
    if (S.view && seq.length) {
      const pos = seq.indexOf(S.idx);
      const n = pos > 0 ? pos - 1 : seq.length - 1;
      setTrack(seq[n]);
      return;
    }
    let i = S.idx - 1;
    if (i < 0) i = S.tracks.length - 1;
    setTrack(i);
  }

  function pickShuffle(seq) {
    seq = (seq && seq.length) ? seq : S.tracks.map((_, i) => i);
    if (S.shuffleBag.length === 0) {
      S.shuffleBag = seq.filter(i => i !== S.idx);
      for (let i = S.shuffleBag.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [S.shuffleBag[i], S.shuffleBag[j]] = [S.shuffleBag[j], S.shuffleBag[i]];
      }
    }
    return S.shuffleBag.length ? S.shuffleBag.pop() : seq[0];
  }

  /* ── 事件 ── */
  audio.addEventListener('loadedmetadata', () => {
    if (isFinite(audio.duration)) {
      $('#tDur').textContent = fmt(audio.duration);
      const t = S.tracks[S.idx];
      if (t && !t.duration) t.duration = Math.round(audio.duration);
    }
  });

  audio.addEventListener('timeupdate', () => {
    const d = audio.duration || 0, c = audio.currentTime || 0;
    try { window.__luvLastPos = c; window.__luvLastAt = Date.now(); } catch (e) {}
    const pct = d ? (c / d * 100) : 0;
    $('#seekFill').style.width = pct + '%';
    $('#seekKnob').style.left = pct + '%';
    $('#tCur').textContent = fmt(c);
    const lnBar = $('#lnBar');
    if (lnBar) lnBar.style.width = pct + '%';
    if (!seek.dragging) Lyrics.sync(c);
    pushMedia();
    savePlayStateThrottled();
  });

  audio.addEventListener('progress', () => {
    try {
      const d = audio.duration, b = audio.buffered;
      if (d && b.length) {
        const end = b.end(b.length - 1);
        $('#seekBuf').style.width = (end / d * 100) + '%';
      }
    } catch (e) {}
  });

  audio.addEventListener('play', () => {
    S.playing = true;
    $('#btnPlay').classList.add('playing');
    $('#lnPlay').classList.add('playing');
    Art.setPlaying(true);
    pushMedia(true);
    try {
      const t = S.tracks[S.idx];
      if (t && api()) api().set_now_playing(t.title || t.name || '', true);
    } catch (e) {}
  });

  audio.addEventListener('pause', () => {
    S.playing = false;
    $('#btnPlay').classList.remove('playing');
    $('#lnPlay').classList.remove('playing');
    Art.setPlaying(false);
    pushMedia(true);
    savePlayState();
    try {
      const t = S.tracks[S.idx];
      if (t && api()) api().set_now_playing(t.title || t.name || '', false);
    } catch (e) {}
  });

  try { window.addEventListener('beforeunload', () => savePlayState()); } catch (e) {}

  audio.addEventListener('ended', () => {
    if (!Settings.allowNext()) { try { audio.pause(); } catch (e) {} return; }
    if (S.mode === 'one') { audio.currentTime = 0; play(); return; }
    next(false);
  });

  let errStreak = 0;
  audio.addEventListener('playing', () => { errStreak = 0; });
  audio.addEventListener('error', () => {
    if (!audio.src) return;
    if (S.tracks[S.idx] && S.tracks[S.idx].demo) return;
    errStreak++;
    // 连续几首都读不出来（文件被删/没下载/损坏）就停下来，不要无限"跳过"
    const limit = Math.max(2, Math.min(S.tracks.length || 2, 5));
    if (errStreak >= limit) {
      errStreak = 0;
      try { audio.pause(); } catch (e) {}
      toast('连续几首都读不出来，已停止播放（检查文件是否还在、或没下载）', 2600);
      return;
    }
    toast('这个文件读不出来，跳过');
    setTimeout(() => next(false), 900);
  });

  /* ── 进度条拖拽 ── */
  const seekEl = $('#seek'), tip = $('#seekTip');
  const seek = { dragging: false };
  if (seekEl) seekEl.style.touchAction = 'none';

  function pctFromEvent(e) {
    const r = seekEl.getBoundingClientRect();
    return Math.max(0, Math.min(1, (e.clientX - r.left) / r.width));
  }
  function paintSeek(p) {
    $('#seekFill').style.width = p * 100 + '%';
    $('#seekKnob').style.left = p * 100 + '%';
  }
  seekEl.addEventListener('pointerdown', e => {
    e.preventDefault();
    seek.dragging = true;
    try { seekEl.setPointerCapture(e.pointerId); } catch (err) {}
    paintSeek(pctFromEvent(e));
  });
  seekEl.addEventListener('pointermove', e => {
    const p = pctFromEvent(e);
    tip.style.left = p * 100 + '%';
    const d = audio.duration || 0;
    tip.textContent = fmt(p * d);
    if (seek.dragging) {
      e.preventDefault();          // 拖动时不要触发页面滚动
      paintSeek(p);
    }
  });
  function finishSeek(e, commit) {
    if (!seek.dragging) return;
    seek.dragging = false;
    try { seekEl.releasePointerCapture(e.pointerId); } catch (err) {}
    if (!commit) return;
    const p = pctFromEvent(e);
    paintSeek(p);
    if (isFinite(audio.duration)) {
      audio.currentTime = p * audio.duration;
      Lyrics.sync(audio.currentTime, true);
    }
  }
  seekEl.addEventListener('pointerup', e => finishSeek(e, true));
  seekEl.addEventListener('pointercancel', e => finishSeek(e, false));
  // 手指滑出进度条时也要继续跟手（指针捕获之外再加一层兜底）
  window.addEventListener('pointerup', e => { if (seek.dragging) finishSeek(e, true); });

  return { setTrack, play, pause, toggle, next, prev, seek, resumeLast };
})();

/* ══════════════════════════════════════════
   六、歌单 / 右键菜单 / 命名浮层
   ══════════════════════════════════════════ */
const Playlists = (() => {
  async function load() {
    const d = await api().get_playlists();
    S.playlists = (d && Array.isArray(d.playlists)) ? d.playlists : [];
  }
  function save() {
    // 统一成对象格式再存（兼容旧版纯字符串条目，避免来回混格式）
    S.playlists.forEach(p => {
      p.items = (p.items || []).map(x => {
        if (typeof x === 'string') {
          return { path: x, name: (x.split(/[\\/]/).pop() || '') };
        }
        return x;
      });
    });
    api().save_playlists({ playlists: S.playlists });
  }
  function get(id) { return S.playlists.find(p => p.id === id); }
  function create(name) {
    const id = 'p' + Date.now().toString(36) + Math.floor(Math.random() * 99);
    S.playlists.push({ id, name, items: [] });
    save();
    return id;
  }
  function remove(id) {
    S.playlists = S.playlists.filter(p => p.id !== id);
    if (S.view === id) S.view = null;
    save();
  }
  function rename(id, name) {
    const p = get(id);
    if (p && name) { p.name = name; save(); }
  }
  function addTrack(id, path) {
    const p = get(id);
    if (!p || p.items.some(it => pathOf(it) === path)) return false;
    p.items.push(path); save();
    return true;
  }
  function removeTrack(id, path) {
    const p = get(id);
    if (!p) return;
    p.items = p.items.filter(x => pathOf(x) !== path);
    save();
  }
  /* 歌单条目既支持旧格式（纯 path 字符串），也支持新格式（对象，带离线标记） */
  function itemOf(it) {
    if (typeof it === 'string') {
      return { path: it, name: (it.split(/[\\/]/).pop() || ''), title: '', artist: '', duration: 0, offline: false };
    }
    return it || {};
  }
  function pathOf(it) { return typeof it === 'string' ? it : ((it && it.path) || ''); }
  function itemByPath(id, path) {
    const p = get(id);
    if (!p) return null;
    let hit = null;
    (p.items || []).forEach(it => { if (pathOf(it) === path) hit = itemOf(it); });
    return hit;
  }
  function move(id, from, to) {
    const p = get(id);
    if (!p || from === to || from < 0 || to < 0 || from >= p.items.length) return;
    const [x] = p.items.splice(from, 1);
    p.items.splice(to, 0, x);
    save();
  }
  function sortById(id, key) {
    const p = get(id);
    if (!p) return;
    const map = new Map(S.tracks.map(t => [t.path, t]));
    p.items.sort((a, b) => {
      const ta = map.get(pathOf(a)) || {}, tb = map.get(pathOf(b)) || {};
      return String(ta[key] || '').localeCompare(String(tb[key] || ''), 'zh-Hans-CN');
    });
    save();
  }
  return { load, save, get, create, remove, rename, addTrack, removeTrack, move, sortById,
           itemOf, pathOf, itemByPath };
})();

/* 当前视图的曲目序列（不受搜索过滤影响，用于播放顺序）
   歌单里本机没有的歌也保留一行，标成 offline（未下载），不静默丢掉 */
function viewTracks() {
  if (S.view === '_recent') {
    const map = new Map(S.tracks.map((t, i) => [t.path, { t, i }]));
    const out = [];
    (S.recent || []).forEach(r => {
      const hit = map.get(r.path);
      if (hit) out.push(hit);
    });
    return out;
  }
  if (!S.view) return S.tracks.map((t, i) => ({ t, i }));
  const pl = Playlists.get(S.view);
  if (!pl) return S.tracks.map((t, i) => ({ t, i }));
  const map = new Map(S.tracks.map((t, i) => [t.path, { t, i }]));
  const out = [];
  (pl.items || []).forEach(it => {
    const o = Playlists.itemOf(it);
    const hit = map.get(o.path);
    if (hit) { out.push(hit); return; }
    out.push({
      t: {
        path: o.path || '', name: o.name || '', title: o.title || o.name || '',
        artist: o.artist || '', album: o.album || '', duration: o.duration || 0,
        hasCover: false, offline: true,
      },
      i: -1,
    });
  });
  return out;
}

/* 右键菜单 */
const Ctx = (() => {
  let el = null, onDoc = null;
  function close() {
    if (!el) return;
    const node = el;
    node.classList.remove('on');
    setTimeout(() => node.remove(), 180);
    el = null;
    document.removeEventListener('pointerdown', onDoc, true);
  }
  function open(x, y, items) {
    close();
    el = document.createElement('div');
    el.className = 'ctx';
    items.forEach(it => {
      if (it.sep) { const s = document.createElement('div'); s.className = 'ctx-sep'; el.appendChild(s); return; }
      if (it.head) { const h = document.createElement('div'); h.className = 'ctx-head'; h.textContent = it.head; el.appendChild(h); return; }
      const b = document.createElement('button');
      b.className = 'ctx-item' + (it.danger ? ' danger' : '') + (it.dim ? ' dim' : '');
      b.textContent = it.label;
      b.onclick = () => { close(); it.action && it.action(); };
      el.appendChild(b);
    });
    document.body.appendChild(el);
    const r = el.getBoundingClientRect();
    el.style.left = Math.max(8, Math.min(x, innerWidth - r.width - 12)) + 'px';
    el.style.top = Math.max(8, Math.min(y, innerHeight - r.height - 12)) + 'px';
    requestAnimationFrame(() => el.classList.add('on'));
    onDoc = e => { if (el && !el.contains(e.target)) close(); };
    setTimeout(() => document.addEventListener('pointerdown', onDoc, true), 0);
  }
  return { open, close };
})();

/* 命名浮层（app 模式禁用 prompt，用这个替代） */
function askName(title, def, cb) {
  $('#askTitle').textContent = title;
  const inp = $('#askInput');
  inp.value = def || '';
  $('#nameAsk').classList.add('on');
  setTimeout(() => { inp.focus(); inp.select(); }, 80);
  const done = ok => {
    $('#nameAsk').classList.remove('on');
    $('#askOk').onclick = $('#askCancel').onclick = null;
    inp.onkeydown = null;
    if (ok && inp.value.trim()) cb(inp.value.trim());
  };
  $('#askOk').onclick = () => done(true);
  $('#askCancel').onclick = () => done(false);
  $('#nameAsk').addEventListener('pointerdown', e => {
    if (e.target === $('#nameAsk')) done(false);
  }, { once: true });
  inp.onkeydown = e => {
    if (e.key === 'Enter') done(true);
    if (e.key === 'Escape') done(false);
    e.stopPropagation();
  };
}


/* 通用确认弹窗：标题 + 说明 + 「稍后 / 立即」两个按钮 */
function showConfirm(title, msg, yesLabel, onYes) {
  const box = $('#confirmAsk');
  if (!box) { try { onYes(); } catch (e) {} return; }
  const t = $('#confirmTitle'), m = $('#confirmMsg');
  if (t) t.textContent = title || '提示';
  if (m) m.textContent = msg || '';
  const yes = $('#confirmYes'), no = $('#confirmNo');
  const close = () => {
    box.classList.remove('on');
    if (yes) yes.onclick = null;
    if (no) no.onclick = null;
  };
  if (yes) {
    yes.textContent = yesLabel || '确定';
    yes.onclick = () => { close(); try { onYes(); } catch (e) {} };
  }
  if (no) no.onclick = close;
  box.onclick = e => { if (e.target === box) close(); };
  box.classList.add('on');
}

/* 从曲库移除（不动磁盘文件） */
async function removeTracks(paths) {
  const gone = new Set(paths);
  const oldTracks = S.tracks;
  const cur = oldTracks[S.idx];
  const curPath = cur ? cur.path : null;
  const curIsGone = !!curPath && gone.has(curPath);

  S.selSet.clear();
  S.lastSel = null;
  S.multi = false;
  updateSelBar();
  S.tracks = oldTracks.filter(t => !gone.has(t.path));
  S.shuffleBag = [];
  // 歌单条目可能是对象（新格式）也可能是纯路径字符串，统一按 path 比较，否则删不干净
  S.playlists.forEach(p => {
    p.items = (p.items || []).filter(x => !gone.has(Playlists.pathOf(x)));
  });
  Playlists.save();
  try { await api().remove_tracks(paths); } catch (e) {}
  paths.forEach(p => { delete S.lyricsCache[p]; });

  if (curIsGone) {
    // 正在播的被删了：接被删位置之后（不行再往前）第一首还存在的歌
    const start = oldTracks.indexOf(cur);
    let nxtPath = null;
    for (let i = start + 1; i < oldTracks.length; i++) {
      if (!gone.has(oldTracks[i].path)) { nxtPath = oldTracks[i].path; break; }
    }
    if (!nxtPath) {
      for (let i = start - 1; i >= 0; i--) {
        if (!gone.has(oldTracks[i].path)) { nxtPath = oldTracks[i].path; break; }
      }
    }
    const ni = nxtPath ? S.tracks.findIndex(t => t.path === nxtPath) : -1;
    if (ni >= 0) {
      Player.setTrack(ni, S.playing);
    } else {
      S.idx = -1;
      Engine.audio.pause();
      Engine.audio.removeAttribute('src');
      $('#mTitle').textContent = '暂无曲目';
      $('#mArtist').textContent = '曲库已空';
    }
  } else {
    // 关键：把当前播放指针往回挪「被删且排在它前面」的数量，否则上下首全部错位
    let before = 0;
    for (let i = 0; i < S.idx && i < oldTracks.length; i++) {
      if (gone.has(oldTracks[i].path)) before++;
    }
    S.idx -= before;
    Queue.markCurrent();
    updateNowBar();
  }

  Queue.render($('#libSearch').value);
  Queue.renderPlSide();
  toast(paths.length > 1 ? `已移除 ${paths.length} 首（文件保留）` : '已从曲库移除（文件保留）');
  saveConfig();
}

/* ══════════════════════════════════════════
   七、曲库 · 编辑视图
   ══════════════════════════════════════════ */
const Queue = (() => {
  const list = $('#libList');
  const NOTE = '单击播放 · 双击进入多选（可批量加入歌单 / 移除）· Ctrl/Shift 多选';
  const ICON_NOTE = '<svg viewBox="0 0 24 24"><path d="M9 18V6l11-2v12"/><circle cx="6.5" cy="18" r="2.6"/><circle cx="17.5" cy="16" r="2.6"/></svg>';
  const PLAY_DELAY = 250;              // 区分单击/双击的判定窗口
  let playTimer = null;                // 待触发的单击播放

  function syncSel() {
    updateSelBar();
    [...list.children].forEach((node, k) => {
      const it = S.shown[k];
      node.classList.toggle('sel', !!it && S.selSet.has(it.t.path));
    });
  }

  function render(filter = '') {
    if (playTimer) { clearTimeout(playTimer); playTimer = null; }
    const q = filter.trim().toLowerCase();
    let base = viewTracks();
    // 歌单里可切顺序/倒序：倒序时按当前列表反向播放
    if (S.view && S.plDesc) base = base.slice().reverse();
    S.shown = base.filter(({ t }) => {
      if (!q) return true;
      const hay = (t.title + ' ' + t.artist + ' ' + t.name).toLowerCase();
      if (hay.includes(q)) return true;
      // 拼音首字母搜索：输入纯字母时，「qhc」能搜到「青花瓷」
      if (/^[a-z0-9]+$/.test(q)) {
        try {
          if (PY.ofText(t.title).includes(q)) return true;
          if (PY.ofText(t.artist).includes(q)) return true;
        } catch (e) {}
      }
      return false;
    });
    renderPlSide();

    list.innerHTML = '';
    if (!S.tracks.length) {
      list.innerHTML = document.body.classList.contains('android')
        ? '<div class="lib-empty">曲库里还没有歌<br><small>点上方「选歌」扫描本机，勾选要加入曲库的歌曲</small></div>'
        : '<div class="lib-empty">还没有曲目<br><small>点上方「选文件夹」，指向你放音乐的目录</small></div>';
      $('#libFoot').textContent = '';
      return;
    }
    if (S.view && !base.length) {
      list.innerHTML = '<div class="lib-empty">这个歌单还是空的<br><small>回到「全部音乐」，右键歌曲 → 加入歌单</small></div>';
      $('#libFoot').textContent = NOTE;
      return;
    }

    const dragable = !!S.view && !q;
    const totalItems = S.view ? ((Playlists.get(S.view) || { items: [] }).items || []).length : 0;
    const frag = document.createDocumentFragment();
    S.shown.forEach(({ t, i }, n) => {
      const el = document.createElement('div');
      el.className = 'lrow' + (i === S.idx ? ' cur' : '') + (S.selSet.has(t.path) ? ' sel' : '') + (t.offline ? ' offline' : '');
      el.innerHTML = `
        <div class="l-num">${String(n + 1).padStart(2, '0')}</div>
        <div class="l-th">${t.hasCover ? '' : ICON_NOTE}</div>
        <div class="l-name">${esc(t.title || t.name)}${t.offline ? '<span class="off-tag">未下载</span>' : ''}</div>
        <div class="l-artist">${esc(t.artist || '未知艺术家')}</div>
        <div class="l-album">${esc(t.album || '')}</div>
        <div class="l-dur">${t.duration ? fmt(t.duration) : '—'}</div>`;
      if (t.hasCover) {
        el.querySelector('.l-th').style.backgroundImage =
          `url('/cover?p=${encodeURIComponent(t.path)}')`;
      }
      // 手机：长按 600ms -> 歌单视图里进入拖动排序；其它情况弹操作菜单
      let sortDrag = null;
      const sortOpts = (dragable && document.body.classList.contains('android')) ? {
        onDragStart: (tp) => {
          const listEl = document.getElementById('libList');
          sortDrag = { startY: tp.clientY, lastY: tp.clientY, from: n, to: n, rowH: 46,
                       moved: false, startScroll: listEl ? listEl.scrollTop : 0 };
          // 行间距 = 相邻两行的 top 差（比元素自身高度更准，因为行之间有 gap）
          let step = el.getBoundingClientRect().height || 46;
          const rows = listEl ? [...listEl.children] : [];
          if (rows.length > 1) {
            const d = Math.round(rows[1].getBoundingClientRect().top - rows[0].getBoundingClientRect().top);
            if (d > 10) step = d;
          }
          sortDrag.rowH = step;
          el.classList.add('drag-lift');
          return true;
        },
        onDragMove: (ev) => {
          if (!sortDrag) return;
          const tp = (ev.touches && ev.touches[0]) || { clientY: sortDrag.startY };
          sortDrag.lastY = tp.clientY;
          const listEl = document.getElementById('libList');
          // 拖到上下边缘：列表自动滚动，并在每一帧重算目标位置
          if (listEl) autoScrollDuringDrag(tp.clientY, listEl, () => applySortDrag(el, sortDrag));
          applySortDrag(el, sortDrag);
        },
        onDragEnd: () => {
          stopAutoScroll();
          if (!sortDrag) return;
          const st = sortDrag;
          sortDrag = null;
          el.classList.remove('drag-lift');
          el.style.transform = '';
          markDropLine(-1);
          if (st.moved && st.to !== st.from) {
            // 倒序显示时把显示位置换算回真实索引
            const from = S.plDesc ? (totalItems - 1 - st.from) : st.from;
            const to = S.plDesc ? (totalItems - 1 - st.to) : st.to;
            Playlists.move(S.view, from, to);
            render($('#libSearch').value);
            toast('已移到第 ' + (st.to + 1) + ' 位');
          }
        },
      } : null;
      bindCtx(el, (x, y) => rowMenu(x, y, t, i, n), sortOpts);
      el.addEventListener('click', e => {
        // 单击 = 切歌播放；双击 = 进入多选。Ctrl 加选 / Shift 范围选 在任何模式下都可用。
        if (longPressConsumed(el)) return;
        if (t.offline) {
          toast('本地还没有这首歌 · 右键「下载到本地」或到设置里打开自动下载');
          return;
        }
        if (e.ctrlKey || e.metaKey) {
          S.selSet.has(t.path) ? S.selSet.delete(t.path) : S.selSet.add(t.path);
          S.lastSel = t.path;
          syncSel();
          return;
        }
        if (e.shiftKey && S.lastSel) {
          const all = S.shown.map(x => x.t.path);
          const a = all.indexOf(S.lastSel), b = all.indexOf(t.path);
          if (a >= 0 && b >= 0) {
            S.selSet.clear();
            all.slice(Math.min(a, b), Math.max(a, b) + 1).forEach(pp => S.selSet.add(pp));
          }
          syncSel();
          return;
        }
        if (S.multi) {                       // 多选模式内：单击 = 切换选中
          S.selSet.has(t.path) ? S.selSet.delete(t.path) : S.selSet.add(t.path);
          S.lastSel = t.path;
          syncSel();
          return;
        }
        // 普通模式：单击 = 切歌播放。先等 PLAY_DELAY，判断是不是双击。
        if (playTimer) clearTimeout(playTimer);
        playTimer = setTimeout(() => {
          playTimer = null;
          S.selSet.clear();
          S.lastSel = null;
          syncSel();
          Player.setTrack(i);
        }, PLAY_DELAY);
      });
      el.addEventListener('dblclick', () => {
        if (playTimer) { clearTimeout(playTimer); playTimer = null; }
        if (t.offline) return;
        const wasMulti = S.multi;
        S.multi = true;                     // 进入多选模式
        S.selSet = new Set([t.path]);
        S.lastSel = t.path;
        syncSel();
        if (!wasMulti) toast('已进入多选模式 · 单击继续选择，Esc 退出');
      });
      if (dragable) bindDrag(el, n);
      frag.appendChild(el);
    });
    list.appendChild(frag);

    const total = S.tracks.reduce((a, t) => a + (t.duration || 0), 0);
    const pl = S.view ? Playlists.get(S.view) : null;
    $('#libFoot').textContent = (pl ? `「${pl.name}」 · ` : '') +
      `${S.shown.length} 首 · 总长 ${Math.floor(total / 60)} 分 ${Math.floor(total % 60)} 秒 · ${NOTE}`;
  }

  function rowMenu(x, y, t, gi, n) {
    // 未下载的歌：给下载 / 从歌单移除 / 从曲库移除
    if (t.offline) {
      const menu = [
        { head: '这首歌本地还没有' },
      ];
      if (S.view) {
        menu.push({ sep: true });
        menu.push({ label: '从此歌单移除', action: () => {
          Playlists.removeTrack(S.view, t.path);
          render($('#libSearch').value);
          toast('已从歌单移除');
        }});
      }
      menu.push({ sep: true });
      menu.push({ label: '从曲库移除（文件保留）', danger: true, action: () => removeTracks([t.path]) });
      Ctx.open(x, y, menu);
      return;
    }
    // 右键的行已在多选集合里 → 菜单作用于整个集合；否则重置为这一行
    let targets;
    const multi = S.selSet.has(t.path) && S.selSet.size > 1;
    if (multi) {
      targets = [...S.selSet];
    } else {
      targets = [t.path];
      S.selSet = new Set([t.path]);
      S.lastSel = t.path;
      syncSel();
    }
    const tag = multi ? `（${targets.length} 首）` : '';
    const items = [{ head: '加入歌单' + tag }];
    if (!S.playlists.length) {
      items.push({ label: '还没有歌单', dim: true });
    } else {
      S.playlists.forEach(p => {
        const inIt = p.items.some(it => Playlists.pathOf(it) === t.path);
        items.push({
          label: (targets.every(pp => p.items.some(it => Playlists.pathOf(it) === pp)) ? '✓  ' : '') + p.name,
          action: () => {
            let added = 0;
            targets.forEach(pp => { if (Playlists.addTrack(p.id, pp)) added++; });
            toast(added ? `已加入 ${added} 首到「${p.name}」` : '都已在歌单里');
            render($('#libSearch').value);
          },
        });
      });
    }
    items.push({ label: '＋ 新建歌单并加入', action: () => {
      askName('新建歌单', '', v => {
        const id = Playlists.create(v);
        let added = 0;
        targets.forEach(pp => { if (Playlists.addTrack(id, pp)) added++; });
        toast(`已加入 ${added} 首到「${v}」`);
        render($('#libSearch').value);
      });
    }});
    if (S.view) {
      const pl = Playlists.get(S.view);
      const inPl = pl && pl.items.some(it => Playlists.pathOf(it) === t.path);
      items.push({ sep: true });
      if (inPl) {
        items.push({ label: '从此歌单移除' + tag, action: () => {
          targets.forEach(pp => Playlists.removeTrack(S.view, pp));
          render($('#libSearch').value);
        }});
        items.push({ label: '上移一位', dim: n === 0, action: () => {
          if (n > 0) { Playlists.move(S.view, n, n - 1); render($('#libSearch').value); }
        }});
        items.push({ label: '下移一位', dim: n >= pl.items.length - 1, action: () => {
          if (n < pl.items.length - 1) { Playlists.move(S.view, n, n + 1); render($('#libSearch').value); }
        }});
        if (targets.length === 1) {
          items.push({ label: '移到最前', dim: n === 0, action: () => {
            Playlists.move(S.view, n, 0);
            render($('#libSearch').value);
            toast('已移到最前');
          }});
          items.push({ label: '移到最后', dim: n >= pl.items.length - 1, action: () => {
            Playlists.move(S.view, n, pl.items.length - 1);
            render($('#libSearch').value);
            toast('已移到最后');
          }});
          items.push({ label: '移到指定位置…', action: () => {
            askName('移到第几位（1~' + pl.items.length + '）', String(n + 1), v => {
              let k = parseInt(v, 10);
              if (!isFinite(k)) return;
              k = Math.max(1, Math.min(pl.items.length, k)) - 1;
              Playlists.move(S.view, n, k);
              render($('#libSearch').value);
              toast('已移到第 ' + (k + 1) + ' 位');
            });
          }});
        }
      }
    }
    items.push({ sep: true });
    if (targets.length === 1) {
      items.push({ label: '设置歌曲信息…', action: () => MetaEdit.open(targets[0]) });
    }
    items.push({ label: '从曲库移除' + tag + '（文件保留）', danger: true, action: () => removeTracks(targets) });
    Ctx.open(x, y, items);
  }

  function bindDrag(el, n) {
    el.draggable = true;
    el.addEventListener('dragstart', e => {
      S.dragFrom = n;
      el.classList.add('dragging');
      e.dataTransfer.effectAllowed = 'move';
      try { e.dataTransfer.setData('text/plain', String(n)); } catch (_) {}
    });
    el.addEventListener('dragend', () => {
      stopAutoScroll();
      S.dragFrom = S.dragTo = null;
      el.classList.remove('dragging');
      list.querySelectorAll('.drop-before,.drop-after')
        .forEach(x => x.classList.remove('drop-before', 'drop-after'));
    });
    el.addEventListener('dragover', e => {
      e.preventDefault();
      e.dataTransfer.dropEffect = 'move';
      // 拖到列表上下边缘时自动滚动，长歌单也能拖到远处
      autoScrollDuringDrag(e.clientY, list);
      const r = el.getBoundingClientRect();
      const before = e.clientY < r.top + r.height / 2;
      list.querySelectorAll('.drop-before,.drop-after')
        .forEach(x => x.classList.remove('drop-before', 'drop-after'));
      el.classList.add(before ? 'drop-before' : 'drop-after');
      S.dragTo = before ? n : n + 1;
    });
    el.addEventListener('drop', e => {
      e.preventDefault();
      stopAutoScroll();
      const from = S.dragFrom, to = S.dragTo;
      S.dragFrom = S.dragTo = null;
      if (from == null || to == null || from === to) return;
      Playlists.move(S.view, from, from < to ? to - 1 : to);
      render($('#libSearch').value);
    });
  }

  function renderPlSide() {
    const box = $('#libPls');
    if (!box) return;
    box.innerHTML = '';
    const mk = (label, view, count) => {
      const b = document.createElement('button');
      b.className = 'lib-pl' + ((S.view || null) === view ? ' on' : '');
      b.innerHTML = `<svg viewBox="0 0 24 24"><path d="M4 6h16M4 12h16M4 18h10"/></svg>
        <span class="pl-n">${esc(label)}</span>${count != null ? `<span class="pl-c">${count}</span>` : ''}`;
      b.addEventListener('click', () => {
        if (longPressConsumed(b)) return;
        S.view = view; render($('#libSearch').value);
      });
      bindCtx(b, (x, y) => {
        if (!view || view === '_recent') return;
        Ctx.open(x, y, [
          { label: '导出 m3u…', action: async () => {
            try {
              const r = await api().export_playlist_m3u(view);
              toast((r && r.msg) || (r && r.ok ? '已导出' : '导出失败'), 3600);
            } catch (e) { toast('导出失败'); }
          } },
          { label: '分享给好友…', action: async () => {
            try {
              const fr = await api().friends_list();
              const flist = (fr && fr.list) || [];
              if (!flist.length) { toast('还没有好友，先去「好友与排行」加好友'); return; }
              Ctx.open(x, y, flist.map(f => ({
                label: (f.nick || f.name || ('用户 ' + (f.code || f.id))) +
                       (f.code && String(f.code) !== String(f.nick || '') ? ' · ' + f.code : ''),
                action: async () => {
                  try {
                    const rr = await api().share_playlist(view, f.id);
                    toast((rr && rr.msg) || (rr && rr.ok ? '已分享' : '分享失败'), 3600);
                  } catch (e) { toast('分享失败'); }
                },
              })));
            } catch (e) { toast('分享失败'); }
          } },
          { sep: true },
          { label: '重命名', action: () => askName('重命名歌单', label, v => {
            Playlists.rename(view, v); render($('#libSearch').value); }) },
          { label: '按标题排序', action: () => { Playlists.sortById(view, 'title'); render($('#libSearch').value); } },
          { label: '按歌手排序', action: () => { Playlists.sortById(view, 'artist'); render($('#libSearch').value); } },
          { sep: true },
          { label: '删除歌单', danger: true, action: () => {
            Playlists.remove(view);
            render($('#libSearch').value);
            toast('已删除歌单（歌曲保留在曲库）');
          }},
        ]);
      });
      return b;
    };
    box.appendChild(mk('全部音乐', null, S.tracks.length));
    box.appendChild(mk('最近播放', '_recent', (S.recent || []).length));
    S.playlists.forEach(p => box.appendChild(mk(p.name, p.id, p.items.length)));
  }

  function markCurrent() {
    [...list.children].forEach((el, k) => {
      const item = S.shown[k];
      el.classList.toggle('cur', !!item && item.i === S.idx);
    });
  }

  function currentList() { return viewTracks(); }

  return { render, markCurrent, currentList, renderPlSide };
})();

/* ══════════════════════════════════════════
   七、EQ 曲线
   ══════════════════════════════════════════ */
const EqCurve = (() => {
  const cv = $('#eqCurve'), c = cv.getContext('2d');
  function draw() {
    const W = cv.width, H = cv.height;
    c.clearRect(0, 0, W, H);
    const pad = 6;
    // 网格
    c.lineWidth = 1;
    for (let i = 0; i <= 4; i++) {
      const y = pad + (H - pad * 2) * i / 4;
      c.strokeStyle = i === 2 ? 'rgba(243,238,231,.16)' : 'rgba(243,238,231,.06)';
      c.beginPath(); c.moveTo(0, y); c.lineTo(W, y); c.stroke();
    }
    // 频段刻度
    c.fillStyle = 'rgba(243,238,231,.18)';
    c.font = '9px sans-serif';
    ['60', '250', '1k', '4k', '16k'].forEach((lb, i) => {
      c.fillText(lb, 4 + i * (W - 30) / 4, H - 3);
    });
    const [lo, mid, hi] = S.eq;
    const pts = [];
    for (let i = 0; i <= 60; i++) {
      const x = i / 60;
      // 简化响应曲线：低频端点、中频钟形、高频端点
      const lowW = Math.max(0, 1 - x / 0.34);
      const hiW = Math.max(0, (x - 0.62) / 0.38);
      const midW = Math.exp(-Math.pow((x - 0.45) / 0.20, 2));
      const g = lo * Math.pow(lowW, 1.6) + mid * midW + hi * Math.pow(hiW, 1.5);
      pts.push([x * W, H / 2 - g / 12 * (H / 2 - pad)]);
    }
    const grad = c.createLinearGradient(0, 0, W, 0);
    grad.addColorStop(0, 'rgba(156,140,234,1)');
    grad.addColorStop(.45, 'rgba(228,194,154,1)');
    grad.addColorStop(1, 'rgba(255,241,214,1)');
    // 先画一层宽光晕
    c.beginPath();
    pts.forEach(([x, y], i) => i ? c.lineTo(x, y) : c.moveTo(x, y));
    c.strokeStyle = 'rgba(228,194,154,.22)';
    c.lineWidth = 9;
    c.shadowColor = 'rgba(228,194,154,.55)';
    c.shadowBlur = 18;
    c.stroke();
    // 主线
    c.beginPath();
    pts.forEach(([x, y], i) => i ? c.lineTo(x, y) : c.moveTo(x, y));
    c.strokeStyle = grad;
    c.lineWidth = 3.2;
    c.shadowBlur = 8;
    c.stroke();
    c.shadowBlur = 0;
    // 三个频段标记点
    [[0, lo], [0.45, mid], [1, hi]].forEach(([x, g]) => {
      const y = H / 2 - g / 12 * (H / 2 - pad);
      c.beginPath();
      c.arc(x * W, y, 3.4, 0, Math.PI * 2);
      c.fillStyle = '#FFF3DF';
      c.fill();
    });
    // 填充
    c.lineTo(W, H / 2); c.lineTo(0, H / 2); c.closePath();
    const fg = c.createLinearGradient(0, 0, 0, H);
    fg.addColorStop(0, 'rgba(228,194,154,.14)');
    fg.addColorStop(1, 'rgba(228,194,154,0)');
    c.fillStyle = fg; c.fill();
  }
  return { draw };
})();

/* ══════════════════════════════════════════
   八、面板 / 交互绑定
   ══════════════════════════════════════════ */
const EQ_PRESETS = {
  flat: [0, 0, 0],
  warm: [4, -1, -2],
  clear: [-2, 1, 4],
  night: [3, 1, -4],
  vocal: [-3, 4, -1],
};

/* 双向滑杆：从中线向当前值填充 */
function bipolarVar(el, val, min = -12, max = 12) {
  const p = (val - min) / (max - min) * 100;
  const mid = (0 - min) / (max - min) * 100;
  el.style.setProperty('--lo', Math.min(p, mid) + '%');
  el.style.setProperty('--hi', Math.max(p, mid) + '%');
}

function applyPreset(name) {
  S.eqPreset = name;
  S.eq = [...EQ_PRESETS[name]];
  $$('.eq-presets button').forEach(b => b.classList.toggle('on', b.dataset.eq === name));
  $('#eqPresetName').textContent = ({ flat: '原声', warm: '温暖', clear: '清澈', night: '深夜', vocal: '人声' })[name];
  $$('.slider.eq').forEach((el, i) => {
    el.value = S.eq[i];
    bipolarVar(el, S.eq[i]);
    el.parentElement.querySelector('em').textContent = (S.eq[i] > 0 ? '+' : '') + S.eq[i];
  });
  Engine.applyEQ();
  EqCurve.draw();
  const pill = $('#pillQuality');
  pill.innerHTML = `<span class="p-dot"></span>${({ flat: '原声', warm: '温暖', clear: '清澈', night: '深夜', vocal: '人声' })[name]}`;
  pill.classList.toggle('accent', name !== 'flat');
  saveConfig();
}

function setMode(m) {
  if (!['order', 'shuffle', 'one'].includes(m)) m = 'order';
  S.mode = m;
  S.shuffleBag = [];
  updateModeUI();
  saveConfig();
}
function cycleMode() {
  const order = ['order', 'shuffle', 'one'];
  setMode(order[(order.indexOf(S.mode) + 1) % 3]);
  toast({ order: '按顺序播放', shuffle: '随机播放', one: '单曲循环' }[S.mode], 1400);
}
function updateModeUI() {
  const btn = $('#btnMode');
  if (!btn) return;
  btn.classList.remove('m-shuffle', 'm-one');
  if (S.mode === 'shuffle') btn.classList.add('m-shuffle');
  if (S.mode === 'one') btn.classList.add('m-one');
  btn.title = { order: '播放模式：顺序播放（点击切换）',
                shuffle: '播放模式：随机播放（点击切换）',
                one: '播放模式：单曲循环（点击切换）' }[S.mode];
  btn.classList.toggle('on', S.mode !== 'order');
  const t = S.tracks[S.idx];
  $('#tDur').textContent = t && t.duration ? fmt(t.duration) : $('#tDur').textContent;
}

function updateSelBar() {
  const bar = $('#selBar');
  if (!bar) return;
  bar.classList.toggle('on', S.selSet.size > 0 || S.multi);
  $('#selCount').textContent = S.selSet.size;
  $('#selRmPl').style.display = S.view ? '' : 'none';
  const m = $('#selMode');
  if (m) m.classList.toggle('on', S.multi);
}

function clearSel() {
  S.selSet.clear();
  S.lastSel = null;
  S.multi = false;
  updateSelBar();
  Queue.render($('#libSearch').value);
}

function setView(mode) {
  const lib = mode === 'lib';
  document.body.classList.toggle('lib-mode', lib);
  const lv = $('#library');
  if (lv) lv.setAttribute('aria-hidden', String(!lib));
  S.libOpen = lib;
  if (lib) {
    Queue.render($('#libSearch') ? $('#libSearch').value : '');
    updateNowBar();
  }
}

/* ── 电脑版：首页 + 左侧导航 ── */
function syncRail(target) {
  const rail = document.getElementById('pcRail');
  if (!rail) return;
  rail.querySelectorAll('.rail-btn').forEach(function (b) {
    b.classList.toggle('on', b.dataset.go === target);
  });
}

function showPcHome(on) {
  document.body.classList.toggle('pc-home', !!on);
  if (on) {
    try { if (document.body.classList.contains('set-mode')) Settings.close(); } catch (e) {}
    try { setView('play'); } catch (e) {}
    syncRail('home');
  }
}

function pcNavigate(target) {
  if (document.body.classList.contains('android')) return;
  // 电脑版整屏界面互斥：先全部关掉，再打开目标界面（不依赖各模块的 open/close 状态）
  document.body.classList.remove('pc-home', 'online-mode', 'lib-mode', 'set-mode', 'admin-mode', 'friends-mode');
  ['library', 'onlineView', 'settingsView', 'adminView', 'friendsView'].forEach(function (id) {
    var el = document.getElementById(id);
    if (el) el.setAttribute('aria-hidden', 'true');
  });
  S.libOpen = false;
  S.setOpen = false;
  if (target === 'home') { showPcHome(true); return; }
  syncRail(target);
  if (target === 'music') {
    setView('play');
  } else if (target === 'lib') {
    setView('lib');
    S.libOpen = true;
  } else if (target === 'search') {
  } else if (target === 'settings') {
    document.body.classList.add('set-mode');
    S.setOpen = true;
    var sv = document.getElementById('settingsView');
    if (sv) sv.setAttribute('aria-hidden', 'false');
  } else if (target === 'admin') {
    AdminPage.open();
  } else if (target === 'friends') {
    FriendsPage.open();
  }
}

function updateNowBar() {
  const t = S.tracks[S.idx];
  $('#lnName').textContent = t ? (t.title || t.name) : '—';
  $('#lnSub').textContent = t ? (t.artist || '未知艺术家') : '';
  const art = $('#lnArt');
  if (art) {
    if (!t) {
      art.classList.remove('has-cover');
      art.style.backgroundImage = 'none';
    } else {
      const url = `/cover?p=${encodeURIComponent(t.path)}`;
      const probe = new Image();
      probe.onload = () => {
        art.style.backgroundImage = `url("${url}")`;
        art.classList.add('has-cover');
      };
      probe.onerror = () => {
        art.classList.remove('has-cover');
        art.style.backgroundImage = 'none';
      };
      probe.src = url;
    }
  }
  $('#lnPlay').classList.toggle('playing', S.playing);
}

function openSheet(id) {
  closeSheets(true);
  const el = $('#' + id);
  el.classList.add('open');
  if (id === 'tuneSheet') { S.tuneOpen = true; $('#btnTune').classList.add('on'); EqCurve.draw(); }
  $('#scrim').classList.add('on');
}
function closeSheets(silent) {
  $$('.sheet').forEach(s => s.classList.remove('open'));
  $('#scrim').classList.remove('on');
  S.tuneOpen = false;
  $('#btnTune').classList.remove('on');
}

/* ── 配置 ── */
let saveTimer = null;
function saveConfig() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    const a = api(); if (!a) return;
    a.save_config({
      volume: S.volume, speed: S.speed, pitch: S.pitch,
      eq: S.eq, eqPreset: S.eqPreset, width: S.width,
      mode: S.mode,
      lyrOn: S.lyrOn, vizOn: S.vizOn, crossfade: S.crossfade,
      folder: S.folder, view: S.view,
      lastPath: S.tracks[S.idx]?.path || '',
      wallMode: S.wallMode, wallIdx: S.wallIdx,
      wallOn: S.wallOn, wallBright: S.wallBright, wallBlur: S.wallBlur, lyrColor: S.lyrColor, lyrSize: S.lyrSize, downloadDir: S.downloadDir, plDesc: S.plDesc,
      autoDownloadMissing: S.autoDownloadMissing,
      theme: S.theme,
      sleepMode: S.sleepMode, sleepVal: S.sleepVal,
      wxRoot: S.wxRoot,
    });
  }, 500);
}

/* ── 主题（黑夜 / 白昼） ── */
function applyTheme() {
  const light = S.theme === 'light';
  document.body.classList.toggle('light', light);
  document.documentElement.classList.toggle('light', light);
  try {
    if (window.parent && window.parent !== window) {
      window.parent.postMessage({ type: 'luvsic-theme', theme: S.theme }, '*');
    }
  } catch (e) {}
}

/* range 滑杆的已填充部分：桌面版靠 --p / --lo --hi 画进度，新加的滑杆也得同步 */
function syncRangeFill(el) {
  if (!el || el.type !== 'range') return;
  const min = isFinite(+el.min) ? +el.min : 0;
  const max = isFinite(+el.max) ? +el.max : 100;
  const v = isFinite(+el.value) ? +el.value : min;
  const span = Math.max(1, max - min);
  const p = ((v - min) / span) * 100;
  el.style.setProperty('--p', p.toFixed(1) + '%');
  if (el.classList.contains('bipolar')) {
    const zero = ((0 - min) / span) * 100;
    el.style.setProperty('--lo', Math.min(p, zero).toFixed(1) + '%');
    el.style.setProperty('--hi', Math.max(p, zero).toFixed(1) + '%');
  }
}

function bindRangeFills(root) {
  (root || document).querySelectorAll('input[type="range"]').forEach(el => {
    if (el.dataset.fillBound) return;
    el.dataset.fillBound = '1';
    el.addEventListener('input', () => syncRangeFill(el));
    el.addEventListener('change', () => syncRangeFill(el));
    syncRangeFill(el);
  });
}

/* 歌词颜色：空=跟随主题；自定义时把颜色转成一组 CSS 变量（暗主题淡、亮主题深） */
function hexToRgb(hex) {
  if (!hex) return null;
  const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim());
  if (!m) return null;
  const v = parseInt(m[1], 16);
  return [(v >> 16) & 255, (v >> 8) & 255, v & 255];
}

function applyLyricColor() {
  const bs = document.body.style;
  const props = ['--lyr-fg', '--lyr-fg-hover', '--lyr-on', '--lyr-glow', '--lyr-glow2', '--lyr-trans'];
  const rgb = hexToRgb(S.lyrColor || '');
  if (!rgb) {
    props.forEach(p => bs.removeProperty(p));
    try {
      if (window.LuvsicNative && window.LuvsicNative.setLyricColor) {
        window.LuvsicNative.setLyricColor('');
      } else if (window.parent && window.parent !== window) {
        window.parent.postMessage({ type: 'luvsic-lyrcolor', color: '' }, '*');
      }
    } catch (e) {}
    return;
  }
  const [r, g, b] = rgb;
  const rgba = a => `rgba(${r},${g},${b},${a})`;
  bs.setProperty('--lyr-fg', rgba(0.46));
  bs.setProperty('--lyr-fg-hover', rgba(0.86));
  bs.setProperty('--lyr-on', `rgb(${r},${g},${b})`);
  bs.setProperty('--lyr-glow', rgba(0.42));
  bs.setProperty('--lyr-glow2', rgba(0.13));
  bs.setProperty('--lyr-trans', rgba(0.36));
  try {
    if (window.LuvsicNative && window.LuvsicNative.setLyricColor) {
      window.LuvsicNative.setLyricColor(S.lyrColor);
    } else if (window.parent && window.parent !== window) {
      window.parent.postMessage({ type: 'luvsic-lyrcolor', color: S.lyrColor }, '*');
    }
  } catch (e) {}
}

/* 歌词字号：0=自动（桌面17px / 手机14px），>0 时用自定义 px，同时同步悬浮窗 */
function applyLyricSize() {
  const n = +S.lyrSize || 0;
  if (n > 0) document.body.style.setProperty('--lyr-size', n + 'px');
  else document.body.style.removeProperty('--lyr-size');
  try {
    if (window.LuvsicNative && window.LuvsicNative.setLyricSize) {
      window.LuvsicNative.setLyricSize(n);
    } else if (window.parent && window.parent !== window) {
      window.parent.postMessage({ type: 'luvsic-lyrsize', size: n }, '*');
    }
  } catch (e) {}
}

/* 切回 Luvsic 原生系统壁纸（不用照片）：关掉壁纸层，恢复渐变背景 */
function useSystemWall() {
  S.wallOn = false;
  S.customWallUrl = '';
  S.wallMode = 'off';
  document.body.classList.remove('wall-on');
  ['bgWallA', 'bgWallB'].forEach(function (id) {
    const el = document.getElementById(id);
    if (el) { el.classList.remove('show'); el.style.backgroundImage = ''; }
  });
  const bgEl = document.getElementById('bg');
  if (bgEl) bgEl.style.backgroundImage = '';
  const appEl = document.getElementById('app');
  if (appEl) appEl.style.backgroundImage = '';
  try { Wallpapers.setMode('off'); } catch (e) {}
  try { Settings.renderChips(); Settings.renderWalls(); } catch (e) {}
  saveConfig();
  try {
    if (window.parent && window.parent !== window) {
      window.parent.postMessage({ type: 'luvsic-wall-off' }, '*');
    }
  } catch (e) {}
  toast('已切换为系统壁纸');
}

/* 后台保活：暂停超时退出时间（分钟，0=不退出） */
function bgTimeoutMin() {
  try {
    var v = localStorage.getItem('luvsic_bg_timeout');
    if (v === null || v === '') return 30;
    var n = parseInt(v, 10);
    return isFinite(n) ? n : 30;
  } catch (e) { return 30; }
}

/* 系统媒体控制：把当前播放状态推给安卓原生（通知栏/锁屏/控制中心） */
let mediaPushAt = 0;
function pushMedia(force) {
  try {
    const now = Date.now();
    if (!force && now - mediaPushAt < 900) return;
    mediaPushAt = now;
    const t = S.tracks[S.idx] || null;
    const audioEl = (typeof Engine !== 'undefined' && Engine.audio) ? Engine.audio : null;
    const st = {
      title: t ? (t.title || t.name || '') : '',
      artist: t ? (t.artist || '') : '',
      coverPath: t ? (t.path || '') : '',
      playing: !!S.playing,
      positionMs: audioEl ? Math.round((audioEl.currentTime || 0) * 1000) : 0,
      durationMs: audioEl && isFinite(audioEl.duration)
        ? Math.round(audioEl.duration * 1000)
        : Math.round(((t && t.duration) || 0) * 1000),
      timeoutMin: bgTimeoutMin()
    };
    if (window.LuvsicNative && window.LuvsicNative.updatePlayback) {
      window.LuvsicNative.updatePlayback(JSON.stringify(st));
    } else if (window.parent && window.parent !== window) {
      window.parent.postMessage({ type: 'luvsic-media', state: st }, '*');
    }
  } catch (e) {}
}

/* 播放中每 3 秒把进度推给原生：后台 JS 被冻结时，保活服务靠它推算播完时间并主动切歌 */
setInterval(function () {
  try { if (S.playing) pushMedia(true); } catch (e) {}
}, 3000);

/* 下载目录显示 */
function updateDlLabel() {
  var el = document.getElementById('dlDirVal');
  if (!el) return;
  el.textContent = (S.downloadDir || '').trim() || '跟随曲库目录';
  el.title = S.downloadDir || '';
}

/* 版本号：安卓壳把真实安装版本推过来后显示 */
window.addEventListener('message', function (e) {
  try {
    if (e.data && e.data.type === 'luvsic-version') {
      var el = document.getElementById('curVersion');
      if (el) el.textContent = 'v' + e.data.version;
    }
  } catch (err) {}
});
/* 桌面版没有安卓壳，版本号显示为「桌面版」而不是一直 v— */
setTimeout(function () {
  try {
    var el = document.getElementById('curVersion');
    if (!el) return;
    if (el.textContent === 'v—' && !document.body.classList.contains('android')) {
      el.textContent = '桌面版';
    }
  } catch (e) {}
}, 2200);

/* 壁纸亮度/模糊：同时作用于页面内的壁纸层和外层壳 */
function applyWallFilter() {  const b = (S.wallBright || 110) / 100;
  const blur = S.wallBlur || 0;
  const f = `brightness(${b}) saturate(1.08) blur(${blur}px)`;
  document.body.style.setProperty('--wall-filter', f);
  try {
    if (window.parent && window.parent !== window) {
      window.parent.postMessage({ type: 'luvsic-wall', filter: f }, '*');
    }
  } catch (e) {}
}

function setTheme(t) {
  S.theme = t === 'light' ? 'light' : 'dark';
  applyTheme();
  document.querySelectorAll('#settingsView [data-theme]').forEach(c => {
    c.classList.toggle('on', c.dataset.theme === S.theme);
  });
  saveConfig();
}

/* 把当前歌词行推给安卓悬浮窗 */
/* 每次打开 App：把登录时间刷到云端（管理员界面能看到最近打开时间） */
setTimeout(function () {
  try {
    if (typeof api !== 'function') return;
    api().login_touch().catch(function () {});
  } catch (e) {}
}, 6000);

function pushOverlayLyric() {
  try {
    if (!window.LuvsicNative || !window.LuvsicNative.setLyric) return;
    const t = S.tracks[S.idx] || {};
    let text = '';
    const kids = document.querySelectorAll('#lyrScroll .lyr-line, .lyr-line');
    const act = document.querySelector('.lyr-line.active');
    if (act) text = (act.textContent || '').trim();
    window.LuvsicNative.setLyric(JSON.stringify({
      text: text,
      title: t.title || '',
      artist: t.artist || '',
      playing: !!S.playing,
    }));
  } catch (e) {}
}

function restoreConfig(cfg) {
  if (!cfg) return;
  S.volume = cfg.volume ?? 82;
  S.speed = cfg.speed ?? 1;
  S.pitch = cfg.pitch ?? 0;
  S.width = cfg.width ?? 50;
  S.eq = cfg.eq || [0, 0, 0];
  S.eqPreset = cfg.eqPreset || 'flat';
  S.mode = cfg.mode || (cfg.shuffle ? 'shuffle' : (cfg.loop === 'one' ? 'one' : 'order'));
  S.lyrOn = cfg.lyrOn !== false;
  S.vizOn = cfg.vizOn !== false;
  S.crossfade = cfg.crossfade !== false;
  S.folder = cfg.folder || '';
  S.lastPath = cfg.lastPath || '';
  S.view = cfg.view || null;
  S.wallMode = cfg.wallMode || 'cycle';
  S.wallIdx = cfg.wallIdx || 1;
  S.wallOn = !!cfg.wallOn;
  S.wallBright = cfg.wallBright ? +cfg.wallBright : 110;
  S.wallBlur = cfg.wallBlur ? +cfg.wallBlur : 0;
  S.lyrColor = cfg.lyrColor || '';
  S.downloadDir = cfg.downloadDir || '';
  updateDlLabel();
  S.lyrSize = +cfg.lyrSize || 0;
  applyWallFilter();
  applyLyricColor();
  applyLyricSize();
  // 把配置里的值回填到滑杆（否则滑杆停在默认位置、进度条也不对）
  try {
    const wbEl = document.getElementById('wallBright');
    const wblEl = document.getElementById('wallBlur');
    if (wbEl) { wbEl.value = S.wallBright; syncRangeFill(wbEl); }
    if (wblEl) { wblEl.value = S.wallBlur; syncRangeFill(wblEl); }
    const lb = document.getElementById('wallBrightV');
    if (lb) lb.textContent = S.wallBright + '%';
    const lb2 = document.getElementById('wallBlurV');
    if (lb2) lb2.textContent = S.wallBlur + 'px';
    const lszEl = document.getElementById('lyrSize');
    if (lszEl) { lszEl.value = S.lyrSize || 0; syncRangeFill(lszEl); }
    const lszV = document.getElementById('lyrSizeV');
    if (lszV) lszV.textContent = (S.lyrSize > 0 ? S.lyrSize + 'px' : '自动');
  } catch (e) {}
  S.plDesc = !!cfg.plDesc;
  S.autoDownloadMissing = !!cfg.autoDownloadMissing;
  S.theme = cfg.theme === 'light' ? 'light' : 'dark';
  applyTheme();
  if (S.wallOn) { try { document.body.classList.add('wall-on'); } catch (e) {} }
  S.sleepMode = cfg.sleepMode || 'off';
  S.sleepVal = cfg.sleepVal || 30;
  S.wxRoot = cfg.wxRoot || '';
  if (S.sleepVal < 1) S.sleepVal = 30;

  Engine.setVolume(S.volume);
  Engine.setSpeed(S.speed);
  Engine.setPitch(S.pitch);
  Engine.setWidth(S.width);
  applyPreset(S.eqPreset);
  // 若自定义 EQ 与预设不同
  if (cfg.eq && JSON.stringify(cfg.eq) !== JSON.stringify(EQ_PRESETS[S.eqPreset])) {
    S.eq = cfg.eq;
    $$('.slider.eq').forEach((el, i) => {
      el.value = S.eq[i];
      bipolarVar(el, S.eq[i]);
      el.parentElement.querySelector('em').textContent = (S.eq[i] > 0 ? '+' : '') + S.eq[i];
    });
    $('#eqPresetName').textContent = '自定义';
    EqCurve.draw(); Engine.applyEQ();
  }
  updateModeUI();
  $('#swLyrics').classList.toggle('on', S.lyrOn);
  $('#swViz').classList.toggle('on', S.vizOn);
  $('#swCrossfade').classList.toggle('on', S.crossfade);
  try { Settings.applyFromConfig(); } catch (e) {}
}

/* ── 事件绑定 ── */
function bindUI() {
  // 窗口（宿主系统标题栏提供按钮；这里保留接口备用）
  const bindIf = (sel, fn) => { const el = $(sel); if (el) el.onclick = fn; };
  bindIf('#wMin', () => api().min_window());
  bindIf('#wMax', () => api().max_window());
  bindIf('#wClose', () => api().close_window());

  // 标题栏拖动：交给 Windows 原生拖动循环（零延迟）
  const drag = $('#tbDrag');
  const onDragStart = e => {
    if (e.button !== 0) return;
    e.preventDefault();
    api().start_drag();
  };
  drag.addEventListener('pointerdown', onDragStart);
  Array.from(drag.parentElement.children).forEach(el => {
    if (el.classList.contains('tb-mark') || el.id === 'tbDrag') {
      el.addEventListener('pointerdown', onDragStart);
      el.style.cursor = 'default';
    }
  });

  // 播放控制
  $('#btnPlay').onclick = () => Player.toggle();
  $('#btnNext').onclick = () => Player.next();
  $('#btnPrev').onclick = () => Player.prev();
  $('#btnMode').onclick = () => cycleMode();

  // 音量
  const vol = $('#volume');
  vol.value = S.volume;
  vol.style.setProperty('--p', S.volume + '%');
  vol.oninput = () => {
    S.muted = false;
    Engine.setVolume(+vol.value);
    saveConfig();
  };
  $('#btnMute').onclick = () => Engine.toggleMute();

  // 面板与视图
  $('#btnQueue').onclick = () => setView(S.libOpen ? 'play' : 'lib');
  $('#dockOpen').onclick = () => setView('lib');
  $('#btnTune').onclick = () => S.tuneOpen ? closeSheets() : openSheet('tuneSheet');
  $('#dockSet').onclick = () => S.tuneOpen ? closeSheets() : openSheet('tuneSheet');
  $('#btnSettings').onclick = () => Settings.toggle();
  $('#scrim').onclick = () => closeSheets();
  $$('[data-close]').forEach(b => b.onclick = () => closeSheets());
  $('#pillQuality').onclick = () => openSheet('tuneSheet');
  $('#pillSpeed').onclick = () => openSheet('tuneSheet');
  $('#pillPitch').onclick = () => openSheet('tuneSheet');

  // 曲库编辑视图
  $('#libSearch').oninput = e => Queue.render(e.target.value);
  $('#libBack').onclick = () => setView('play');
  $('#libPickFolder').onclick = pickFolder;
  $('#libPickFiles').onclick = pickFiles;
  $('#libRescan').onclick = async () => {
    // 扫描后先让用户勾选，只有勾选的才进曲库（电脑/手机一致）
    if (!S.folder) return pickFolder();
    toast('重新扫描中…', 4000);
    Picker.open(S.folder);
  };
  // 选歌入库面板
  const pickSearchEl = $('#pickSearch');
  if (pickSearchEl) pickSearchEl.oninput = () => Picker.render();
  const pickAllEl = $('#pickAll');
  if (pickAllEl) pickAllEl.onclick = () => Picker.setAll(true);
  const pickNoneEl = $('#pickNone');
  if (pickNoneEl) pickNoneEl.onclick = () => Picker.setAll(false);
  const pickSaveEl = $('#pickSave');
  if (pickSaveEl) pickSaveEl.onclick = () => Picker.save();
  // 歌曲信息编辑
  const metaSaveEl = $('#metaSave');
  if (metaSaveEl) metaSaveEl.onclick = () => MetaEdit.save();
  // 联网补全未知歌手（电脑/手机都支持）
  const fixMetaEl = $('#libFixMeta');
  if (fixMetaEl) fixMetaEl.onclick = () => runMetaFix(fixMetaEl);
  $('#libNew').onclick = () => askName('新建歌单', '', v => {
    S.view = Playlists.create(v);
    Queue.render($('#libSearch').value);
  });
  // 电脑版左侧导航 + 首页卡片
  document.querySelectorAll('#pcRail .rail-btn').forEach(function (b) {
    b.onclick = function () { pcNavigate(b.dataset.go); };
  });
  document.querySelectorAll('#pcHome .pc-card').forEach(function (b) {
    b.onclick = function () { pcNavigate(b.dataset.go); };
  });
  $('#lnPlay').onclick = () => Player.toggle();
  // 点迷你条（播放按钮除外）返回歌词界面
  const nowBar = document.querySelector('.lib-now');
  if (nowBar) nowBar.addEventListener('click', e => {
    if (e.target.closest('#lnPlay')) return;
    setView('play');
  });
  $('#selClear').onclick = () => clearSel();
  $('#selRmLib').onclick = () => { const ps = [...S.selSet]; if (ps.length) removeTracks(ps); };
  $('#selRmPl').onclick = () => {
    if (!S.view) return;
    const ps = [...S.selSet];
    if (!ps.length) return;
    ps.forEach(pp => Playlists.removeTrack(S.view, pp));
    toast(`已移出 ${ps.length} 首`);
    Queue.render($('#libSearch').value);
  };
  $('#selAdd').onclick = e => {
    const ps = [...S.selSet];
    if (!ps.length) return;
    const items = [{ head: `把 ${ps.length} 首加入歌单` }];
    if (!S.playlists.length) items.push({ label: '还没有歌单', dim: true });
    S.playlists.forEach(p => items.push({
      label: p.name,
      action: () => {
        let n = 0;
        ps.forEach(pp => { if (Playlists.addTrack(p.id, pp)) n++; });
        toast(n ? `已加入 ${n} 首到「${p.name}」` : '都已在歌单里');
        Queue.render($('#libSearch').value);
      },
    }));
    items.push({ label: '＋ 新建歌单并加入', action: () => askName('新建歌单', '', v => {
      const id = Playlists.create(v);
      let n = 0;
      ps.forEach(pp => { if (Playlists.addTrack(id, pp)) n++; });
      toast(`已加入 ${n} 首到「${v}」`);
      Queue.render($('#libSearch').value);
    })});
    Ctx.open(e.clientX, e.clientY, items);
  };

  // EQ
  $$('.eq-presets button').forEach(b => b.onclick = () => applyPreset(b.dataset.eq));
  $$('.slider.eq').forEach((el, i) => {
    el.oninput = () => {
      S.eq[i] = +el.value;
      bipolarVar(el, +el.value);
      el.parentElement.querySelector('em').textContent = (+el.value > 0 ? '+' : '') + el.value;
      $$('.eq-presets button').forEach(b => b.classList.remove('on'));
      $('#eqPresetName').textContent = '自定义';
      Engine.applyEQ(); EqCurve.draw(); saveConfig();
    };
  });

  // 宽度
  const stw = $('#stWidth');
  stw.oninput = () => { Engine.setWidth(+stw.value); saveConfig(); };

  // 速度
  const sp = $('#speedRange');
  sp.oninput = () => { Engine.setSpeed(+sp.value / 100); saveConfig(); };
  $$('[data-speed]').forEach(b => b.onclick = () => {
    Engine.setSpeed(+b.dataset.speed); saveConfig();
  });

  // 调式
  const pr = $('#pitchRange');
  pr.oninput = () => { Engine.setPitch(+pr.value); saveConfig(); };
  $$('[data-pitch]').forEach(b => b.onclick = () => {
    Engine.setPitch(+b.dataset.pitch); saveConfig();
  });

  // 开关
  $('#swLyrics').onclick = e => {
    S.lyrOn = !S.lyrOn;
    e.currentTarget.classList.toggle('on', S.lyrOn);
    if (!S.lyrOn) { Lyrics.clear(); toast('已关闭在线补词', 1300); }
    else if (S.tracks[S.idx]) Lyrics.load(S.tracks[S.idx]);
    saveConfig();
  };
  $('#swViz').onclick = e => {
    S.vizOn = !S.vizOn;
    e.currentTarget.classList.toggle('on', S.vizOn);
    Art.setPlaying(S.playing);
    saveConfig();
  };
  $('#swCrossfade').onclick = e => {
    S.crossfade = !S.crossfade;
    e.currentTarget.classList.toggle('on', S.crossfade);
    saveConfig();
  };
  $('#btnClearLyrics').onclick = async () => {
    const n = await api()?.clear_lyrics_cache();
    S.lyricsCache = {};
    toast(`已清空 ${n || 0} 份歌词缓存`);
  };
  $('#lyrAlt').onclick = () => {
    const t = S.tracks[S.idx];
    if (!t) return;
    Lyrics.alt(t);
  };
  $('#lyrRefresh').onclick = () => {
    const t = S.tracks[S.idx];
    if (!t) return;
    delete S.lyricsCache[t.path];
    Lyrics.load(t, true);
    toast('正在重新匹配歌词…', 1600);
  };

  // 快捷键
  document.addEventListener('keydown', e => {
    if (e.target.tagName === 'INPUT') return;
    switch (e.code) {
      case 'Space': e.preventDefault(); Player.toggle(); break;
      case 'ArrowRight': if (e.shiftKey) Player.next(); else Engine.audio.currentTime += 5; break;
      case 'ArrowLeft': if (e.shiftKey) Player.prev(); else Engine.audio.currentTime -= 5; break;
      case 'ArrowUp': e.preventDefault(); Engine.setVolume(Math.min(100, S.volume + 5)); saveConfig(); break;
      case 'ArrowDown': e.preventDefault(); Engine.setVolume(Math.max(0, S.volume - 5)); saveConfig(); break;
      case 'KeyM': Engine.toggleMute(); break;
      case 'KeyA': if ((e.ctrlKey||e.metaKey) && S.libOpen) { e.preventDefault(); S.multi = true; S.selSet = new Set(viewTracks().map(x => x.t.path)); updateSelBar(); Queue.render($('#libSearch').value); } break;
      case 'KeyL': cycleMode(); break;
    }
  });

  // 滚轮调音量（在封面上）
  $('#artBox').addEventListener('wheel', e => {
    e.preventDefault();
    const d = e.deltaY > 0 ? -4 : 4;
    Engine.setVolume(Math.max(0, Math.min(100, S.volume + d)));
    saveConfig();
  }, { passive: false });

}

/* ── 曲库扫描 ── */
async function scan(folder, silent) {
  const a = api(); if (!a) return;
  const res = await a.scan_folder(folder);
  if (!res || !res.ok) {
    if (!silent) toast(res?.msg || '扫描失败');
    return;
  }
  S.folder = folder;
  S.tracks = res.tracks || [];
  Queue.render();
  if (!S.tracks.length) { toast('这个文件夹里没有音频文件'); return; }
  if (!silent) toast(`已载入 ${S.tracks.length} 首`);
  // 恢复上次播放
  const last = S.lastPath;
  let start = 0;
  if (last) {
    const k = S.tracks.findIndex(t => t.path === last);
    if (k >= 0) start = k;
  }
  if (S.idx < 0) Player.setTrack(start, false);
  saveConfig();
}

async function pickFolder() {
  const a = api(); if (!a) return;
  const folder = await a.pick_folder();
  if (!folder) return;
  try { await a.set_folder(folder); } catch (e) {}
  S.folder = folder;
  toast('正在扫描…', 4000);
  Picker.open(folder);
}

async function pickFiles() {
  const a = api(); if (!a) return;
  const files = await a.pick_files();
  if (!files || !files.length) return;
  // 用文件所在目录作为曲库
  const dir = files[0].replace(/[\\/][^\\/]+$/, '');
  try { await a.set_folder(dir); } catch (e) {}
  S.folder = dir;
  Picker.open(dir);
}

/* ══════════════════════════════════════════
   九、启动
   ══════════════════════════════════════════ */
/* ══════════════════════════════════════════
   背景壁纸：轮换 / 固定单张 / 自定义图片
   ══════════════════════════════════════════ */
const Wallpapers = (function () {
  const TOTAL = 7;
  const INTERVAL = 22000;          // 每张停留时长
  let A, B, front, timer = null, order = [];
  let mode = 'cycle';              // cycle | single | custom
  let idx = 1;                     // single 模式的固定张

  function url(i) { return `/wall/wp${i}.jpg?v=3`; }
  function currentSrc() {
    if (mode === 'custom') return S.customWallUrl || url(1);
    if (mode === 'single') return url(idx);
    return null;
  }

  function shuffle() {
    order = [];
    for (let i = 1; i <= TOTAL; i++) order.push(i);
    for (let i = order.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [order[i], order[j]] = [order[j], order[i]];
    }
    if (order[0] === idx) order.push(order.shift());  // 避免和当前重复
  }

  function preload(src, cb) {
    const im = new Image();
    im.onload = () => cb && cb(true);
    im.onerror = () => cb && cb(false);
    im.src = src;
  }

  function swapTo(src) {
    const next = front === A ? B : A;
    next.style.backgroundImage = `url("${src}")`;
    preload(src, () => {
      requestAnimationFrame(() => {
        next.classList.add('show');
        front.classList.remove('show');
        front = next;
      });
    });
  }

  function tick() {
    if (mode !== 'cycle') return;
    if (!order.length) shuffle();
    idx = order.shift();
    swapTo(url(idx));
    if (order.length === 0) shuffle();
  }

  function apply() {
    clearInterval(timer); timer = null;
    const src = currentSrc();
    if (mode === 'cycle') {
      shuffle();
      clearInterval(timer);
      timer = setInterval(tick, INTERVAL);
    } else if (src) {
      swapTo(src);
    }
  }

  function start() {
    A = document.getElementById('bgWallA');
    B = document.getElementById('bgWallB');
    if (!A || !B) return;
    front = A;
    A.style.backgroundImage = `url("${url(1)}")`;
    requestAnimationFrame(() => A.classList.add('show'));   // 首张直接淡入
    apply();
  }

  function setMode(m, i) {
    mode = m;
    if (i) idx = i;
    apply();
  }

  return { start, setMode };
})();

/* ══════════════════════════════════════════
   设置：定时停止 + 壁纸选择
   ══════════════════════════════════════════ */
const Settings = (function () {
  let secTimer = null;

  function renderChips() {
    $$('#settingsView [data-sleep]').forEach(c =>
      c.classList.toggle('on', c.dataset.sleep === S.sleepMode));
    $$('#settingsView [data-bg]').forEach(c =>
      c.classList.toggle('on', parseInt(c.dataset.bg, 10) === bgTimeoutMin()));
    const cyc = $('#wallCycle');
    if (cyc) cyc.classList.toggle('on', S.wallMode === 'cycle' && S.wallOn);
    const sysBtn = $('#wallSystem');
    if (sysBtn) sysBtn.classList.toggle('on', !S.wallOn || S.wallMode === 'off');
    const unit = $('#sleepUnit');
    if (unit) unit.textContent = S.sleepMode === 'minutes' ? '分钟' : '首';
  }

  function fmtRemain() {
    if (S.sleepMode === 'songs' && S.sleepRemain > 0) return `还剩 ${S.sleepRemain} 首`;
    if (S.sleepMode === 'minutes' && S.sleepRemain > 0) {
      const m = Math.floor(S.sleepRemain / 60), s = S.sleepRemain % 60;
      return `还剩 ${m}:${String(s).padStart(2, '0')}`;
    }
    return '';
  }

  function renderStatus() {
    const el = $('#sleepStatus');
    if (el) el.textContent = fmtRemain();
  }

  function fireStop() {
    try { Engine.audio.pause(); } catch (e) {}
    S.sleepMode = 'off'; S.sleepRemain = 0;
    renderChips(); renderStatus();
    toast('定时停止，好好休息 ♪');
    saveConfig();
  }

  /* Player 的 ended 回调先问这里；返回 false = 到量了，不再切下一首 */
  function allowNext() {
    if (S.sleepMode !== 'songs') return true;
    S.sleepRemain = Math.max(0, (S.sleepRemain || S.sleepVal) - 1);
    renderStatus();
    if (S.sleepRemain <= 0) { fireStop(); return false; }
    return true;
  }

  function arm() {
    S.sleepRemain = Math.max(1, S.sleepVal | 0);
    renderStatus();
  }

  function setMode(m) {
    S.sleepMode = m;
    if (m !== 'off') arm(); else S.sleepRemain = 0;
    renderChips(); renderStatus(); saveConfig();
  }

  function renderWalls() {
    const g = $('#wallGrid');
    if (!g) return;
    let h = '';
    for (let i = 1; i <= 7; i++) {
      const on = S.wallMode === 'single' && S.wallIdx === i ? ' on' : '';
      h += `<button class="wall-th${on}" data-w="${i}" style="background-image:url('/wall/wp${i}.jpg?v=3')" title="壁纸 ${i}"></button>`;
    }
    if (S.customWallUrl) {
      const on = S.wallMode === 'custom' ? ' on' : '';
      h += `<button class="wall-th${on}" data-w="custom" style="background-image:url('${S.customWallUrl}')" title="自定义壁纸"></button>`;
    }
    g.innerHTML = h;
    $$('#wallGrid .wall-th').forEach(b => {
      b.onclick = () => {
        const v = b.dataset.w;
        if (v === 'custom') { S.wallMode = 'custom'; Wallpapers.setMode('custom'); }
        else { S.wallMode = 'single'; S.wallIdx = +v; Wallpapers.setMode('single', +v); }
        S.wallOn = true;
        document.body.classList.add('wall-on');
        renderChips(); renderWalls(); saveConfig();
      };
    });
  }

  async function pickWall() {
    try {
      // 安卓壳：没有后端文件选择器，走系统相册 + 原生桥
      if (window.LuvsicNative && window.LuvsicNative.pickImage) {
        window.LuvsicNative.pickImage();
        toast('请选一张图片');
        return;
      }
      const a = api(); if (!a) return;
      const p = await a.pick_image();
      if (!p) return;
      const r = await a.set_custom_wallpaper(p);
      if (r && r.ok) {
        S.customWallUrl = (r.url || '/wall/custom.jpg');
        S.wallMode = 'custom';
        Wallpapers.setMode('custom');
        renderChips(); renderWalls(); saveConfig();
        toast('壁纸已更换');
      } else {
        toast((r && r.msg) || '换壁纸失败');
      }
    } catch (e) { toast('换壁纸失败'); }
  }

  function open() {
    document.body.classList.add('set-mode');
    S.setOpen = true;
    $('#settingsView').setAttribute('aria-hidden', 'false');
  }
  function close() {
    document.body.classList.remove('set-mode');
    S.setOpen = false;
    $('#settingsView').setAttribute('aria-hidden', 'true');
  }
  function toggle() { S.setOpen ? close() : open(); }

  /* 一键把微信（文件传输助手）里收到的 MP3 收进曲库 */
  async function wxImport(manual) {
    const a = api(); if (!a) return;
    const hint = $('#wxHint');
    let root = '';
    if (manual) {
      root = await a.pick_folder();
      if (!root) return;
    }
    if (hint) hint.textContent = '正在扫描微信文件…';
    toast('正在扫描微信歌曲…', 8000);

    let r = await a.scan_wechat_music(root);
    // 自动找不着微信目录 → 让他手动指一次
    if (r && r.needPick && !manual) {
      const p = await a.pick_folder();
      if (!p) {
        if (hint) hint.textContent = '没找到微信文件目录，点「指定微信目录…」手动选一次（一般选 D:\\xwechat_files）。';
        toast('没找到微信目录');
        return;
      }
      r = await a.scan_wechat_music(p);
      if (r && r.ok) { S.wxRoot = p; saveConfig(); }
    } else if (manual && r && r.ok) {
      S.wxRoot = root; saveConfig();
    }

    if (!r) { toast('扫描失败'); if (hint) hint.textContent = '扫描失败'; return; }
    if (r.__error && String(r.__error).indexOf('scan_wechat_music') >= 0) {
      toast('后端还是旧版本，重启播放器后再试', 3000);
      if (hint) hint.textContent = '后端还是旧版本：关掉播放器再双击「启动 Luvsic.bat」重开，这个按钮才生效。';
      return;
    }
    if (!r.ok) { const m = r.msg || '扫描失败'; toast(m); if (hint) hint.textContent = m; return; }

    // 曲库变了，重排并保住当前播放位置
    if (Array.isArray(r.tracks) && r.tracks.length) {
      const cur = S.tracks[S.idx] && S.tracks[S.idx].path;
      S.tracks = r.tracks;
      const k = cur ? S.tracks.findIndex(t => t.path === cur) : -1;
      if (k >= 0) S.idx = k;
      Queue.render($('#libSearch').value || '');
    }

    const parts = [r.msg || '完成'];
    if (r.added && r.names && r.names.length) parts.push('新增：' + r.names.join('、'));
    if (r.dup) parts.push(`跳过重复 ${r.dup} 首`);
    if (r.skipped) parts.push(`剔除非歌曲 ${r.skipped} 个`);
    if (hint) hint.textContent = parts.join('　·　');
    toast(r.msg || '完成', 3500);
  }

  function init() {
    secTimer = setInterval(() => {
      if (S.sleepMode !== 'minutes') return;
      const au = Engine.audio;
      if (au && !au.paused && S.sleepRemain > 0) {
        S.sleepRemain--;
        renderStatus();
        if (S.sleepRemain <= 0) fireStop();
      }
    }, 1000);

    $$('#settingsView [data-sleep]').forEach(c => {
      c.onclick = () => setMode(c.dataset.sleep);
    });
    $$('#settingsView [data-bg]').forEach(c => {
      c.onclick = () => {
        try { localStorage.setItem('luvsic_bg_timeout', c.dataset.bg); } catch (e) {}
        renderChips();
        pushMedia(true);
        toast('暂停 ' + (c.dataset.bg === '0' ? '不退出后台' : c.dataset.bg + ' 分钟后退出后台'));
      };
    });
    $('#sleepNum').addEventListener('change', e => {
      let v = parseInt(e.target.value, 10);
      if (!isFinite(v) || v < 1) v = 1;
      if (v > 999) v = 999;
      e.target.value = v;
      S.sleepVal = v;
      if (S.sleepMode !== 'off') arm();
      saveConfig();
    });
    $('#wallCycle').onclick = () => {
      S.wallMode = 'cycle';
      Wallpapers.setMode('cycle');
      renderChips(); renderWalls(); saveConfig();
    };
    $('#wallPick').onclick = pickWall;
    try { Sync.bind(); } catch (e) { console.warn('sync bind', e); }
  try {
    const sc = $('#syncConnect');
    if (sc) sc.onclick = () => { try { Gate.open(); } catch (e) {} };
  } catch (e) {}
    // 下载设置：选择保存文件夹
    var dlPick = $('#dlPick');
    if (dlPick) {
      dlPick.onclick = async function () {
        try {
          var r = await api().pick_download_folder();
          var p = (typeof r === 'string') ? r : ((r && r.path) || '');
          if (!p) return;
          S.downloadDir = p;
          updateDlLabel();
          saveConfig();
          toast('下载目录已设为：' + p);
        } catch (e) { toast('选择文件夹失败'); }
      };
    }
    var dlReset = $('#dlReset');
    if (dlReset) {
      dlReset.onclick = function () {
        S.downloadDir = '';
        updateDlLabel();
        saveConfig();
        toast('已恢复为跟随曲库目录');
      };
    }
    updateDlLabel();
    const wallSysBtn = $('#wallSystem');
    if (wallSysBtn) wallSysBtn.onclick = useSystemWall;
    $('#wxScan').onclick = () => wxImport(false);
    $('#wxPick').onclick = () => wxImport(true);
    $('#setBack').onclick = close;
    // 曲库维护：联网补全歌手 / 校验修复曲库（手机版入口在这里）
    const setFixMeta = $('#setFixMeta');
    if (setFixMeta) setFixMeta.onclick = () => runMetaFix(setFixMeta);
    const setRepair = $('#setRepairLib');
    if (setRepair) {
      setRepair.onclick = async () => {
        setRepair.disabled = true;
        try {
          const r = await api().library_repair();
          toast((r && r.msg) || (r && r.ok ? '曲库已校验' : '校验失败'));
          const lib = await api().cached_library();
          if (lib && lib.tracks) {
            S.tracks = lib.tracks;
            Queue.render($('#libSearch').value);
          }
        } finally { setRepair.disabled = false; }
      };
    }
    // 数据备份 / 恢复
    const btnBackup = $('#btnExportBackup');
    if (btnBackup) btnBackup.onclick = async () => {
      btnBackup.disabled = true;
      try {
        const r = await api().export_backup();
        if (r && r.ok) toast(r.msg || '备份完成', 3600);
        else toast((r && r.msg) || '已取消');
      } catch (e) { toast('备份失败'); }
      finally { btnBackup.disabled = false; }
    };
    const btnRestore = $('#btnImportBackup');
    if (btnRestore) btnRestore.onclick = async () => {
      if (!confirm('恢复备份会覆盖当前曲库/歌单/统计（会先自动备份当前数据），确定继续？')) return;
      btnRestore.disabled = true;
      try {
        const r = await api().import_backup('');
        if (r && r.ok) {
          toast(r.msg || '恢复完成，正在刷新…', 3600);
          setTimeout(() => location.reload(), 1400);
        } else {
          toast((r && r.msg) || '已取消');
        }
      } catch (e) { toast('恢复失败'); }
      finally { btnRestore.disabled = false; }
    };

    // 主题切换
    $$('#setThemeCard [data-theme]').forEach(b => {
      b.classList.toggle('on', b.dataset.theme === S.theme);
      b.onclick = () => setTheme(b.dataset.theme);
    });
    // 歌词颜色
    const lyrPick = $('#lyrColorPick');
    const lyrChips = $$('#lyrColorChips .set-chip');
    const syncLyrChips = () => {
      lyrChips.forEach(c => c.classList.toggle('on', (c.dataset.lyr || '') === (S.lyrColor || '')));
      if (lyrPick && S.lyrColor) lyrPick.value = S.lyrColor;
    };
    lyrChips.forEach(c => {
      c.onclick = () => {
        S.lyrColor = c.dataset.lyr || '';
        applyLyricColor(); syncLyrChips(); saveConfig();
      };
    });
    if (lyrPick) {
      lyrPick.oninput = () => {
        S.lyrColor = lyrPick.value;
        applyLyricColor(); syncLyrChips(); saveConfig();
      };
    }
    const lyrClear = $('#lyrColorClear');
    if (lyrClear) {
      lyrClear.onclick = () => {
        S.lyrColor = ''; applyLyricColor(); syncLyrChips(); saveConfig();
        toast('歌词颜色已恢复默认');
      };
    }
    syncLyrChips();
    // 歌词字号
    const lszEl = $('#lyrSize');
    if (lszEl) {
      const lszV = $('#lyrSizeV');
      const paintSz = () => { if (lszV) lszV.textContent = (S.lyrSize > 0 ? S.lyrSize + 'px' : '自动'); };
      lszEl.value = S.lyrSize || 0; syncRangeFill(lszEl); paintSz();
      lszEl.oninput = () => {
        S.lyrSize = +lszEl.value || 0;
        applyLyricSize(); syncRangeFill(lszEl); paintSz(); saveConfig();
      };
    }
    // 壁纸亮度 / 模糊
    const wbEl = $('#wallBright'), wblEl = $('#wallBlur');
    if (wbEl) {
      wbEl.value = S.wallBright;
      const lb = $('#wallBrightV'); if (lb) lb.textContent = S.wallBright + '%';
      wbEl.oninput = () => {
        S.wallBright = +wbEl.value;
        if (lb) lb.textContent = S.wallBright + '%';
        applyWallFilter(); saveConfig();
      };
    }
    if (wblEl) {
      wblEl.value = S.wallBlur;
      const lb2 = $('#wallBlurV'); if (lb2) lb2.textContent = S.wallBlur + 'px';
      wblEl.oninput = () => {
        S.wallBlur = +wblEl.value;
        if (lb2) lb2.textContent = S.wallBlur + 'px';
        applyWallFilter(); saveConfig();
      };
    }
    // 悬浮歌词（仅安卓壳提供）
    const ov = $('#lyrOverlayBtn');
    if (ov) {
      const syncOv = () => {
        try {
          if (!window.LuvsicNative || !window.LuvsicNative.isOverlayOn) return;
          const on = !!window.LuvsicNative.isOverlayOn();
          if (on !== S.lyricOverlay) {
            S.lyricOverlay = on;
            ov.classList.toggle('on', on);
            ov.textContent = on ? '关闭悬浮歌词' : '开启悬浮歌词';
          }
        } catch (e) {}
      };
      setInterval(syncOv, 1500);
      ov.onclick = () => {
        try {
          if (!window.LuvsicNative || !window.LuvsicNative.toggleOverlay) {
            toast('当前环境不支持悬浮歌词');
            return;
          }
          const r = window.LuvsicNative.toggleOverlay();
          if (r === 'need_permission') {
            toast('请在系统设置里允许「显示在其他应用上层」，再回来开启');
            return;
          }
          if (r === 'unsupported') {
            toast('当前环境不支持悬浮歌词');
            return;
          }
          S.lyricOverlay = (r === 'on');
          ov.classList.toggle('on', S.lyricOverlay);
          ov.textContent = S.lyricOverlay ? '关闭悬浮歌词' : '开启悬浮歌词';
          if (S.lyricOverlay) { pushOverlayLyric(); toast('悬浮歌词已开启，可拖动位置'); }
        } catch (e) {
          toast('悬浮歌词启动失败');
        }
      };
    }

    // 探测是否存在自定义壁纸
    fetch('/wall/custom.jpg', { method: 'HEAD' }).then(r => {
      if (r.ok) {
        S.customWallUrl = '/wall/custom.jpg';
        renderWalls();
        if (S.wallMode === 'custom') Wallpapers.setMode('custom');
      }
    }).catch(() => {});

    renderChips(); renderStatus(); renderWalls();
    if (S.sleepMode !== 'off') arm();
  }

  function applyFromConfig() {
    renderChips(); renderStatus(); renderWalls();
    if (S.sleepMode !== 'off') arm();
    Wallpapers.setMode(S.wallMode, S.wallIdx);
  }

  return { init, open, close, toggle, allowNext, applyFromConfig, renderWalls, renderChips };
})();

/* ───────────── 选歌入库：扫描本机 → 用户勾选 → 只有勾选的进曲库 ───────────── */
/* ───────────── 登录门禁 ───────────── */
const GUEST_KEY = 'luvsic_local_mode';

const Gate = (() => {
  let checked = false;

  function show() {
    document.body.classList.add('locked');
    const el = $('#loginGate');
    if (el) el.setAttribute('aria-hidden', 'false');
    setTimeout(() => { try { $('#gateRepo').focus(); } catch (e) {} }, 200);
  }

  function hide() {
    document.body.classList.remove('locked');
    const el = $('#loginGate');
    if (el) el.setAttribute('aria-hidden', 'true');
  }

  /** 启动时检查：默认就是本地模式，绝不主动弹门禁。
   *  只有在设置里主动点「连接云同步」时才会 show()。 */
  async function check() {
    checked = true;
    hide();
  }

  /** 用户在设置里主动点「连接云同步」时调用 */
  function open() {
    show();
  }

  /** 本地模式：不登录，只使用本机功能（播放 / 曲库 / 歌词 / EQ） */
  function skip() {
    try { localStorage.setItem(GUEST_KEY, '1'); } catch (e) {}
    hide();
    toast('已进入本地模式：只播放本机文件，云端同步与好友需要登录', 3200);
  }

  /** 退出本地模式（登录成功后 / 手动切回） */
  function clearGuest() {
    try { localStorage.removeItem(GUEST_KEY); } catch (e) {}
  }

  async function login() {
    const repoEl = $('#gateRepo');
    const tokEl = $('#gateToken');
    const err = $('#gateErr');
    const btn = $('#gateLogin');
    let repo = ((repoEl ? repoEl.value : '') || '').trim();
    repo = repo.replace(/^https?:\/\/github\.com\//i, '').replace(/\/+$/, '');
    const token = ((tokEl ? tokEl.value : '') || '').trim();
    if (!repo || repo.split('/').filter(Boolean).length !== 2) {
      if (err) err.textContent = '仓库格式应为 owner/repo，例如 yourname/luvsic-data';
      return;
    }
    if (!token) { if (err) err.textContent = '请填入 GitHub Token'; return; }
    if (btn) { btn.disabled = true; btn.textContent = '连接中…'; }
    if (err) err.textContent = '';
    try {
      const r = await api().sync_config(repo, token);
      if (!r || !r.ok) { if (err) err.textContent = (r && r.msg) || '连接失败'; return; }
      clearGuest();
      hide();
      toast('已连接 ' + repo);
      try { await Sync.reloadAfterLogin(); } catch (e) {}
    } finally {
      if (btn) { btn.disabled = false; btn.textContent = '连 接'; }
    }
  }

  function bind() {
    const b = $('#gateLogin');
    if (b) b.onclick = login;
    const sk = $('#gateSkip');
    if (sk) sk.onclick = skip;
    ['#gateRepo', '#gateToken'].forEach(sel => {
      const el = $(sel);
      if (el) el.addEventListener('keydown', e => { if (e.key === 'Enter') login(); });
    });
  }

  return { check, login, bind, show, hide, skip, clearGuest, open };
})();

/* ───────────── 账号与同步（邀请码）───────────── */
const Sync = (() => {
  let status = null;

  async function reloadData() {
    const lib = await api().cached_library();
    if (lib && lib.tracks) {
      S.tracks = lib.tracks;
      S.folder = lib.folder || S.folder;
    }
    try {
      const pl = await api().get_playlists();
      if (pl && pl.playlists) S.playlists = pl.playlists;
    } catch (e) {}
    if (typeof Queue !== 'undefined') Queue.render($('#libSearch') ? $('#libSearch').value : '');
    // 断点续播：恢复上次听的歌和位置（不自动播放）
    try {
      if (typeof Player !== 'undefined' && Player.resumeLast) Player.resumeLast();
    } catch (e) {}
  }

  async function refresh() {
    try { status = await api().sync_status(); } catch (e) { status = null; }
    const el = $('#syncState');
    const idEl = $('#acctId');
    if (idEl) {
      const uid = (status && status.userId) || '';
      idEl.textContent = /^\d{8}$/.test(uid) ? uid : (status && status.loggedIn ? '未绑定' : '未登录');
    }
    const adminCard = $('#pcAdminCard');
    const isAdmin = !!(status && status.isAdmin);
    document.body.classList.toggle('is-admin', isAdmin);
    if (adminCard) adminCard.style.display = isAdmin ? '' : 'none';
    // 手机壳首页里的「账号管理」入口也要跟着显示/隐藏
    try {
      if (window.parent && window.parent !== window && window.parent.__SHELL_ADMIN) {
        window.parent.__SHELL_ADMIN(isAdmin);
      }
    } catch (e) {}
    const fc = $('#myFriendCode');
    if (fc) fc.textContent = (status && /^\d{8}$/.test(status.userId || '')) ? status.userId : '—';
    if (status && status.loggedIn) {
      if (el) el.textContent = '已登录 · ' + (status.provider || 'GitHub');
      try { await loadProfile(); await loadStats(); await loadBoard(); } catch (e) {}
      if (status.isAdmin) { try { await loadLogins(); } catch (e) {} }
    } else {
      if (el) el.textContent = '未登录';
      const lb = $('#friendsBoard');
      if (lb) lb.textContent = '登录后显示';
    }
    const now = $('#syncNow'), out = $('#syncOut');
    if (now) now.disabled = !(status && status.loggedIn);
    if (out) out.disabled = !(status && status.loggedIn);
  }

  async function login() {
    const code = ($('#syncCode').value || '').trim();
    if (!code) { toast('先粘贴邀请码'); return; }
    const btn = $('#syncLogin');
    if (btn) { btn.disabled = true; btn.textContent = '登录中…'; }
    try {
      const r = await api().sync_login(code);
      toast((r && r.msg) || '登录失败');
      if (r && r.ok) {
        $('#syncCode').value = '';
        await reloadData();
        loadProfile(); loadStats(); loadBoard();
      }
    } finally {
      if (btn) { btn.disabled = false; btn.textContent = '登录'; }
      refresh();
    }
  }

  async function now() {
    const btn = $('#syncNow');
    if (btn) { btn.disabled = true; btn.textContent = '同步中…'; }
    try {
      const r = await api().sync_now();
      toast((r && r.msg) || '同步完成');
      if (r && (r.action === 'pull' || r.action === 'conflict')) {
        await reloadData();
      }
      loadProfile(); loadStats(); loadBoard();
    } finally {
      if (btn) { btn.disabled = false; btn.textContent = '立即同步'; }
      refresh();
    }
  }

  async function logout() {
    const r = await api().sync_logout();
    toast((r && r.msg) || '已退出');
    refresh();
    // 退出后立刻回到登录门禁（曲库/歌单等本地数据不动）
    try { Gate.show(); } catch (e) {}
  }

  /** 统一处理手机（同步桥）与桌面（Promise）两种 api 返回 */
  function apiCall(name, args) {
    try {
      const fn = api()[name];
      if (typeof fn !== 'function') return Promise.reject(new Error('no api ' + name));
      const r = fn.apply(api(), args || []);
      return (r && typeof r.then === 'function') ? r : Promise.resolve(r);
    } catch (e) {
      return Promise.reject(e);
    }
  }

  let lastAudioT = null;
  let accSec = 0;
  let lastPush = Date.now();
  let lastFlush = Date.now();

  function trackTime() {
    const a = $('#audio');
    if (!a || a.paused || a.ended || !isFinite(a.currentTime)) { lastAudioT = a ? a.currentTime : null; return; }
    if (lastAudioT == null) { lastAudioT = a.currentTime; return; }
    let d = a.currentTime - lastAudioT;
    lastAudioT = a.currentTime;
    if (d > 0 && d < 5) accSec += d;
    if (accSec >= 5 && Date.now() - lastPush >= 30000) {
      const v = accSec; accSec = 0; lastPush = Date.now();
      apiCall('stats_add', [v]).then(loadStats).catch(() => {});
      // 每 5 分钟把累计时长推到 Hub，好友排行榜就能及时看到
      if (Date.now() - lastFlush >= 300000) {
        lastFlush = Date.now();
        apiCall('hub_flush_stats', []).catch(() => {});
      }
    }
  }

  function fmtHour(sec) {
    sec = Math.max(0, Math.round(sec || 0));
    const h = Math.floor(sec / 3600), m = Math.round((sec % 3600) / 60);
    return h > 0 ? (h + ' 小时 ' + m + ' 分') : (m + ' 分钟');
  }

  async function loadProfile() {
    try {
      const p = await api().profile_get();
      const nm = $('#syncName');
      if (nm) nm.value = p.name || '';
      const av = $('#syncAvatar');
      if (av) {
        if (p.avatar) { av.style.backgroundImage = 'url(' + p.avatar + ')'; av.textContent = ''; }
        else { av.style.backgroundImage = ''; av.textContent = (p.name || '?').slice(0, 1); }
      }
    } catch (e) {}
  }

  async function loadStats() {
    try {
      const st = await api().stats_get();
      const el = $('#syncStats');
      if (el) el.textContent = '今日 ' + fmtHour(st.today) + ' · 总计 ' + fmtHour(st.total);
    } catch (e) {}
  }

  /* 好友榜：总榜 / 周榜 / 月榜切换（S.boardMode） */
  function renderBoardTabs() {
    const box = $('#friendsBoard');
    if (!box || !box.parentNode) return;
    let tabs = box.parentNode.querySelector('.board-tabs');
    if (!tabs) {
      tabs = document.createElement('div');
      tabs.className = 'board-tabs';
      box.parentNode.insertBefore(tabs, box);
      tabs.onclick = (ev) => {
        const b = ev.target.closest('[data-board]');
        if (!b) return;
        S.boardMode = b.dataset.board;
        loadBoard();
      };
    }
    const mode = S.boardMode || 'total';
    const defs = [['total', '总榜'], ['week', '周榜'], ['month', '月榜']];
    tabs.innerHTML = defs.map(([k, label]) =>
      '<button class="set-chip' + (mode === k ? ' on' : '') + '" data-board="' + k + '">' + label + '</button>'
    ).join('');
  }

  async function loadBoard() {
    const box = $('#friendsBoard');
    if (!box) return;
    try {
      const r = await api().hub_leaderboard();
      const list = (r && r.list) || [];
      if (!list.length) { box.textContent = r && r.ok ? '还没有排行数据' : ((r && r.msg) || '登录后显示'); return; }
      const mode = S.boardMode || 'total';
      const sorted = list.slice().sort((a, b) => (Number(b[mode]) || 0) - (Number(a[mode]) || 0));
      renderBoardTabs();
      box.innerHTML = sorted.map((it, i) => {
        const localNick = friendNickMap()[it.id] || '';
        const rawName = String(localNick || it.nick || it.name || '').trim();
        const nm = it.me ? ('我' + (rawName ? '（' + rawName + '）' : ''))
                         : (rawName || ('用户 ' + (it.code || '')));
        const showCode = !it.me && it.code && rawName && rawName !== String(it.code);
        const nowLine = (it.nowTitle && it.nowPlaying)
          ? '<div class="lb-now">正在听：' + esc(it.nowTitle) + '</div>' : '';
        return '<div class="sync-lb-row"><span class="rank">' + (i + 1) + '</span>' +
          '<span class="nm">' + esc(nm) +
          (showCode ? '<em class="lb-id">' + esc(it.code) + '</em>' : '') +
          nowLine + '</span>' +
          '<span class="hr">' + fmtHour(Number(it[mode]) || 0) + '</span></div>';
      }).join('');
    } catch (e) { box.textContent = '排行榜读取失败'; }
  }

  async function saveName() {
    const nm = $('#syncName');
    if (!nm) return;
    try { await api().profile_set(nm.value.trim(), '', ''); await loadProfile(); } catch (e) {}
  }

  /* 手机端没有原生选图接口：用 <input type=file> 走系统相册（WebView 已支持），
     读出来后在 canvas 里裁成 256x256 JPEG，再以 dataURL 存进资料。 */
  function pickImageDataUrl() {
    return new Promise(resolve => {
      const inp = document.createElement('input');
      inp.type = 'file';
      inp.accept = 'image/*';
      inp.style.display = 'none';
      document.body.appendChild(inp);
      let done = false;
      const finish = v => { if (done) return; done = true; try { inp.remove(); } catch (e) {} resolve(v); };
      inp.onchange = () => {
        const f = inp.files && inp.files[0];
        if (!f) { finish(''); return; }
        const objUrl = URL.createObjectURL(f);
        const img = new Image();
        img.onload = () => {
          try {
            const size = 256;
            const c = document.createElement('canvas');
            c.width = c.height = size;
            const g = c.getContext('2d');
            const s = Math.min(img.width, img.height);
            g.drawImage(img, (img.width - s) / 2, (img.height - s) / 2, s, s, 0, 0, size, size);
            URL.revokeObjectURL(objUrl);
            finish(c.toDataURL('image/jpeg', 0.82));
          } catch (e) {
            URL.revokeObjectURL(objUrl);
            finish('');
          }
        };
        img.onerror = () => { URL.revokeObjectURL(objUrl); finish(''); };
        img.src = objUrl;
      };
      inp.click();
    });
  }

  async function pickAvatar() {
    try {
      // 电脑版：原生文件对话框返回路径
      const p = await api().pick_image();
      if (p) {
        const r = await api().profile_set('', '', p);
        if (r && !r.ok) { toast(r.msg || '头像设置失败'); return; }
        await loadProfile();
        toast('头像已更新');
        return;
      }
      // 手机版：系统相册 → dataURL
      const url = await pickImageDataUrl();
      if (!url) return;
      const r2 = await api().profile_set('', url, '');
      if (r2 && !r2.ok) { toast(r2.msg || '头像设置失败'); return; }
      await loadProfile();
      toast('头像已更新');
    } catch (e) { toast('选图失败'); }
  }

  function bind() {
    const lg = $('#syncLogin');
    if (lg) lg.onclick = login;
    const nm = $('#syncNow');
    if (nm) nm.onclick = now;
    const ou = $('#syncOut');
    if (ou) ou.onclick = logout;
    const sn = $('#syncName');
    if (sn) { sn.onchange = saveName; sn.onblur = saveName; }
    const pa = $('#syncPickAvatar');
    if (pa) pa.onclick = pickAvatar;
    const ar = $('#adminRefresh');
    if (ar) ar.onclick = () => loadLogins();
    setInterval(trackTime, 1000);
    try { Gate.bind(); } catch (e) {}
    refresh();
    setTimeout(function () { try { refresh(); } catch (e) {} }, 1200);
  }

  function fmtTime(ts) {
    if (!ts) return '—';
    const d = new Date(ts * 1000);
    const p = n => (n < 10 ? '0' + n : '' + n);
    return (d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
  }

  async function loadLogins() {
    const box = $('#adminList');
    if (!box) return;
    try {
      const r = await api().hub_accounts();
      if (!r || !r.ok) { box.textContent = (r && r.msg) || '读取失败'; return; }
      const list = r.list || [];
      if (!list.length) { box.textContent = '还没有账号'; return; }
      box.textContent = '';
      list.forEach(it => {
        const on = it.enabled !== false;
        const row = document.createElement('div');
        row.className = 'admin-row';
        const platIcon = it.platform === 'android' ? '📱' : (it.platform ? '💻' : '');
        const dev = (platIcon + ' ' + (it.device || '')).trim() || '—';
        row.innerHTML =
          '<span class="aid">' + esc(it.id) + '</span>' +
          '<span class="anm">' + esc(it.name || '未命名') +
            (it.admin ? '<em class="adm-tag">管理员</em>' : '') +
            (it.pending ? '<em class="adm-tag pend">待批准</em>' : '') +
            (it.note ? '<span class="admin-note">' + esc(it.note) + '</span>' : '') + '</span>' +
          '<button class="note-btn">' + (it.note ? '改备注' : '＋备注') + '</button>' +
          '<span class="adev">' + esc(dev) + '</span>' +
          '<span class="atime">' + (it.lastLoginAt ? fmtTime(it.lastLoginAt) : '从未登录') + '</span>' +
          '<button class="acc-btn ' + (on ? 'allow' : 'deny') + '">' + (on ? '允许' : '禁止') + '</button>';
        const btn = row.querySelector('.acc-btn');
        btn.onclick = async () => {
          btn.disabled = true;
          const want = !on;
          const nr = await api().hub_set_account(it.id, want);
          toast((nr && nr.msg) || '操作失败');
          loadLogins();
        };
        row.querySelector('.note-btn').onclick = () => {
          askName('备注：' + it.id, it.note || '', async v => {
            const nr = await api().hub_set_note(it.id, v);
            toast((nr && nr.msg) || '备注已保存');
            loadLogins();
          });
        };
        box.appendChild(row);
      });
    } catch (e) { box.textContent = '读取失败'; }
  }

  async function reloadAfterLogin() {
    await reloadData();
    await loadProfile();
    await loadStats();
    await loadBoard();
    refresh();
  }

  async function loadPendingDevices() {
    const box = $('#pendingDevices');
    if (!box) return;
    try {
      const r = await api().admin_pending_devices();
      if (!r || !r.ok) { box.textContent = (r && r.msg) || '读取失败'; return; }
      const list = r.list || [];
      if (!list.length) { box.textContent = '暂无'; return; }
      box.textContent = '';
      list.forEach(it => {
        const row = document.createElement('div');
        row.className = 'friend-row';
        row.innerHTML =
          '<span class="nm">' + esc(it.accountName || ('账号 ' + (it.account || ''))) + '</span>' +
          '<em class="lb-id">' + esc(it.account || '') + '</em>' +
          '<span class="hr">' + esc(it.device || '') + '</span>' +
          '<button class="mini-btn friend-ok">允许</button>' +
          '<button class="mini-btn ghost friend-no">拒绝</button>';
        row.querySelector('.friend-ok').onclick = async () => {
          const ar = await api().admin_approve_device(it.account, it.deviceId);
          toast((ar && ar.msg) || '已允许');
          loadPendingDevices();
        };
        row.querySelector('.friend-no').onclick = async () => {
          const rr = await api().admin_deny_device(it.account, it.deviceId);
          toast((rr && rr.msg) || '已拒绝');
          loadPendingDevices();
        };
        box.appendChild(row);
      });
    } catch (e) { box.textContent = '读取失败'; }
  }

  return { bind, refresh, reloadData, loadProfile, loadStats, loadBoard, reloadAfterLogin, loadLogins, loadPendingDevices };
})();


/* 启动时判断一次管理员身份：控制电脑首页与手机壳首页的管理入口可见性 */
async function syncShellAdmin() {
  try {
    const st = await api().sync_status();
    const isAdmin = !!(st && st.isAdmin);
    document.body.classList.toggle('is-admin', isAdmin);
    const pcAdmin = document.getElementById('pcAdminCard');
    if (pcAdmin) pcAdmin.style.display = isAdmin ? '' : 'none';
    const fc = document.getElementById('myFriendCode');
    if (fc) fc.textContent = (st && /^\d{8}$/.test(st.userId || '')) ? st.userId : '—';
    try {
      if (window.parent && window.parent !== window && window.parent.__SHELL_ADMIN) {
        window.parent.__SHELL_ADMIN(isAdmin);
      }
    } catch (e) {}
  } catch (e) {}
}

/* ───────────── 独立页面：账号管理（仅管理员）/ 好友与排行榜 ───────────── */
const AdminPage = (() => {
  async function open() {
    if (!document.body.classList.contains('is-admin')) {
      try {
        const st = await api().sync_status();
        document.body.classList.toggle('is-admin', !!(st && st.isAdmin));
      } catch (e) {}
    }
    if (!document.body.classList.contains('is-admin')) { toast('只有管理员可以进入'); return false; }
    document.body.classList.add('set-mode', 'admin-mode');
    document.body.classList.remove('friends-mode');
    const sv = $('#settingsView'); if (sv) sv.setAttribute('aria-hidden', 'true');
    const v = $('#adminView'); if (v) v.setAttribute('aria-hidden', 'false');
    try { Sync.loadLogins(); } catch (e) {}
    try { Sync.loadPendingDevices(); } catch (e) {}
    return true;
  }
  function close() {
    document.body.classList.remove('admin-mode');
    const v = $('#adminView'); if (v) v.setAttribute('aria-hidden', 'true');
    if (document.body.classList.contains('android') && window.parent !== window) {
      try { window.parent.location.hash = 'home'; return; } catch (e) {}
    }
    document.body.classList.remove('set-mode');
    try { showPcHome(true); } catch (e) {}
  }
  function bind() {
    const b = $('#adminBack'); if (b) b.onclick = close;
    const r = $('#adminRefresh'); if (r) r.onclick = () => { Sync.loadLogins(); Sync.loadPendingDevices(); };
  }
  return { open, close, bind };
})();

const FriendsPage = (() => {
  async function loadFriends(retry) {
    const box = $('#friendsList');
    try { loadShareInbox(); } catch (e) {}
    if (!box) return;
    try {
      const r = await api().friends_list();
      if (!r || !r.ok) { box.textContent = (r && r.msg) || '读取好友失败'; return; }
      const fc = $('#myFriendCode');
      if (fc && r.myCode) fc.textContent = r.myCode;
      const list = r.list || [];
      if (!list.length) { box.textContent = '还没有好友，输入对方的好友码添加'; return; }
      box.textContent = '';
      list.forEach(it => {
        const row = document.createElement('div');
        row.className = 'friend-row';
        // 备注名优先显示（本地缓存的备注立即生效），原昵称用小字跟在后面
        const localNick = friendNickMap()[it.id] || '';
        const disp = localNick || it.nick || it.name || ('用户 ' + (it.code || ''));
        const shownCode = it.code || '';
        const orig = (it.nick && it.name && it.nick !== it.name) ? it.name : '';
        row.innerHTML =
          '<span class="nm">' + esc(disp) +
            (orig ? '<em class="friend-orig">' + esc(orig) + '</em>' : '') + '</span>' +
          '<em class="lb-id">' + esc(shownCode) + '</em>' +
          '<span class="hr">' + (it.lastLoginAt ? fmtTime(it.lastLoginAt) : '从未登录') + '</span>' +
          '<button class="mini-btn ghost friend-note">备注</button>' +
          '<button class="mini-btn ghost friend-del">删除</button>';
        row.querySelector('.friend-note').onclick = () => {
          const dispName = it.nick || it.name || ('用户 ' + (it.code || ''));
          askName('给 ' + dispName + ' 备注名字', it.nick || '', async v => {
            const nr = await api().friends_note(it.id, v);
            if (nr && nr.ok) setFriendNickCache(it.id, v);
            toast((nr && nr.msg) || '已保存');
            loadFriends();
          });
        };
        row.querySelector('.friend-del').onclick = async () => {
          const dr = await api().friends_remove(it.id);
          if (dr && dr.ok) setFriendNickCache(it.id, '');
          toast((dr && dr.msg) || '已删除');
          loadFriends(); loadRequests();
        };
        box.appendChild(row);
      });
    } catch (e) {
      box.textContent = '读取好友失败';
      if (!retry) setTimeout(() => { try { loadFriends(true); } catch (err) {} }, 1500);
    }
  }

  /* 收到的分享歌单：导入后本地有的歌用本地文件，没有的标未下载 */
  async function loadShareInbox() {
    const box = $('#shareInbox');
    if (!box) return;
    try {
      const r = await api().share_inbox_get();
      const list = (r && r.list) || [];
      if (!r || !r.ok) { box.textContent = (r && r.msg) || '读取失败'; return; }
      if (!list.length) { box.textContent = '还没有人分享歌单给你'; return; }
      box.textContent = '';
      list.forEach(it => {
        const row = document.createElement('div');
        row.className = 'friend-row';
        row.innerHTML =
          '<span class="nm">' + esc(it.fromName || ('用户 ' + (it.from || ''))) + ' 的分享 · ' +
            esc(it.name || '歌单') + '<em class="friend-orig">' + (it.count || 0) + ' 首</em></span>' +
          '<span class="hr">' + (it.at ? fmtTime(it.at) : '') + '</span>' +
          '<button class="mini-btn import-share">导入</button>';
        row.querySelector('.import-share').onclick = async () => {
          const btn = row.querySelector('.import-share');
          btn.disabled = true;
          btn.textContent = '导入中…';
          try {
            const rr = await api().import_shared_playlist(it.id);
            toast((rr && rr.msg) || '已导入', 3800);
            if (rr && rr.ok) {
              loadShareInbox();
              try { await Sync.reloadData(); } catch (e) {}
              try { Queue.render($('#libSearch').value); } catch (e) {}
            }
          } finally {
            btn.disabled = false;
            btn.textContent = '导入';
          }
        };
        box.appendChild(row);
      });
    } catch (e) { box.textContent = '读取失败'; }
  }

  async function loadRequests(retry) {
    const box = $('#friendRequests');
    const sentHint = $('#friendSentHint');
    if (!box) return;
    try {
      const r = await api().friends_requests();
      const list = (r && r.list) || [];
      if (!r || !r.ok) {
        box.textContent = (r && r.msg) || '读取好友申请失败';
      } else if (!list.length) {
        box.textContent = '还没有新的好友申请';
        box.classList.remove('req-list');
      } else {
        box.textContent = '';
        box.classList.add('req-list');
        list.forEach(it => {
          const row = document.createElement('div');
          row.className = 'friend-row';
          row.innerHTML =
            '<span class="nm">' + esc(it.name || ('用户 ' + (it.code || ''))) + '</span>' +
            '<em class="lb-id">' + esc(it.code || '') + '</em>' +
            '<span class="hr">' + (it.at ? fmtTime(it.at) : '') + '</span>' +
            '<button class="mini-btn friend-ok">同意</button>' +
            '<button class="mini-btn ghost friend-no">拒绝</button>';
          row.querySelector('.friend-ok').onclick = async () => {
            const ar = await api().friends_accept(it.from);
            toast((ar && ar.msg) || '已同意');
            loadRequests(); loadFriends();
          };
          row.querySelector('.friend-no').onclick = async () => {
            const rr = await api().friends_reject(it.from);
            toast((rr && rr.msg) || '已拒绝');
            loadRequests();
          };
          box.appendChild(row);
        });
      }
      // 我发出的、还没被同意的申请
      let sentTxt = '';
      try {
        const sr = await api().friends_sent();
        const sents = (sr && sr.list) || [];
        if (sents.length) {
          sentTxt = '已发送、等待对方同意：' + sents.map(x => (x.name || ('用户 ' + (x.code || ''))) + '（' + (x.code || '') + '）').join('、');
        }
      } catch (e) {}
      if (sentHint) sentHint.textContent = sentTxt;
    } catch (e) {
      box.textContent = '读取好友申请失败';
      if (!retry) setTimeout(() => { try { loadRequests(true); } catch (err) {} }, 1500);
    }
  }

  function open() {
    document.body.classList.add('set-mode', 'friends-mode');
    document.body.classList.remove('admin-mode');
    const sv = $('#settingsView'); if (sv) sv.setAttribute('aria-hidden', 'true');
    const v = $('#friendsView'); if (v) v.setAttribute('aria-hidden', 'false');
    try { Sync.loadBoard(); } catch (e) {}
    try { loadFriends(); } catch (e) {}
    try { loadRequests(); } catch (e) {}
    return true;
  }
  function close() {
    document.body.classList.remove('friends-mode');
    const v = $('#friendsView'); if (v) v.setAttribute('aria-hidden', 'true');
    if (document.body.classList.contains('android') && window.parent !== window) {
      try { window.parent.location.hash = 'home'; return; } catch (e) {}
    }
    document.body.classList.remove('set-mode');
    try { showPcHome(true); } catch (e) {}
  }
  function bind() {
    const b = $('#friendsBack'); if (b) b.onclick = close;
    const rf = $('#friendsRefresh');
    if (rf) rf.onclick = () => {
      rf.disabled = true;
      rf.textContent = '刷新中…';
      try { Sync.loadBoard(); } catch (e) {}
      try { loadFriends(); } catch (e) {}
      try { loadRequests(); } catch (e) {}
      setTimeout(() => {
        rf.disabled = false;
        rf.textContent = '刷新';
        toast('已刷新好友与排行榜');
      }, 800);
    };
    const addBtn = $('#friendAddBtn');
    if (addBtn) {
      addBtn.onclick = async () => {
        const inp = $('#friendCodeInput');
        const code = ((inp && inp.value) || '').replace(/\D/g, '').slice(0, 8);
        if (code.length !== 8) { toast('请输入 8 位好友码'); return; }
        addBtn.disabled = true;
        try {
          const r = await api().friends_add(code);
          toast((r && r.msg) || '添加失败');
          if (r && r.ok) { if (inp) inp.value = ''; loadFriends(); loadRequests(); }
        } finally { addBtn.disabled = false; }
      };
    }
    const cp = $('#copyFriendCode');
    if (cp) cp.onclick = () => {
      const c = $('#myFriendCode');
      const txt = c ? c.textContent : '';
      if (!txt || txt === '—') { toast('还没登录，登录后才有好友码'); return; }
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(txt).then(
            () => toast('好友码已复制：' + txt),
            () => toast('好友码：' + txt + '（复制失败，手动记一下）'));
          return;
        }
      } catch (e) {}
      toast('好友码：' + txt);
    };
  }
  return { open, close, bind, loadFriends };
})();

/* ───────────── 版本检查与更新 ───────────── */
const Updater = (() => {
  let latest = null;

  async function loadVersion() {
    try {
      const r = await api().app_version();
      const el = $('#appVersion');
      if (el && r && r.version) el.textContent = 'v' + r.version;
    } catch (e) {}
  }

  async function check() {
    const hint = $('#updateHint');
    const btn = $('#doUpdateBtn');
    if (hint) hint.textContent = '正在检查…';
    try {
      const r = await api().check_update();
      if (!r || !r.ok) {
        if (hint) hint.textContent = (r && r.msg) || '检查更新失败';
        return;
      }
      if (!r.hasUpdate) {
        latest = null;
        if (hint) hint.textContent = '已是最新版本（v' + (r.current || '') + '）';
        if (btn) btn.style.display = 'none';
        return;
      }
      latest = r;
      if (hint) hint.textContent = '发现新版本 v' + r.latest + (r.notes ? '：' + r.notes : '');
      if (btn) {
        btn.style.display = '';
        btn.textContent = '下载并安装 v' + r.latest;
      }
    } catch (e) {
      if (hint) hint.textContent = '检查更新失败';
    }
  }

  async function doUpdate() {
    const hint = $('#updateHint');
    const btn = $('#doUpdateBtn');
    if (!latest) return;
    if (btn) btn.disabled = true;
    const isAndroid = document.body.classList.contains('android');
    try {
      if (hint) hint.textContent = isAndroid ? '正在下载…（完成后点通知栏安装）' : '正在下载安装包…';
      // 手机端要传完整的下载地址（check_update 返回的 url），桌面端传文件相对路径
      const fileName = String(latest.file || '').split('/').pop();
      const r = isAndroid
        ? await api().download_update(latest.url || '', fileName)
        : await api().download_update(latest.file || '');
      if (!r || !r.ok) {
        const msg = (r && r.msg) || '下载失败';
        if (hint) hint.textContent = msg;
        // 弹窗提醒（权限问题引导去设置）
        try {
          if (r && r.needPermission) {
            if (window.confirm(msg + '。点「确定」去系统设置开启，开启后回来再点一次「下载并安装」。')) {
              try { await api().open_install_settings(); } catch (e) {}
            }
          } else if (window.confirm) {
            window.confirm(msg);
          }
        } catch (e) {}
        return;
      }
      if (isAndroid) {
        if (hint) hint.textContent = (r.msg || '已开始下载')
          + '。下载完成会自动弹出安装界面；如果没弹，下拉通知栏点「Luvsic 新版本已下载」即可安装。'
          + '（若提示没有安装权限，请到 设置 → 应用 → Luvsic → 安装未知应用 里允许）';
        return;
      }
      if (hint) hint.textContent = '下载完成，正在启动安装程序…';
      const rr = await api().run_update(r.path || '');
      if (hint) hint.textContent = (rr && rr.msg) || '安装程序已启动';
    } finally {
      if (btn) btn.disabled = false;
    }
  }

  function bind() {
    const c = $('#checkUpdateBtn');
    if (c) c.onclick = () => check();
    const b = $('#doUpdateBtn');
    if (b) b.onclick = () => doUpdate();
    loadVersion();
    // 更新检查统一由手机外壳处理（首页不加载本页，放在这里会漏检查）
  }

  async function autoCheck() {
    try {
      const r = await api().check_update();
      // 没有新版本：什么都不做（不打扰用户）
      if (!(r && r.ok && r.hasUpdate)) return;
      // 有新版本：先弹确认窗，用户点「立即安装」后才下载并弹出安装界面
      showConfirm('发现新版本 v' + r.latest,
        (r.notes ? r.notes + '。' : '') + '是否现在安装？',
        '立即安装',
        async () => {
          toast('正在下载新版本…');
          const fileName = String(r.file || '').split('/').pop();
          const d = await api().download_update(r.url || '', fileName);
          if (d && d.ok) toast('下载中，完成后会自动弹出安装界面');
          else toast('下载失败：' + ((d && d.msg) || '请到设置页手动更新'));
        });
    } catch (e) {}
  }

  return { bind, loadVersion, check };
})();

/* ───────────── 缺失歌曲下载（歌单跨设备：本地没有的歌） ───────────── */

/* ───────────── 歌曲信息编辑 + 联网补全歌手（电脑版）───────────── */
const MetaEdit = (() => {
  let path = '';

  function open(p) {
    const t = S.tracks.find(x => x.path === p);
    if (!t) return;
    path = p;
    $('#metaTitle').value = t.title || '';
    $('#metaArtist').value = (t.artist && t.artist !== '未知艺术家') ? t.artist : '';
    $('#metaAlbum').value = t.album || '';
    $('#metaPath').textContent = t.path;
    $('#metaPath').title = t.path;
    openSheet('metaSheet');
    setTimeout(() => { try { $('#metaTitle').focus(); } catch (e) {} }, 120);
  }

  async function save() {
    if (!path) return;
    const btn = $('#metaSave');
    if (btn) { btn.disabled = true; btn.textContent = '保存中…'; }
    try {
      const r = await api().update_track_meta(path,
        $('#metaTitle').value.trim(), $('#metaArtist').value.trim(), $('#metaAlbum').value.trim());
      if (!r || !r.ok) { toast((r && r.msg) || '保存失败'); return; }
      const t = S.tracks.find(x => x.path === path);
      if (t && r.track) {
        t.title = r.track.title;
        t.artist = r.track.artist;
        t.album = r.track.album;
      }
      Queue.render($('#libSearch').value);
      closeSheets();
      toast(r.msg || '已保存');
    } finally {
      if (btn) { btn.disabled = false; btn.textContent = '保存'; }
    }
  }

  return { open, save };
})();

/* 曲库工具：联网补全未知歌手（电脑工具栏按钮 / 手机设置页按钮共用） */

async function runMetaFix(btn) {
  const r = await api().meta_fix_start();
  if (!r || !r.ok) { toast((r && r.msg) || '启动失败'); return; }
  toast('正在联网补全歌手，可继续用其他功能…', 3000);
  const baseLabel = (btn && btn.dataset && btn.dataset.label) || '补全歌手';
  const tick = async () => {
    const st = (await api().meta_fix_status()).state || {};
    if (st.running) {
      if (btn) btn.textContent = '补全中 ' + (st.done || 0) + '/' + (st.total || 0);
      setTimeout(tick, 1000);
      return;
    }
    if (btn) btn.textContent = baseLabel;
    const lib = await api().cached_library();
    if (lib && lib.tracks) {
      S.tracks = lib.tracks;
      Queue.render($('#libSearch').value);
    }
    toast(st.msg || '补全完成');
  };
  setTimeout(tick, 1200);
}

const Picker = (() => {
  let items = [];
  let sel = new Set();

  function updateInfo() {
    const el = $('#pickInfo');
    if (el) el.textContent = '已选 ' + sel.size + ' 首 / 本机共 ' + items.length + ' 首';
  }

  function render() {
    const list = $('#pickList');
    if (!list) return;
    const q = (($('#pickSearch') && $('#pickSearch').value) || '').trim().toLowerCase();
    const rows = items.filter(t => !q ||
      ((t.title || '') + ' ' + (t.artist || '') + ' ' + (t.name || '')).toLowerCase().indexOf(q) >= 0);
    if (!rows.length) {
      list.innerHTML = '<div class="on-empty">没有匹配的本机歌曲</div>';
      updateInfo();
      return;
    }
    list.innerHTML = '';
    rows.forEach(t => {
      const row = document.createElement('label');
      row.className = 'pick-row' + (sel.has(t.path) ? ' on' : '');
      const cb = document.createElement('input');
      cb.type = 'checkbox';
      cb.checked = sel.has(t.path);
      const txt = document.createElement('div');
      txt.className = 'pick-txt';
      const sub = [t.artist, t.album, t.duration ? fmt(t.duration) : ''].filter(Boolean).join(' · ');
      txt.innerHTML = '<div class="pick-nm">' + esc(t.title || t.name || '') + '</div>' +
        (sub ? '<div class="pick-sub">' + esc(sub) + '</div>' : '');
      cb.onchange = () => {
        if (cb.checked) sel.add(t.path); else sel.delete(t.path);
        row.classList.toggle('on', cb.checked);
        updateInfo();
      };
      row.appendChild(cb);
      row.appendChild(txt);
      list.appendChild(row);
    });
    updateInfo();
  }

  async function open(folder) {
    toast('正在扫描本机歌曲…', 2500);
    const r = await api().library_candidates(folder || '');
    if (!r || !r.ok) {
      toast((r && r.msg) || '扫描失败（检查「音乐和音频」权限）');
      return;
    }
    items = r.tracks || [];
    sel = new Set(items.filter(t => t.inLib).map(t => t.path));
    const sr = $('#pickSearch');
    if (sr) sr.value = '';
    render();
    openSheet('pickSheet');
  }

  function setAll(v) {
    items.forEach(t => { if (v) sel.add(t.path); else sel.delete(t.path); });
    render();
  }

  async function save() {
    const btn = $('#pickSave');
    if (btn) { btn.disabled = true; btn.textContent = '保存中…'; }
    const r = await api().library_set_picks([...sel]);
    if (btn) { btn.disabled = false; btn.textContent = '保存到曲库'; }
    if (!r || !r.ok) { toast((r && r.msg) || '保存失败'); return; }
    const curPath = S.tracks[S.idx] && S.tracks[S.idx].path;
    S.tracks = r.tracks || [];
    S.folder = r.folder || S.folder;
    const at = curPath ? S.tracks.findIndex(t => t.path === curPath) : -1;
    if (at >= 0) {
      S.idx = at;
    } else {
      S.idx = S.tracks.length ? 0 : -1;
      if (S.playing) { try { Player.pause(); } catch (e) {} }
    }
    Queue.render();
    closeSheets();
    toast('曲库已更新：' + S.tracks.length + ' 首');
  }

  return { open, save, render, setAll };
})();


async function boot() {
  // 安卓外壳（android.html）自己播进入动画，内嵌页面跳过，避免每次切页都重播
  if (location.search.indexOf('android=1') >= 0) {
    const bootEl = document.getElementById('boot');
    if (bootEl) bootEl.style.display = 'none';
    const appEl = document.getElementById('app');
    if (appEl) appEl.classList.add('in');
  }
  fetch('/ready').catch(() => {});
  // 安卓端不内置壁纸：用户选过本机图片就用它；点过「系统壁纸」则保持原生背景
  if (location.search.indexOf('android=1') >= 0) {
    if (S.wallMode === 'off') {
      // 系统壁纸：清掉残留壁纸层
      ['bgWallA', 'bgWallB'].forEach(function (id) {
        var el = document.getElementById(id);
        if (el) { el.classList.remove('show'); el.style.backgroundImage = ''; }
      });
      try {
        window.parent.postMessage({ type: 'luvsic-wall-off' }, '*');
      } catch (e) {}
    } else {
      fetch('/wall/custom.jpg', { method: 'HEAD' }).then(r => {
      if (!r || !r.ok) {
        // 没有自定义壁纸：内置壁纸在手机端已移除，直接关掉壁纸层
        S.wallOn = false;
        document.body.classList.remove('wall-on');
        return;
      }
      S.customWallUrl = '/wall/custom.jpg?t=' + Date.now();
      S.wallMode = 'custom';
      S.wallOn = true;
      document.body.classList.add('wall-on');
      // 同时铺到 #bg 上（手机端内嵌页用它当整页背景，最稳）
      var bgEl = document.getElementById('bg');
      if (bgEl) {
        bgEl.style.backgroundImage = 'url("' + S.customWallUrl + '")';
        bgEl.style.backgroundSize = 'cover';
        bgEl.style.backgroundPosition = 'center';
      }
      var appBg2 = document.getElementById('app');
      if (appBg2) {
        appBg2.style.backgroundImage = 'url("' + S.customWallUrl + '")';
        appBg2.style.backgroundSize = 'cover';
        appBg2.style.backgroundPosition = 'center';
        appBg2.style.backgroundRepeat = 'no-repeat';
      }
      try { Wallpapers.setMode('custom'); } catch (e) {}
      try { Settings.renderWalls(); Settings.renderChips(); } catch (e) {}
      try { window.parent.postMessage({ type: 'luvsic-wall' }, '*'); } catch (e) {}
      }).catch(() => {});
    }
  }
  Boot.bind();
  Boot.start();
  bindUI();
  try { bindRangeFills(document); } catch (e) {}
  Wallpapers.start();
  Settings.init();
  try { Updater.bind(); } catch (e) {}
  Lyrics.loop();

  const a = api();
  const stage = (n, x) => { if (SELFTEST) reportSelfTest(Object.assign({ stage: n }, x || {})); };
  stage('boot-start', { hasApi: !!a });
  Queue.render();
  Lyrics.clear();

  /* 演示模式：浏览器里预览整套界面 */
  if (DEMO) {
    Engine.setVolume(S.volume);
    Engine.setSpeed(S.speed);
    Engine.setPitch(S.pitch);
    Engine.setWidth(S.width);
    applyPreset('flat');
    Engine.resume();
    S.tracks = DEMO_TRACKS.map(t => Object.assign({}, t, { demo: true }));
    S.folder = '演示曲库';
    Queue.render();
    Player.setTrack(0, false);
    Lyrics.render({ synced: DEMO_LRC, source: 'LRCLIB' });
    let dt = 64.5;
    setInterval(() => {
      dt += 0.25;
      if (dt > 250) dt = 0;
      const p = dt / 254 * 100;
      $('#seekFill').style.width = p + '%';
      $('#seekKnob').style.left = p + '%';
      $('#seekBuf').style.width = Math.min(100, p + 22) + '%';
      $('#tCur').textContent = fmt(dt);
      Lyrics.sync(dt);
    }, 250);
    $('#btnPlay').classList.add('playing');
    Art.setPlaying(true);
    return;
  }

  if (a) {
    try { await Playlists.load(); } catch (e) {}
    try {
      const cfg = await a.get_config();
      restoreConfig(cfg);
      stage('config-ok', { vol: S.volume });
    } catch (e) { stage('config-fail', { msg: String(e) }); }

    try {
      const lib = await a.cached_library();
      if (lib && lib.tracks && lib.tracks.length) {
        S.folder = lib.folder || '';
        S.tracks = lib.tracks;
        Queue.render();
        let start = 0;
        if (S.lastPath) {
          const k = S.tracks.findIndex(t => t.path === S.lastPath);
          if (k >= 0) start = k;
        }
        Player.setTrack(start, false);
      }
    } catch (e) { stage('lib-fail', { msg: String(e) }); }
  }
  stage('lib-loaded', { tracks: S.tracks.length });
  try { setTimeout(function () { Gate.check(); }, 300); } catch (e) {}
  try { setTimeout(function () { syncShellAdmin(); }, 700); } catch (e) {}
  try { AdminPage.bind(); } catch (e) {}
  try { FriendsPage.bind(); } catch (e) {}
  // 电脑版：进入后先落在「首页」，由左侧栏切换各界面（手机版有外壳自己的首页）
  try {
    if (!document.body.classList.contains('android') && !QS.get('q') && !QS.get('batch')) {
      // Edge 的 app 窗口可能恢复上次的 #hash（例如 #settings），桌面版进入时统一回到首页
      try { history.replaceState(null, '', location.pathname); } catch (e) {}
      setTimeout(function () { showPcHome(true); }, 700);
    }
  } catch (e) {}
  // 向安卓壳要一下安装版本号（网页版没有壳，忽略即可）
  try {
    if (window.parent && window.parent !== window) {
      window.parent.postMessage({ type: 'luvsic-req-version' }, '*');
      setTimeout(function () {
        try { window.parent.postMessage({ type: 'luvsic-req-version' }, '*'); } catch (e) {}
      }, 1600);
    }
  } catch (e) {}
  // 手机端曲库为空时，直接弹出「选歌入库」，不再自动把系统歌曲全加进来
  if (!S.tracks.length && document.body.classList.contains('android')) {
    setTimeout(() => { try { Picker.open(); } catch (e) {} }, 700);
  }

  /* 深链：index.html?q=歌名 → 定位到曲库搜索框 */
  const deepQ = QS.get('q');
  if (deepQ) {
    setTimeout(() => {
      try {
        try { showPcHome(false); } catch (e) {}
        const inp = $('#onSearch');
        if (inp) inp.value = deepQ;
      } catch (e) {}
    }, 260);
  }

  if (!S.tracks.length) {
    $('#mTitle').textContent = '暂无曲目';
    $('#mArtist').textContent = '点左下「曲库」→ 选文件夹';
  }

  /* 自检模式：真实播放 + 歌词 + 变调，回报结果 */
  if (SELFTEST) {
    setTimeout(async () => {
      stage('play-attempt');
      try { await Player.play(); } catch (e) { stage('play-throw', { msg: String(e) }); }
      setTimeout(() => {
        const el = Engine.audio;
        reportSelfTest({
          tracks: S.tracks.length,
          title: $('#mTitle').textContent,
          paused: el.paused,
          currentTime: +(el.currentTime || 0).toFixed(2),
          duration: +(el.duration || 0).toFixed(1),
          playbackRate: el.playbackRate,
          lyricLines: document.querySelectorAll('.lyr-line').length,
          lyrSrc: $('#lyrSrc').textContent,
          bootGone: $('#boot').classList.contains('gone'),
          pitchRatioOk: Engine.shifterOK !== false,
          seekWidth: $('#seekFill').style.width,
        });
      }, 5000);
    }, 4200);
  }
}

// pywebview 就绪后才可调用后端
let __started = false;
function startOnce() {
  if (__started) return;
  __started = true;
  boot();
}
startOnce();

/* ── 手机版：底部播放条（进度条 + 控制键）+ 歌曲/歌词切换 ── */
function buildMobileBar() {
  if (document.getElementById('mobBar')) return;
  const main = document.getElementById('main');
  const seek = document.querySelector('.seek-wrap');
  const tr = document.querySelector('.transport');
  if (!main || !seek || !tr) return;
  const bar = document.createElement('div');
  bar.id = 'mobBar';

  const top = document.createElement('div');
  top.id = 'mobTop';
  const mode = document.getElementById('btnMode');
  if (mode) top.appendChild(mode); else top.appendChild(document.createElement('span'));
  const tabs = document.createElement('div');
  tabs.id = 'mobTabs';
  tabs.innerHTML = '<button data-v="song" class="on">歌 曲</button><button data-v="lyr">歌 词</button>';
  top.appendChild(tabs);
  const spacer = document.createElement('span');
  spacer.className = 'mob-sp';
  // 右侧「音效」按钮：打开桌面版同一套调校面板（EQ / 声场宽度 / 倍速 / 升降调）
  const tune = document.createElement('button');
  tune.id = 'mobTune';
  tune.type = 'button';
  tune.textContent = '音效';
  tune.addEventListener('click', function () {
    try { openSheet('tuneSheet'); } catch (e) { console.warn('open tuneSheet', e); }
  });
  top.appendChild(tune);

  bar.appendChild(top);
  bar.appendChild(seek);
  bar.appendChild(tr);
  main.appendChild(bar);

  /* 播放页左右滑动切换「歌曲 / 歌词」：向左滑看歌词，向右滑回歌曲 */
  function setLyrView(lyr) {
    document.body.classList.toggle('lyr-open', lyr);
    tabs.querySelectorAll('button').forEach(function (x) {
      x.classList.toggle('on', (x.getAttribute('data-v') === 'lyr') === lyr);
    });
  }
  (function bindPlaySwipe() {
    const stageEl = document.querySelector('.stage');
    const lyrEl = document.querySelector('.lyrics-panel');
    const mainEl = document.getElementById('main') || main;
    const EASE = 'transform .32s cubic-bezier(.22,.61,.36,1)';
    let sw = { on: false, x: 0, y: 0, dx: 0 };
    let moved = false;

    function width() {
      return (mainEl && mainEl.clientWidth) || window.innerWidth || 1;
    }
    function apply(p) {          // 布局用 display 切换（稳定），这里只保留空实现
    }
    function finish(lyr) {
      setLyrView(lyr);          // CSS 内含滑入动画
    }
    function start(e) {
      if (!e.touches || e.touches.length !== 1) return;
      sw.on = true; sw.x = e.touches[0].clientX; sw.y = e.touches[0].clientY; sw.dx = 0;
      moved = false;
      if (stageEl) stageEl.style.transition = 'none';
      if (lyrEl) lyrEl.style.transition = 'none';
    }
    function move(e) {
      if (!sw.on || !e.touches || !e.touches.length) return;
      const dx = e.touches[0].clientX - sw.x;
      const dy = e.touches[0].clientY - sw.y;
      if (!moved && Math.abs(dx) < 6) return;
      if (Math.abs(dy) > Math.abs(dx) * 1.2) return;   // 纵向滚歌词不抢
      moved = true;
      sw.dx = dx;
    }
    function end() {
      if (!sw.on) return;
      sw.on = false;
      const w = width();
      const base = document.body.classList.contains('lyr-open') ? 1 : 0;
      if (moved && Math.abs(sw.dx) > Math.min(80, w * 0.18)) {
        finish(sw.dx < 0);            // 向左滑 → 歌词，向右滑 → 歌曲
      } else {
        finish(base === 1);           // 没滑够：回弹
      }
    }
    [mainEl, stageEl, lyrEl].forEach(function (el) {
      if (!el) return;
      el.addEventListener('touchstart', start, { passive: true });
      el.addEventListener('touchmove', move, { passive: true });
      el.addEventListener('touchend', end, { passive: true });
      el.addEventListener('touchcancel', end, { passive: true });
    });
    window.__luvLyricPages = { apply: apply, finish: finish };
    // 初始化一次：确保内联 transform 和当前 lyr-open 状态一致
    try { apply(document.body.classList.contains('lyr-open') ? 1 : 0); } catch (e) {}
  })();

  tabs.addEventListener('click', function (e) {
    const b = e.target.closest('button');
    if (!b) return;
    const lyr = b.getAttribute('data-v') === 'lyr';
    // 用滑动页的 finish：同时改 transform 和按钮高亮，避免"显示的歌页/高亮在歌词"
    if (window.__luvLyricPages && window.__luvLyricPages.finish) {
      window.__luvLyricPages.finish(lyr);
    } else {
      setLyrView(lyr);
    }
  });
}

/* ── 手机版：歌单顺序 / 倒序切换 ── */
function buildMobileOrderBtn() {
  if (document.getElementById('libOrder')) return;
  const head = document.querySelector('.lib-head');
  if (!head) return;
  const btn = document.createElement('button');
  btn.id = 'libOrder';
  btn.className = 'mini-btn ghost';
  btn.title = '切换歌单播放顺序';
  btn.textContent = S.plDesc ? '倒序' : '正序';
  btn.addEventListener('click', function () {
    S.plDesc = !S.plDesc;
    btn.textContent = S.plDesc ? '倒序' : '正序';
    const s = document.getElementById('libSearch');
    Queue.render(s ? s.value : '');
    saveConfig();
    toast(S.plDesc ? '歌单倒序播放' : '歌单顺序播放');
  });
  head.appendChild(btn);
}

/* ── 安卓外壳桥：/android.html 用 iframe 调用这里的视图切换 ── */
window.__LUV = {
  view: function (name) {
    try {
      // 先把其它模式全部退掉，避免「点了音乐还是搜索页」这类残留
      try { Settings.close(); } catch (e) {}
      document.body.classList.remove('lib-mode', 'set-mode', 'online-mode', 'admin-mode', 'friends-mode');
      ['settingsView', 'adminView', 'friendsView'].forEach(function (id) {
        var v = document.getElementById(id);
        if (v) v.setAttribute('aria-hidden', 'true');
      });
      try { if (document.activeElement && document.activeElement.blur) document.activeElement.blur(); } catch (e) {}
      if (name === 'lib') { setView('lib'); return true; }
      if (name === 'play') { setView('play'); return true; }
      if (name === 'settings') { Settings.open(); return true; }
      if (name === 'admin') { AdminPage.open(); return true; }
      if (name === 'friends') { FriendsPage.open(); return true; }
    } catch (e) { console.warn('__LUV.view', name, e); }
    return false;
  },
  hideChrome: function () {
    try {
      document.body.classList.add('android');
      document.documentElement.classList.add('android');
      var tb = document.getElementById('titlebar');
      if (tb) tb.style.display = 'none';
      // 外壳已经有自己的进入动画，内嵌页面不再重复播一遍
      var boot = document.getElementById('boot');
      if (boot) boot.style.display = 'none';
      var appEl = document.getElementById('app');
      if (appEl) appEl.classList.add('in');
      var libNewBtn = document.getElementById('libNew');
      if (libNewBtn) libNewBtn.textContent = '＋ 新建';
      var libRescanBtn = document.getElementById('libRescan');
      if (libRescanBtn) libRescanBtn.textContent = '选歌';
      buildMobileBar();
      buildMobileOrderBtn();
    } catch (e) { console.warn('hideChrome', e); }
  },
  state: function () {
    return { tracks: S.tracks.length, title: (S.tracks[S.idx] || {}).title || '', theme: S.theme };
  },
  /* 回到前台：检测音频时间是否漂移（后台被冻结导致快进/卡顿），异常就重载到上次位置 */
  afterResume: function () {
    try {
      var a = (typeof Engine !== 'undefined' && Engine.audio) ? Engine.audio : null;
      if (!a || !a.duration || !S.playing) return;
      var lastPos = window.__luvLastPos || 0;
      var lastAt = window.__luvLastAt || 0;
      var expect = lastPos + (lastAt ? (Date.now() - lastAt) / 1000 : 0);
      if (Math.abs(a.currentTime - expect) > 8) {
        try { a.load(); } catch (e) {}
        try { a.currentTime = lastPos; } catch (e) {}
        try { a.play(); } catch (e) {}
        toast('播放位置已校准', 1500);
      }
    } catch (e) {}
  },
  setTheme: setTheme,
  // 安卓原生系统媒体卡片上的按钮回调
  mediaAction: function (action, seekMs) {
    try {
      if (action === 'play') Player.play();
      else if (action === 'pause') Player.pause();
      else if (action === 'next') Player.next();
      else if (action === 'prev') Player.prev();
      else if (action === 'stop') Player.pause();
      else if (action === 'seek' && isFinite(seekMs) && seekMs >= 0) {
        const t = seekMs / 1000;
        if (Engine.audio) Engine.audio.currentTime = t;
        Lyrics.sync(t, true);
      }
      pushMedia(true);
    } catch (e) { console.warn('mediaAction', e); }
  },
  // 安卓：系统相册选完图后由原生回调过来
  applyCustomWall: function () {
    try {
      var u = '/wall/custom.jpg?t=' + Date.now();
      // 直接把新图铺到壁纸层上，不用等轮换/淡入，选完立刻可见
      var a = document.getElementById('bgWallA');
      var b = document.getElementById('bgWallB');
      if (a) { a.style.backgroundImage = 'url("' + u + '")'; a.classList.add('show'); }
      if (b) { b.classList.remove('show'); }
      var bgEl = document.getElementById('bg');
      if (bgEl) {
        bgEl.style.backgroundImage = 'url("' + u + '")';
        bgEl.style.backgroundSize = 'cover';
        bgEl.style.backgroundPosition = 'center';
      }
      // #app 是 iframe 内最外层容器，直接给它铺图最稳（不依赖 iframe 透明合成）
      var appBg = document.getElementById('app');
      if (appBg) {
        appBg.style.backgroundImage = 'url("' + u + '")';
        appBg.style.backgroundSize = 'cover';
        appBg.style.backgroundPosition = 'center';
        appBg.style.backgroundRepeat = 'no-repeat';
      }
      S.customWallUrl = u;
      S.wallMode = 'custom';
      S.wallOn = true;
      document.body.classList.add('wall-on');
      try { Wallpapers.setMode('custom'); } catch (e) {}
      try { applyWallFilter(); } catch (e) {}
      try { Settings.renderWalls(); Settings.renderChips(); } catch (e) {}
      saveConfig();
      toast('壁纸已更换');
      try {
        if (window.parent && window.parent !== window) {
          window.parent.postMessage({ type: 'luvsic-wall' }, '*');
        }
      } catch (e) {}
    } catch (e) { console.warn('applyCustomWall', e); }
  },
  toast: function (m) { try { toast(m); } catch (e) {} },
};

})();
