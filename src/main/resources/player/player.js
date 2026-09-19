'use strict';
const video = document.getElementById('video');
const message = document.getElementById('message');
const start = document.getElementById('start');
let generation = -1, hls = null, active = false, mediaRecoveries = 0;
const danmakuLayer = document.getElementById('danmaku');
let lastDanmaku = 0, liveRequest = 0, nextLane = 0, lastMetrics = 0;
const lanes = new Array(8).fill(null);

function report(event, extra = {}) {
  fetch('event', {method: 'POST', headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({generation, event, ...extra})}).catch(() => {});
}
function show(text) { message.textContent = text; message.hidden = !text; }
function destroy() {
  active = false;
  if (hls) { hls.destroy(); hls = null; }
  video.pause(); video.removeAttribute('src'); video.load(); start.hidden = true;
  danmakuLayer.replaceChildren();
  lanes.fill(null);
}
function displayDanmaku(item) {
  if (!active || danmakuLayer.childElementCount >= 32) return;
  const now = performance.now();
  const span = document.createElement('span');
  span.className = 'danmaku-item';
  if (item.gift) span.classList.add('gift-item');
  span.textContent = item.displayText || item.text; // Untrusted names and chat are text, never HTML.
  danmakuLayer.appendChild(span);
  const distance = video.clientWidth + span.offsetWidth + 24;
  const speed = distance / 7000;
  const laneCount = Math.max(1, Math.min(8, Math.floor(video.clientHeight / 38)));
  let lane = -1;
  for (let i = 0; i < laneCount; i++) {
    const candidate = (nextLane + i) % laneCount;
    const previous = lanes[candidate];
    const remaining = previous ? previous.end - now : 0;
    // Leave room for the previous tail, and prevent a longer/faster message from catching it.
    if (!previous || remaining <= 0 ||
        (previous.speed * (now - previous.start) >= previous.width + 24 &&
         (speed <= previous.speed || speed * remaining <= video.clientWidth))) {
      lane = candidate; break;
    }
  }
  if (lane < 0) { span.remove(); return; }
  nextLane = (lane + 1) % laneCount;
  lanes[lane] = {start:now, end:now + 7000, width:span.offsetWidth, speed};
  span.style.top = (lane * 34 + 12) + 'px';
  const animation = span.animate([{transform:'translateX(0)'},{transform:'translateX(-'+distance+'px)'}], {duration:7000,easing:'linear'});
  animation.onfinish = () => span.remove();
}
async function resume() {
  try { await video.play(); start.hidden = true; }
  catch (error) {
    if (error.name === 'NotAllowedError') { start.hidden = false; report('autoplay'); }
  }
}
start.addEventListener('click', resume);
video.addEventListener('playing', () => { if (active) { show(''); report('playing'); } });
video.addEventListener('waiting', () => { if (active) { show('正在缓冲…'); report('buffering'); } });

function play(source) {
  if (!Hls.isSupported()) { show('当前浏览器内核不支持 HLS/MSE'); report('error'); return; }
  active = true; mediaRecoveries = 0;
  show('正在加载直播流…');
  hls = new Hls({
    lowLatencyMode: true,
    liveSyncDurationCount: 1,
    liveMaxLatencyDurationCount: 3,
    maxLiveSyncPlaybackRate: 1.1,
    maxBufferLength: 4,
    maxMaxBufferLength: 8,
    backBufferLength: 0,
    enableWorker: true
  });
  hls.on(Hls.Events.MANIFEST_PARSED, resume);
  hls.on(Hls.Events.ERROR, (_event, data) => {
    if (!data.fatal || !active) return;
    if (data.type === Hls.ErrorTypes.MEDIA_ERROR && mediaRecoveries++ < 1) {
      hls.recoverMediaError(); return;
    }
    destroy(); show('播放中断或地址已过期，请重新取流或重新粘贴地址'); report('error');
  });
  hls.attachMedia(video);
  hls.loadSource(source);
}
async function poll() {
  try {
    const response = await fetch('state', {cache: 'no-store'});
    if (!response.ok) throw new Error('closed');
    const state = await response.json();
    video.volume = state.volume; video.muted = state.muted;
    if (state.generation !== generation) {
      destroy(); generation = state.generation;
      document.body.classList.toggle('audio-only', state.playing && state.audioOnly);
      document.getElementById('audio-mode').hidden = !(state.playing && state.audioOnly);
      if (state.playing) play(state.source); else show('已停止');
    }
    if (!state.danmakuEnabled) { danmakuLayer.replaceChildren(); lanes.fill(null); }
    for (const item of state.danmaku || []) {
      if (item.sequence > lastDanmaku && state.danmakuEnabled) displayDanmaku(item);
      lastDanmaku = Math.max(lastDanmaku,item.sequence);
    }
    if (state.liveRequest !== liveRequest) {
      liveRequest = state.liveRequest;
      if (hls && Number.isFinite(hls.liveSyncPosition)) { video.currentTime = hls.liveSyncPosition; video.playbackRate = 1; resume(); }
    }
    if (hls && active && !video.paused && video.readyState >= 3 && performance.now() - lastMetrics > 2000) {
      lastMetrics = performance.now();
      if (Number.isFinite(hls.latency)) report('metrics',{distance:hls.latency});
    }
  } catch (_) { destroy(); show('播放服务已关闭'); return; }
  setTimeout(poll, 200);
}
window.addEventListener('pagehide', destroy);
document.body.addEventListener('dblclick', () => report('view:focus'));
document.addEventListener('keydown', event => {
  if (event.repeat) return;
  if (event.key === 'Escape' || event.key === 'F11') {
    event.preventDefault();report(event.key === 'Escape' ? 'view:escape' : 'view:fullscreen');
  }
});
poll();
