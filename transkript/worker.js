// Transkript — modeller bu Web Worker'da, doğrudan tarayıcıda çalışır; ses hiçbir sunucuya gitmez.
//  • Whisper (konuşma → noktalamalı metin)
//  • WavLM-SV (konuşmacı ses izi; "Konuşmacı 1 / 2" ayrımı için)
// Modeller ilk kullanımda Hugging Face'ten indirilir, sonra tarayıcı önbelleğinden açılır.
import {
  pipeline, env, AutoProcessor, AutoModelForXVector, LogitsProcessor,
} from 'https://cdn.jsdelivr.net/npm/@huggingface/transformers@4.3.2';

env.allowLocalModels = false;

const SPK_MODEL = 'Xenova/wavlm-base-plus-sv';
let asr = null, asrKey = '', device = 'wasm';
let spkProc = null, spkModel = null;
let downloads = {};
let lastPost = 0;

function progress(p) {
  if (!p.file) return;
  if (p.status === 'initiate' || p.status === 'progress' || p.status === 'done') {
    const f = (downloads[p.file] ||= { loaded: 0, total: 0 });
    if (p.total) f.total = p.total;
    f.loaded = p.status === 'done' ? f.total : (p.loaded || f.loaded);
    let l = 0, t = 0;
    for (const k in downloads) { l += downloads[k].loaded; t += downloads[k].total; }
    // ilerleme mesajlarını seyrelt (saniyede ~4), sayfayı meşgul etmesin
    const now = Date.now();
    if (now - lastPost < 250 && p.status === 'progress') return;
    lastPost = now;
    self.postMessage({ type: 'download', loaded: l, total: t });
  }
}
// Ortam bilgisi: çok çekirdek (crossOriginIsolated) açık mı?
const threads = self.crossOriginIsolated ? Math.min(4, Math.max(1, Math.ceil((navigator.hardwareConcurrency || 2) / 2))) : 1;
if (env.backends.onnx && env.backends.onnx.wasm) env.backends.onnx.wasm.numThreads = threads;

function asrDtype(model, dev) {
  // İşlemcide: ses kodlayıcıyı (encoder) sıkıştırmak doğruluğu belirgin düşürür; küçük modellerde tam hassasiyet kullan
  if (dev !== 'webgpu') return /small|large/.test(model) ? 'q8' : { encoder_model: 'fp32', decoder_model_merged: 'q8' };
  return model.includes('large') ? { encoder_model: 'fp16', decoder_model_merged: 'q4' } : { encoder_model: 'fp32', decoder_model_merged: 'q4' };
}

// Not: transformers.js'te bir oturum oluşturma hatası, aynı çalışandaki sonraki tüm model yüklemelerini
// de bozuyor. Bu yüzden WebGPU'yu yüklemeden önce yokluyoruz; yine de hata olursa sayfaya "gpufail"
// bildirip çalışanın baştan (WASM ile) kurulmasını istiyoruz.
class GpuFail extends Error {}
async function gpuUsable() {
  try { return !!(navigator.gpu && await navigator.gpu.requestAdapter()); } catch { return false; }
}
async function loadAsr(model, want) {
  if (want === 'webgpu' && !(await gpuUsable())) want = 'wasm';
  const key = model + '|' + want;
  if (asr && asrKey === key) return;
  if (asr) { try { await asr.dispose(); } catch {} asr = null; }
  try {
    asr = await pipeline('automatic-speech-recognition', model, { device: want, dtype: asrDtype(model, want), progress_callback: progress });
  } catch (e) {
    if (want === 'webgpu') throw new GpuFail(String((e && e.message) || e));
    throw e;
  }
  device = want;
  asrKey = key;
}

async function loadSpk() {
  if (spkModel) return;
  spkProc = await AutoProcessor.from_pretrained(SPK_MODEL, { progress_callback: progress });
  try {
    spkModel = await AutoModelForXVector.from_pretrained(SPK_MODEL, { dtype: 'q8', progress_callback: progress });
  } catch {
    spkModel = await AutoModelForXVector.from_pretrained(SPK_MODEL, { dtype: 'fp32', progress_callback: progress });
  }
}

// İlk çözümleme adımında yalnızca <|tr|> ve <|en|> puanlarını okur
class LangScores extends LogitsProcessor {
  constructor(ids) { super(); this.ids = ids; this.scores = null; }
  _call(input_ids, logits) {
    if (!this.scores) { const d = logits[0].data; this.scores = this.ids.map((i) => d[i]); }
    return logits;
  }
}
async function detectTrEn(audio) {
  const gc = asr.model.generation_config;
  const tr = gc.lang_to_id['<|tr|>'], en = gc.lang_to_id['<|en|>'];
  const { input_features } = await asr.processor(audio);
  const lp = new LangScores([tr, en]);
  await asr.model.generate({ inputs: input_features, decoder_input_ids: [gc.decoder_start_token_id], max_new_tokens: 1, logits_processor: [lp] });
  // Türkçe lehine pay bırak: İngilizce ancak açıkça daha olasıysa seçilsin
  return lp.scores && lp.scores[1] - lp.scores[0] > 1.5 ? 'en' : 'tr';
}

async function embed(audio) {
  // ses izi için 4 sn yeterli (süre arttıkça model yavaşlar); uzun bölümde ortadaki kısmı al
  const max = 16000 * 4;
  let a = audio;
  if (a.length > max) { const s = Math.floor((a.length - max) / 2); a = a.subarray(s, s + max); }
  const inputs = await spkProc(a);
  const out = await spkModel(inputs);
  return Array.from(out.embeddings.data);
}

// Otomatik dil: her cümlede ayrı algılama kodlayıcıyı iki kez çalıştırır (yavaş).
// İlk cümlede ve sonra her 4 cümlede bir algıla, arada son dili kullan.
let lastLang = 'tr', sinceDetect = 99;
async function pickLang(lang, audio, interim) {
  if (lang !== 'auto') return lang;
  if (interim) return lastLang;
  if (sinceDetect >= 4) { lastLang = await detectTrEn(audio); sinceDetect = 0; }
  sinceDetect++;
  return lastLang;
}
async function transcribe(m) {
  const language = await pickLang(m.lang, m.audio, m.interim);
  const out = await asr(m.audio, { language, task: 'transcribe', return_timestamps: false, no_repeat_ngram_size: 6 });
  let emb = null;
  if (m.diar && !m.interim && m.audio.length >= 16000 * 0.8) {
    if (!spkModel) await loadSpk();
    emb = await embed(m.audio);
  }
  return { text: out.text || '', language, emb };
}

self.onmessage = async ({ data: m }) => {
  try {
    if (m.type === 'load') {
      downloads = {};
      self.postMessage({ type: 'status', text: 'Modeller yükleniyor…' });
      // bulut motorunda Whisper sunucuda çalışır; burada yalnızca konuşmacı ses izi modeli gerekir
      if (m.asr !== false) await loadAsr(m.model, m.device);
      if (m.diar) await loadSpk();
      self.postMessage({ type: 'ready', device, threads });
    } else if (m.type === 'embed') {
      // yalnızca konuşmacı ses izi (bulut motoru)
      if (!spkModel) await loadSpk();
      const emb = m.audio.length >= 16000 * 0.8 ? await embed(m.audio) : null;
      self.postMessage({ type: 'emb', id: m.id, emb });
    } else if (m.type === 'utt') {
      let r;
      try {
        r = await transcribe(m);
      } catch (e) {
        if (device === 'webgpu') throw new GpuFail(String((e && e.message) || e));
        throw e;
      }
      self.postMessage({ type: 'result', id: m.id, interim: !!m.interim, ...r });
    }
  } catch (e) {
    if (e instanceof GpuFail) { self.postMessage({ type: 'gpufail', message: e.message }); return; }
    if (m.type === 'embed') { self.postMessage({ type: 'emb', id: m.id, emb: null }); return; }
    self.postMessage({ type: 'error', id: m.id, interim: !!m.interim, message: String((e && e.message) || e) });
  }
};
