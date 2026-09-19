'use strict';
const video = document.getElementById('video');
const message = document.getElementById('message');
const start = document.getElementById('start');
let generation = -1, hls = null, active = false, mediaRecoveries = 0;

function report(event) {
  fetch('event', {method: 'POST', headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({generation, event})}).catch(() => {});
}
function show(text) { message.textContent = text; message.hidden = !text; }
function destroy() {
  active = false;
  if (hls) { hls.destroy(); hls = null; }
  video.pause(); video.removeAttribute('src'); video.load(); start.hidden = true;
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
      if (state.playing) play(state.source); else show('已停止');
    }
  } catch (_) { destroy(); show('播放服务已关闭'); return; }
  setTimeout(poll, 200);
}
window.addEventListener('pagehide', destroy);
poll();
