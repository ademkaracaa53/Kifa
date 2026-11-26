// eFAM Transkript — Cloudflare Worker (bulut motoru)
//
// Uygulamadan gelen kısa konuşma parçalarını (16 kHz WAV) Cloudflare Workers AI üzerindeki
// Whisper large-v3-turbo modeliyle metne çevirir. Kurulum: cloudflare/README.md
//
// Gerekenler (Cloudflare panelinde Worker → Settings):
//   • Bindings → Workers AI, adı: AI
//   • Variables and Secrets → ACCESS_CODE (Secret): uygulamaya gireceğiniz erişim kodu
//
// İstek:  POST /?lang=tr   (lang: tr | en | auto)
//         Başlık: x-access-code: <ACCESS_CODE>
//         Gövde: audio/wav (en fazla ~30 sn)
// Yanıt:  { "text": "...", "language": "tr" }

const MODEL = '@cf/openai/whisper-large-v3-turbo';
const MAX_BYTES = 2 * 1024 * 1024; // 16 kHz mono 16 bit: ~65 sn

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'POST, GET, OPTIONS',
  'Access-Control-Allow-Headers': 'content-type, x-access-code',
  'Access-Control-Max-Age': '86400',
};

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), { status, headers: { ...CORS, 'content-type': 'application/json; charset=utf-8' } });
}

function toBase64(bytes) {
  let s = '';
  const step = 0x8000;
  for (let i = 0; i < bytes.length; i += step) s += String.fromCharCode.apply(null, bytes.subarray(i, i + step));
  return btoa(s);
}

// Zamanlamaya dayalı karşılaştırma sızıntısı olmasın
function sameCode(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') return new Response(null, { headers: CORS });
    if (request.method === 'GET') return json({ ok: true, service: 'eFAM Transkript', model: MODEL, ai: !!env.AI, code: !!env.ACCESS_CODE });
    if (request.method !== 'POST') return json({ error: 'Yalnızca POST' }, 405);

    if (!env.AI) return json({ error: 'Sunucuda Workers AI bağlantısı (AI) tanımlı değil' }, 500);
    if (!env.ACCESS_CODE) return json({ error: 'Sunucuda ACCESS_CODE tanımlı değil' }, 500);
    if (!sameCode(request.headers.get('x-access-code') || '', env.ACCESS_CODE)) return json({ error: 'Erişim kodu yanlış' }, 401);

    const bytes = new Uint8Array(await request.arrayBuffer());
    if (bytes.length < 1000) return json({ text: '', language: null });
    if (bytes.length > MAX_BYTES) return json({ error: 'Ses parçası çok uzun' }, 413);

    const lang = new URL(request.url).searchParams.get('lang') || 'tr';
    const input = { audio: toBase64(bytes), task: 'transcribe' };
    if (lang === 'tr' || lang === 'en') input.language = lang;

    try {
      const r = await env.AI.run(MODEL, input);
      return json({ text: (r && r.text) || '', language: (r && r.transcription_info && r.transcription_info.language) || input.language || null });
    } catch (e) {
      const msg = String((e && e.message) || e);
      // günlük ücretsiz kota bittiğinde ya da geçici hatada uygulama anlaşılır mesaj göstersin
      const status = /limit|quota|429|exceeded/i.test(msg) ? 429 : 502;
      return json({ error: msg }, status);
    }
  },
};
