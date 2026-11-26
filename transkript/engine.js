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
      this.preRoll = opts.preRoll ?? 0.3;
      this.shortLen = opts.shortLen ?? 2;                   // bundan kısa bölüm hemen kesilmesin:
      this.shortSilence = opts.shortSilence ?? 1.0;         // kısa bölümde bu kadar sessizlik bekle (bağlam için)                  // konuşma başından önce tutulan ses (sn)
      this.absMin = opts.absMin ?? 0.004;                   // mutlak eşik (RMS)
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
      const need = (c.frames.length * fs - c.silence) < this.shortLen ? Math.max(this.endSilence, this.shortSilence) : this.endSilence;
      if (c.silence >= need) this._emit(c.frames.length - Math.floor(c.silence / fs) + 2);
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
    // Bir bölümü konuşmacıya ata. Kurallar (yanlışlıkla yeni konuşmacı açmamak için):
    //  • benzerlik ≥ eşik → o konuşmacı
    //  • bir önceki konuşmacı en iyiye çok yakınsa onu tercih et (insanlar art arda konuşur)
    //  • eşiğin biraz altı (belirsiz bölge) → en yakın konuşmacı, ama ortalamasını bozma
    //  • yeni konuşmacıyı yalnızca yeterince uzun (≥ 2 sn) ve açıkça farklı bir bölüm açabilir
    assign(emb, dur) {
      if (!emb || !emb.length) return this.last || this._new(null);
      const v = Speakers.norm(emb);
      let best = null, bestS = -2, lastS = -2;
      for (const c of this.cents) {
        const sc = Speakers.cos(v, c.v);
        if (sc > bestS) { bestS = sc; best = c; }
        if (c.id === this.last) lastS = sc;
      }
      if (!best) return this._new(v);
      const T = this.threshold, soft = T - 0.1;
      let pick = best, pickS = bestS;
      if (this.last && best.id !== this.last && lastS >= soft && lastS >= bestS - 0.04) {
        pick = this.cents.find((c) => c.id === this.last); pickS = lastS;
      }
      const canCreate = dur >= 2 && this.cents.length < this.max;
      if (pickS >= T || (pickS >= soft) || !canCreate) {
        if (pickS >= T && dur >= 1.5) this._update(pick, v);
        return (this.last = pick.id);
      }
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

  // Tüm konuşma bittikten sonra toplu yeniden gruplama (ortalama bağlantılı hiyerarşik kümeleme).
  // items: [{ v:number[] (ses izi), w:number (süre) }]; k verilirse tam k gruba, yoksa eşiğe kadar birleştirir.
  // Dönen etiketler 1'den başlar ve ilk görünme sırasına göre numaralanır.
  function recluster(items, opts = {}) {
    const T = opts.threshold ?? 0.7, k = opts.k || 0, n = items.length;
    const cl = items.map((it, i) => ({ idx: [i], v: Speakers.norm(it.v), w: it.w || 1, on: true }));
    // benzerlik matrisi; birleşmede yalnızca yeni grubun satırı yeniden hesaplanır (n² · boyut yerine n · boyut)
    const S = new Float32Array(n * n);
    for (let i = 0; i < n; i++) for (let j = i + 1; j < n; j++) S[i * n + j] = S[j * n + i] = Speakers.cos(cl[i].v, cl[j].v);
    // Sonuna kadar birleştir; son 8 adımın durumunu ve birleşme benzerliğini kaydet
    let alive = n;
    const snaps = {}, mergeSim = {};   // mergeSim[m]: m gruptan m-1'e inerken birleşen ikilinin benzerliği
    const snap = () => cl.filter((c) => c.on).map((c) => ({ idx: c.idx.slice(), v: c.v.slice(), w: c.w }));
    if (alive <= 9) snaps[alive] = snap();
    while (alive > 1) {
      let bi = -1, bj = -1, bs = -2;
      for (let i = 0; i < n; i++) if (cl[i].on) for (let j = i + 1; j < n; j++) if (cl[j].on && S[i * n + j] > bs) { bs = S[i * n + j]; bi = i; bj = j; }
      mergeSim[alive] = bs;
      const a = cl[bi], c = cl[bj], w = a.w + c.w;
      a.v = Speakers.norm(a.v.map((x, d) => x * a.w + c.v[d] * c.w)); a.w = w; a.idx = a.idx.concat(c.idx);
      c.on = false; alive--;
      for (let j = 0; j < n; j++) if (cl[j].on && j !== bi) S[bi * n + j] = S[j * n + bi] = Speakers.cos(a.v, cl[j].v);
      if (alive <= 9) snaps[alive] = snap();
    }
    // Kaç konuşmacı? Verilmişse o; yoksa birleşme benzerliğinin en sert düştüğü yer.
    // Bir sonraki birleşme hâlâ eşiğin üstündeyse (aynı kişi) daha az gruba in.
    let K = k ? Math.min(k, n) : 1;
    if (!k) {
      let bestGap = 0;
      for (let m = 2; m <= Math.min(8, n); m++) {
        const refuse = mergeSim[m];                 // m → m-1 birleşmesi (yapmadığımız)
        const did = mergeSim[m + 1] ?? 1;           // m+1 → m birleşmesi (yaptığımız)
        if (refuse >= T) continue;                  // bu iki grup yeterince benzer: ayrı kişi sayma
        const gap = did - refuse;
        if (gap > bestGap) { bestGap = gap; K = m; }
      }
    }
    let groups = snaps[K] || snap();
    // çok küçük grupları (tek cümle ya da toplam sürenin %1,5'inden az) en yakın büyük gruba kat:
    // gürültülü tek tük cümleler ayrı "konuşmacı" olmasın
    if (!k) {
      const total = groups.reduce((t, g) => t + g.w, 0);
      const big = groups.filter((g) => g.idx.length >= 2 && g.w >= Math.max(3, total * 0.015));
      if (!big.length && groups.length > 1) {
        // hiçbir grup yeterince büyük değil (az ve gürültülü cümle): şimdilik tek kişi say
        const all = groups[0]; for (const g of groups.slice(1)) all.idx = all.idx.concat(g.idx);
        groups = [all];
      } else if (big.length) {
        for (const g of groups.filter((g) => !big.includes(g))) {
          let best = big[0], bs = -2;
          for (const h of big) { const sc = Speakers.cos(g.v, h.v); if (sc > bs) { bs = sc; best = h; } }
          best.idx = best.idx.concat(g.idx);
        }
        groups = big;
      }
    }
    const lab = new Array(n).fill(0);
    groups.forEach((g, gi) => g.idx.forEach((i) => (lab[i] = gi)));
    const order = new Map();
    const labels = lab.map((l) => { if (!order.has(l)) order.set(l, order.size + 1); return order.get(l); });
    return { labels, centroids: [...order.keys()].map((l) => groups[l].v) };
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

  const api = { SR, Segmenter, Resampler, Speakers, recluster, cleanText };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.TK = api;
})(typeof self !== 'undefined' ? self : this);
