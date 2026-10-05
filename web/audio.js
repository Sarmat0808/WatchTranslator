// Запись с микрофона: сама понимает, когда человек закончил говорить.
// Возвращает звук 16 кГц (как нужно Whisper).

let stream = null;
let ctx = null;
let releaseTimer = null;

export function getAudioContext() {
  if (!ctx) {
    const AC = window.AudioContext || window.webkitAudioContext;
    ctx = new AC();
  }
  return ctx;
}

async function getStream() {
  clearTimeout(releaseTimer);
  if (stream && stream.getTracks().some((t) => t.readyState === 'live')) return stream;
  stream = await navigator.mediaDevices.getUserMedia({
    audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
  });
  return stream;
}

/** Отпускаем микрофон через минуту бездействия (гаснет оранжевая точка iPhone). */
function scheduleRelease() {
  clearTimeout(releaseTimer);
  releaseTimer = setTimeout(releaseMic, 60_000);
}

export function releaseMic() {
  if (stream) stream.getTracks().forEach((t) => t.stop());
  stream = null;
}

document.addEventListener('visibilitychange', () => {
  if (document.hidden) releaseMic();
});

export class Recorder {
  constructor() {
    this.stopRequested = false;
    this.cancelled = false;
  }

  stop() {
    this.stopRequested = true;
  }

  cancel() {
    this.cancelled = true;
    this.stopRequested = true;
  }

  /** onLevel(0..1) — для анимации. Возвращает Float32Array 16 кГц или null. */
  async record(onLevel) {
    const ac = getAudioContext();
    if (ac.state === 'suspended') await ac.resume();
    const s = await getStream();
    const src = ac.createMediaStreamSource(s);
    const proc = ac.createScriptProcessor(4096, 1, 1);
    const mute = ac.createGain();
    mute.gain.value = 0;
    const rate = ac.sampleRate;
    const chunks = [];
    let total = 0;

    // Анализ кусками по 100 мс
    const win = Math.round(rate / 10);
    let pending = new Float32Array(0);
    let noise = 0.008;
    let windows = 0;
    let heard = false;
    let silent = 0;

    return new Promise((resolve) => {
      const finish = () => {
        proc.onaudioprocess = null;
        try { src.disconnect(); proc.disconnect(); mute.disconnect(); } catch (_) {}
        scheduleRelease();
        if (this.cancelled) return resolve(null);
        if (!heard && !this.stopRequested) return resolve(new Float32Array(0));
        const all = new Float32Array(total);
        let o = 0;
        for (const c of chunks) { all.set(c, o); o += c.length; }
        resolve(resample(all, rate, 16000));
      };

      proc.onaudioprocess = (ev) => {
        const data = new Float32Array(ev.inputBuffer.getChannelData(0));
        chunks.push(data);
        total += data.length;
        const merged = new Float32Array(pending.length + data.length);
        merged.set(pending);
        merged.set(data, pending.length);
        let i = 0;
        for (; i + win <= merged.length; i += win) {
          let sum = 0;
          for (let k = i; k < i + win; k++) sum += merged[k] * merged[k];
          const rms = Math.sqrt(sum / win);
          windows++;
          if (windows <= 3) noise = Math.max(noise, rms);
          const thr = Math.max(0.012, noise * 2.5);
          onLevel && onLevel(Math.min(1, rms / (thr * 4)));
          if (rms > thr) { heard = true; silent = 0; } else if (heard) silent++;
        }
        pending = merged.slice(i);
        const done =
          this.stopRequested ||
          (heard && silent >= 15) || // 1,5 с тишины после речи
          (!heard && windows >= 80) || // 8 с без речи
          windows >= 250; // максимум 25 с
        if (done) finish();
      };
      src.connect(proc);
      proc.connect(mute);
      mute.connect(ac.destination);
    });
  }
}

/** Пересэмплирование с фильтром от наложения частот (чтобы Whisper лучше понимал). */
export function resample(input, from, to) {
  if (from === to) return input;
  const ratio = from / to;
  const outLen = Math.floor(input.length / ratio);
  const out = new Float32Array(outLen);
  const cutoff = Math.min(1, to / from) * 0.9;
  const half = 16;
  const taps = [];
  for (let k = -half; k <= half; k++) {
    const x = k * cutoff;
    const sinc = k === 0 ? 1 : Math.sin(Math.PI * x) / (Math.PI * x);
    const w = 0.5 * (1 + Math.cos((Math.PI * k) / (half + 1)));
    taps.push(cutoff * sinc * w);
  }
  for (let i = 0; i < outLen; i++) {
    const center = i * ratio;
    const c0 = Math.floor(center);
    let acc = 0;
    for (let k = -half; k <= half; k++) {
      const j = c0 + k;
      if (j >= 0 && j < input.length) acc += input[j] * taps[k + half];
    }
    out[i] = acc;
  }
  return out;
}

/** Разбор WAV (для самопроверки). */
export function parseWav(buf) {
  const v = new DataView(buf);
  let p = 12;
  let rate = 16000;
  let bits = 16;
  let channels = 1;
  while (p < v.byteLength) {
    const id = String.fromCharCode(v.getUint8(p), v.getUint8(p + 1), v.getUint8(p + 2), v.getUint8(p + 3));
    const size = v.getUint32(p + 4, true);
    if (id === 'fmt ') {
      channels = v.getUint16(p + 10, true);
      rate = v.getUint32(p + 12, true);
      bits = v.getUint16(p + 22, true);
    } else if (id === 'data') {
      const n = size / (bits / 8) / channels;
      const out = new Float32Array(n);
      for (let i = 0; i < n; i++) {
        const off = p + 8 + i * channels * (bits / 8);
        out[i] = bits === 16 ? v.getInt16(off, true) / 32768 : v.getFloat32(off, true);
      }
      return resample(out, rate, 16000);
    }
    p += 8 + size + (size % 2);
  }
  throw new Error('bad wav');
}
