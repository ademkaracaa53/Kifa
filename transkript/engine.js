// Transkript — modelden bağımsız yardımcılar: konuşma bölütleme (VAD), konuşmacı gruplama, metin temizliği.
// Hem sayfada (window.TK) hem Node testlerinde (module.exports) kullanılır.
(function (root) {
  'use strict';
  const SR = 16000;

  // Basit enerji tabanlı konuşma algılayıcı. 16 kHz mono örnekleri parça parça alır,
  // sessizlikle biten konuşma bölümlerini (utterance) onUtt ile verir.
  class Segmenter {
    constructor(onUtt, opts = {}) {
      this.onUtt = onUtt;
      this.frame = opts.frame || 480;                       // 30 ms
      this.endSilence = opts.endSilence ?? 0.7;             // bu kadar sessizlik bölümü bitirir (sn)
      this.maxLen = opts.maxLen ?? 14;                      // daha uzun bölüm en sessiz yerden kesilir (sn)
      this.minSpeech = opts.minSpeech ?? 0.35;              // daha kısa sesler gürültü sayılır (sn)
      this.preRoll = opts.preRoll ?? 0.25;                  // konuşma başından önce tutulan ses (sn)
      this.absMin = opts.absMin ?? 0.006;                   // mutlak eşik (RMS)
      this.ratio = opts.ratio ?? 3;                         // gürültü tabanının kaç katı konuşma sayılır
      this.noise = opts.noise ?? 0.004;
      this.pos = 0;                                         // işlenen toplam örnek
      this.pending = new Float32Array(0);
      this.history = [];                                    // ön-dolgu için son çerçeveler
      this.cur = null;                                      // { start, frames:[], energies:[], speech, silence }
      this.level = 0;
    }
    push(samples) {
      let buf = samples;
      if (this.pending.length) { buf = new Float32Array(this.pending.length + samples.length); buf.set(this.pending); buf.set(samples, this.pending.length); }
      const F = this.frame;
      let i = 0;
      for (; i + F <= buf.length; i += F) this._frame(buf.slice(i, i + F));
      this.pending = buf.slice(i);
    }
    _frame(f) {
      let e = 0;
      for (let k = 0; k < f.length; k++) e += f[k] * f[k];
      const rms = Math.sqrt(e / f.length);
      this.level = rms;
      const thr = Math.max(this.absMin, this.noise * this.ratio);
      const speech = rms > thr;
      // gürültü tabanı: sessizken hızlı, konuşurken çok yavaş uyum
      if (!speech) this.noise = this.noise * 0.95 + rms * 0.05;
      else this.noise = Math.min(this.noise * 1.002, rms);
      const fs = this.frame / SR;
      const start = this.pos;
      this.pos += f.length;
      if (!this.cur) {
        this.history.push(f);
        const keep = Math.ceil(this.preRoll / fs);
        while (this.history.length > keep) this.history.shift();
        if (speech) {
          const pre = this.history.slice(0, -1);
          this.cur = { start: start - pre.length * this.frame, frames: [...pre, f], energies: [...pre.map(() => 0), rms], speech: fs, silence: 0 };
          this.history = [];
        }
        return;
      }
      const c = this.cur;
      c.frames.push(f); c.energies.push(rms);
      if (speech) { c.speech += fs; c.silence = 0; } else c.silence += fs;
      const len = c.frames.length * fs;
      if (c.silence >= this.endSilence) this._emit(c.frames.length - Math.floor(c.silence / fs) + 2);
      else if (len >= this.maxLen) this._cutLongest();
    }
    _cutLongest() {
      // son 4 saniyedeki en sessiz çerçeveden kes, kalan konuşma yeni bölüm olarak devam etsin
      const c = this.cur, fs = this.frame / SR;
      const from = Math.max(1, c.frames.length - Math.round(4 / fs));
      let best = c.frames.length - 1, bestE = Infinity;
      for (let k = from; k < c.frames.length; k++) if (c.energies[k] < bestE) { bestE = c.energies[k]; best = k; }
      const restF = c.frames.slice(best), restE = c.energies.slice(best);
      const restStart = c.start + best * this.frame;
      this._emit(best, true);
      this.cur = { start: restStart, frames: restF, energies: restE, speech: restF.length * fs, silence: 0 };
    }
    _emit(nFrames, forced) {
      const c = this.cur; this.cur = null;
      nFrames = Math.min(Math.max(nFrames, 1), c.frames.length);
      if (c.speech < this.minSpeech && !forced) return;
      const audio = new Float32Array(nFrames * this.frame);
      for (let k = 0; k < nFrames; k++) audio.set(c.frames[k], k * this.frame);
      this.onUtt({ start: c.start / SR, end: (c.start + audio.length) / SR, audio });
    }
    // Henüz bitmemiş (konuşma sürerken) bölümün sesi — ara metin göstermek için
    current() {
      const c = this.cur;
      if (!c) return null;
      const audio = new Float32Array(c.frames.length * this.frame);
      for (let k = 0; k < c.frames.length; k++) audio.set(c.frames[k], k * this.frame);
      return { start: c.start / SR, audio };
    }
    flush() {
      if (this.pending.length) { const p = new Float32Array(this.frame); p.set(this.pending); this.pending = new Float32Array(0); this._frame(p); }
      if (this.cur) this._emit(this.cur.frames.length - Math.floor(this.cur.silence / (this.frame / SR)));
    }
  }

  // Herhangi bir örnekleme hızından 16 kHz'e (ortalama alarak) indirger; parça parça çağrılabilir.
  class Resampler {
    constructor(fromRate) { this.r = fromRate / SR; this.t = 0; this.acc = 0; this.n = 0; }
    push(x) {
      if (this.r === 1) return x.slice();
      const out = new Float32Array(Math.ceil(x.length / this.r) + 1);
      let o = 0;
      for (let i = 0; i < x.length; i++) {
        this.acc += x[i]; this.n++; this.t += 1;
        if (this.t >= this.r) { out[o++] = this.acc / this.n; this.acc = 0; this.n = 0; this.t -= this.r; }
      }
      return out.subarray(0, o);
    }
  }

  // Konuşmacı ses izlerini (embedding) çevrimiçi kümeler: yeterince benzer → aynı konuşmacı.
  class Speakers {
    constructor(opts = {}) {
      this.threshold = opts.threshold ?? 0.8;
      this.max = opts.max ?? 8;
      this.cents = opts.cents || [];   // [{ id, v:number[], n }]
      this.last = opts.last || 0;
    }
    static norm(v) {
      let s = 0; for (let i = 0; i < v.length; i++) s += v[i] * v[i];
      s = Math.sqrt(s) || 1;
      return Array.from(v, (x) => x / s);
    }
    static cos(a, b) { let s = 0; for (let i = 0; i < a.length; i++) s += a[i] * b[i]; return s; }
    nextId() { return this.cents.reduce((m, c) => Math.max(m, c.id), 0) + 1; }
    assign(emb, dur) {
      if (!emb || !emb.length) return this.last || this._new(null);
      const v = Speakers.norm(emb);
      let best = null, bestS = -2;
      for (const c of this.cents) { const s = Speakers.cos(v, c.v); if (s > bestS) { bestS = s; best = c; } }
      const short = dur < 1.6;
      // kısa bölümlerin ses izi güvenilmez: eşiği gevşet, ortalamayı bozma
      const thr = short ? this.threshold - 0.12 : this.threshold;
      if (best && (bestS >= thr || this.cents.length >= this.max)) {
        if (!short) this._update(best, v);
        return (this.last = best.id);
      }
      if (short && this.last) return this.last;
      return this._new(v);
    }
    _new(v) {
      const id = this.nextId();
      if (v) this.cents.push({ id, v, n: 1 });
      return (this.last = id);
    }
    _update(c, v) {
      const w = Math.min(c.n, 20);
      c.v = Speakers.norm(c.v.map((x, i) => (x * w + v[i]) / (w + 1)));
      c.n++;
    }
    // Kullanıcı iki konuşmacıyı birleştirdiğinde
    merge(from, to) {
      const a = this.cents.find((c) => c.id === from), b = this.cents.find((c) => c.id === to);
      if (a && b) { b.v = Speakers.norm(b.v.map((x, i) => x * b.n + a.v[i] * a.n)); b.n += a.n; }
      this.cents = this.cents.filter((c) => c.id !== from);
      if (this.last === from) this.last = to;
    }
    toJSON() { return { cents: this.cents, last: this.last }; }
  }

  // Whisper'ın sessizlik/gürültüde uydurduğu bilinen kalıplar
  const HALLU = [
    /^[^a-zçğıöşü]*alt ?yaz[ıi]/, /izledi[ğg]iniz i[çc]in te[şs]ekk[üu]r/, /abone ol/, /kanal[ıi]ma abone/,
    /thanks? (you )?for watching/, /please subscribe/, /^[^a-zçğıöşü]*(music|müzik|alkış|applause)[^a-zçğıöşü]*$/,
    /^[\s.,!?♪♫*\-–—…"'()[\]]*$/, /^\W*(\[.*\]|\(.*\))\W*$/,
  ];
  function cleanText(t) {
    t = (t || '').replace(/\s+/g, ' ').trim();
    if (!t) return '';
    const low = t.toLocaleLowerCase('tr');
    if (HALLU.some((r) => r.test(low))) return '';
    // aynı ifadenin art arda tekrarını (Whisper döngüsü) tek sefere indir
    t = t.replace(/(\b[^.!?]{4,}?[.!?]?)(\s+\1){2,}/gi, '$1');
    return t;
  }

  const api = { SR, Segmenter, Resampler, Speakers, cleanText };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.TK = api;
})(typeof self !== 'undefined' ? self : this);
