// Whisper (transformers.js) ile tarayıcı içinde konuşma → metin. Ses cihazdan çıkmaz.
// Model ilk kullanımda Hugging Face'ten indirilir ve tarayıcı önbelleğinde tutulur.
import { pipeline, env } from 'https://cdn.jsdelivr.net/npm/@huggingface/transformers@4.3.2';

env.allowLocalModels = false;

const SR = 16000;
const BATCH_S = 240; // sesi ~4 dakikalık parçalarla işle: ilerleme göstermek ve sonuçları parça parça vermek için

let asr = null;
let loadedKey = '';

function dtypeFor(model, device) {
  if (device === 'webgpu') {
    return model.includes('large')
      ? { encoder_model: 'fp16', decoder_model_merged: 'q4' }
      : { encoder_model: 'fp32', decoder_model_merged: 'q4' };
  }
  return 'q8';
}

async function load(model, device) {
  const key = model + '|' + device;
  if (asr && loadedKey === key) return;
  if (asr) { try { await asr.dispose(); } catch {} asr = null; }
  asr = await pipeline('automatic-speech-recognition', model, {
    device,
    dtype: dtypeFor(model, device),
    progress_callback: (p) => {
      if (p.status === 'progress' || p.status === 'initiate' || p.status === 'done') {
        self.postMessage({ type: 'download', file: p.file, loaded: p.loaded || 0, total: p.total || 0, status: p.status });
      }
    },
  });
  loadedKey = key;
}

// Parça sınırını en sessiz ana kaydır ki kelimeler ortadan bölünmesin
function quietestPoint(audio, around) {
  const win = SR * 3, step = SR / 20, frame = SR / 20;
  let best = around, bestE = Infinity;
  for (let c = Math.max(frame, around - win); c < Math.min(audio.length - frame, around + win); c += step) {
    let e = 0;
    for (let i = c - frame / 2; i < c + frame / 2; i++) e += audio[i] * audio[i];
    if (e < bestE) { bestE = e; best = c; }
  }
  return Math.round(best);
}

self.onmessage = async ({ data }) => {
  if (data.type !== 'run') return;
  const { audio, model, language, task } = data;
  let device = data.device;
  try {
    self.postMessage({ type: 'status', text: 'Model yükleniyor…' });
    try {
      await load(model, device);
    } catch (e) {
      if (device !== 'webgpu') throw e;
      device = 'wasm';
      self.postMessage({ type: 'status', text: 'WebGPU kullanılamadı, WASM ile devam ediliyor…' });
      await load(model, device);
    }
    self.postMessage({ type: 'ready', device });

    const total = audio.length;
    let start = 0;
    while (start < total) {
      let end = Math.min(total, start + BATCH_S * SR);
      if (end < total) end = quietestPoint(audio, end);
      const slice = audio.subarray(start, end);
      const out = await asr(slice, {
        language: language === 'auto' ? null : language,
        task,
        chunk_length_s: 30,
        stride_length_s: 5,
        return_timestamps: true,
      });
      const offset = start / SR;
      const chunks = (out.chunks && out.chunks.length ? out.chunks : [{ text: out.text, timestamp: [0, slice.length / SR] }])
        .map((c) => ({ t: offset + (c.timestamp?.[0] ?? 0), text: (c.text || '').trim() }))
        .filter((c) => c.text);
      start = end;
      self.postMessage({ type: 'partial', chunks, progress: start / total });
    }
    self.postMessage({ type: 'done' });
  } catch (e) {
    self.postMessage({ type: 'error', message: String(e && e.message || e) });
  }
};
