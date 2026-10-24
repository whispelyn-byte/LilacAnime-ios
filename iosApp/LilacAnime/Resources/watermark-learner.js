// Android WatermarkMaskLearner: the alpha map of a fixed translucent white watermark, learned from the episode itself.
// On screen = (1 - a) * picture + a * white, so the darkest value a pixel takes over many scenes is close to a (where
// the picture behind it is near black). Only one running minimum per pixel and channel is kept: the memory of one
// frame. Same formula as the PC tool estimate_wm.py, whose .glsl files can be read too (parseMaskShader).
(function (root) {
  const THUMB_W = 48, THUMB_H = 27;
  // A 48 x 27 grey thumbnail (nearest pixels; enough to tell scenes apart and how dark the edges are).
  function thumbnail(rgba, w, h) {
    const out = new Int32Array(THUMB_W * THUMB_H);
    for (let ty = 0; ty < THUMB_H; ty++) for (let tx = 0; tx < THUMB_W; tx++) {
      const x = Math.min(w - 1, Math.floor((tx + 0.5) * w / THUMB_W)), y = Math.min(h - 1, Math.floor((ty + 0.5) * h / THUMB_H)), k = (y * w + x) * 4;
      out[ty * THUMB_W + tx] = (rgba[k] * 3 + rgba[k + 1] * 6 + rgba[k + 2]) / 10 | 0;
    }
    return out;
  }
  // The mean brightness of the thumbnail's outer three cells: the edges, where the logo is not.
  function ringLuma(t) {
    let sum = 0, n = 0;
    for (let y = 0; y < THUMB_H; y++) for (let x = 0; x < THUMB_W; x++) if (y < 3 || y >= THUMB_H - 3 || x < 3 || x >= THUMB_W - 3) { sum += t[y * THUMB_W + x]; n++; }
    return n ? sum / n : 255;
  }
  function meanAbsDiff(a, b) { let s = 0; for (let i = 0; i < a.length; i++) s += Math.abs(a[i] - b[i]); return s / a.length; }
  function percentile(hist, total, q) { if (total <= 0) return 0; const target = Math.floor(total * q); let sum = 0; for (let i = 0; i < hist.length; i++) { sum += hist[i]; if (sum > target) return i; } return 255; }

  class WatermarkLearner {
    constructor() { this.w = 0; this.h = 0; this.low = null; this.prevThumb = null; this.acceptedFrames = 0; this.darkFrames = 0; this.lastRingLuma = 255; this.failureReason = null; }
    // A frame (RGBA, as ImageData has it) is taken when it differs from the last one. True when taken.
    addFrame(rgba, w, h) {
      if (!this.low) { this.w = w; this.h = h; this.low = new Uint8Array(w * h * 3).fill(255); }
      else if (w !== this.w || h !== this.h) return false;
      const thumb = thumbnail(rgba, w, h);
      this.lastRingLuma = ringLuma(thumb);
      if (this.lastRingLuma < 45) this.darkFrames++;
      // Only frames that are almost the same (a still picture) are left out: dark scenes look alike but their dark
      // places differ, which is what is learned, so the bar is low.
      if (this.prevThumb && meanAbsDiff(this.prevThumb, thumb) < 1.5) return false;
      this.prevThumb = thumb;
      const lo = this.low;
      for (let i = 0, j = 0; i < w * h * 4; i += 4, j += 3) {
        if (rgba[i] < lo[j]) lo[j] = rgba[i];
        if (rgba[i + 1] < lo[j + 1]) lo[j + 1] = rgba[i + 1];
        if (rgba[i + 2] < lo[j + 2]) lo[j + 2] = rgba[i + 2];
      }
      this.acceptedFrames++;
      return true;
    }
    // The black level b0 of the edges (no logo there) and how scattered their minimums still are: the expected error.
    ringStats(refMargin) {
      const { w, h, low: lo } = this, m = Math.max(2, Math.floor(Math.min(w, h) * refMargin));
      const hist = [new Int32Array(256), new Int32Array(256), new Int32Array(256)], lum = new Int32Array(256); let count = 0;
      const acc = (x, y) => { const k = (y * w + x) * 3, r = lo[k], g = lo[k + 1], b = lo[k + 2]; hist[0][r]++; hist[1][g]++; hist[2][b]++; lum[(r + g + b) / 3 | 0]++; count++; };
      for (let y = 0; y < h; y++) {
        if (y < m || y >= h - m) for (let x = 0; x < w; x++) acc(x, y);
        else { for (let x = 0; x < m; x++) acc(x, y); for (let x = w - m; x < w; x++) acc(x, y); }
      }
      const b0 = [0, 1, 2].map(c => percentile(hist[c], count, 0.5)), p50 = percentile(lum, count, 0.5), p90 = percentile(lum, count, 0.9), mean = (b0[0] + b0[1] + b0[2]) / 3;
      return { b0, expectedError: Math.min(1, Math.max(0, (p90 - p50) / Math.max(255 - mean, 1))) };
    }
    quickExpectedError() { return !this.low || this.acceptedFrames < 3 ? 1 : this.ringStats(0.06).expectedError; }
    // The mask: RGBA (premultiplied white: all four values are alpha) over the logo's box, normalized to the frame.
    finish(gate = 0.02, pad = 6, refMargin = 0.06) {
      const { w, h, low: lo } = this;
      if (!lo) { this.failureReason = '캡처된 프레임이 없습니다.'; return null; }
      if (this.acceptedFrames < 6) { this.failureReason = `분석할 수 있는 장면이 부족합니다 (${this.acceptedFrames}개). 영상이 불러와졌는지 확인해 주세요.`; return null; }
      const { b0, expectedError } = this.ringStats(refMargin), den = b0.map(v => Math.max(255 - v, 1));
      const alpha = new Float32Array(w * h); let minX = w, minY = h, maxX = -1, maxY = -1, maxAlpha = 0;
      for (let y = 0, k = 0; y < h; y++) for (let x = 0; x < w; x++, k += 3) {
        let a = ((lo[k] - b0[0]) / den[0] + (lo[k + 1] - b0[1]) / den[1] + (lo[k + 2] - b0[2]) / den[2]) / 3;
        a = Math.min(0.97, Math.max(0, a)); if (a < gate) a = 0;
        alpha[y * w + x] = a;
        if (a > gate * 2) { if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y; if (a > maxAlpha) maxAlpha = a; }
      }
      // Under 0.08 it is the scenes' own darkness not reached yet, not a logo (Animenosub's logo, shown now and then, left
      // 0.06 spread over the middle of the picture): no mask rather than one that dims the picture.
      if (maxX < 0 || maxAlpha < 0.08) { this.failureReason = `늘 떠 있는 워터마크를 찾지 못했습니다 (장면 ${this.acceptedFrames}개, 최대 투명도 ${maxAlpha.toFixed(3)}). 로고가 가끔만 나오거나 아주 옅거나 흰색이 아니면 학습할 수 없어요. 영역 직접 지정을 써 주세요.`; return null; }
      const x0 = Math.max(0, minX - pad), y0 = Math.max(0, minY - pad), x1 = Math.min(w, maxX + 1 + pad), y1 = Math.min(h, maxY + 1 + pad);
      // A big box is shrunk by a whole factor to keep the texture small.
      let f = 1; while ((Math.floor((x1 - x0) / f) + 1) * (Math.floor((y1 - y0) / f) + 1) > 700000) f++;
      const tw = Math.ceil((x1 - x0) / f), th = Math.ceil((y1 - y0) / f), rgba = new Uint8Array(tw * th * 4);
      for (let ty = 0, o = 0; ty < th; ty++) for (let tx = 0; tx < tw; tx++, o += 4) {
        let sum = 0;
        for (let dy = 0; dy < f; dy++) { const yy = y0 + ty * f + dy; if (yy >= h) continue; for (let dx = 0; dx < f; dx++) { const xx = x0 + tx * f + dx; if (xx < w) sum += alpha[yy * w + xx]; } }
        rgba[o] = rgba[o + 1] = rgba[o + 2] = rgba[o + 3] = Math.max(0, Math.min(255, Math.round(sum / (f * f) * 255)));
      }
      const ex1 = x0 + tw * f, ey1 = y0 + th * f;
      return { rgba, width: tw, height: th, origin: [x0 / w, y0 / h], extent: [(ex1 - x0) / w, (ey1 - y0) / h], maxAlpha, expectedError, frames: this.acceptedFrames, darkFrames: this.darkFrames };
    }
  }

  // A mask made on a PC (estimate_wm.py) or by the Android app: an mpv user shader whose WM_ORIGIN / WM_EXTENT place
  // the texture "//!TEXTURE wm" (//!SIZE w h, one line of hex RGBA) on the frame.
  function parseMaskShader(text) {
    const vec = name => { const m = text.match(new RegExp(`${name}\\s*=\\s*vec2\\(\\s*([-\\d.eE+]+)\\s*,\\s*([-\\d.eE+]+)\\s*\\)`)); return m ? [Number(m[1]), Number(m[2])] : null; };
    const origin = vec('WM_ORIGIN'), extent = vec('WM_EXTENT'), at = text.indexOf('//!TEXTURE wm');
    if (!origin || !extent || at < 0) throw new Error('워터마크 마스크 파일(.glsl) 형식이 아닙니다.');
    const head = text.slice(at), size = head.match(/\/\/!SIZE\s+(\d+)\s+(\d+)/);
    if (!size) throw new Error('마스크 크기(//!SIZE)를 찾지 못했습니다.');
    const width = Number(size[1]), height = Number(size[2]);
    // The texture body: everything after the //! header lines, white space removed.
    const lines = head.split(/\r?\n/); let i = 0; while (i < lines.length && lines[i].startsWith('//!')) i++;
    const hex = lines.slice(i).join('').replace(/\s+/g, '');
    if (hex.length < width * height * 8) throw new Error('마스크 데이터가 잘려 있습니다.');
    const rgba = new Uint8Array(width * height * 4);
    for (let k = 0; k < rgba.length; k++) rgba[k] = parseInt(hex.substr(k * 2, 2), 16);
    return { rgba, width, height, origin, extent };
  }

  root.LilacWatermark = { WatermarkLearner, parseMaskShader, thumbnail, ringLuma };
  if (typeof module === 'object' && module.exports) module.exports = root.LilacWatermark;
})(typeof window === 'undefined' ? globalThis : window);
