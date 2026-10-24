const { test } = require('node:test');
const assert = require('node:assert/strict');
const { WatermarkLearner, parseMaskShader } = require('../LilacAnime/Resources/watermark-learner.js');

// Scenes of random colour blocks with a fixed translucent white logo (alpha 0.35 in a 40 x 16 box at 300,20), some of
// them dark: the learned mask finds the box and its alpha.
function frame(w, h, seed, dark) {
  let s = seed; const rnd = () => (s = (s * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff;
  const rgba = new Uint8ClampedArray(w * h * 4);
  for (let by = 0; by < h; by += 8) for (let bx = 0; bx < w; bx += 8) {
    const c = [0, 1, 2].map(() => Math.floor(rnd() * (dark ? 30 : 255)));
    for (let y = by; y < Math.min(h, by + 8); y++) for (let x = bx; x < Math.min(w, bx + 8); x++) { const k = (y * w + x) * 4; rgba[k] = c[0]; rgba[k + 1] = c[1]; rgba[k + 2] = c[2]; rgba[k + 3] = 255; }
  }
  for (let y = 20; y < 36; y++) for (let x = 300; x < 340; x++) { const k = (y * w + x) * 4; for (let c = 0; c < 3; c++) rgba[k + c] = Math.round(rgba[k + c] * 0.65 + 255 * 0.35); }
  return rgba;
}
test('the logo box and its alpha are learned from the scenes', () => {
  const w = 384, h = 216, learner = new WatermarkLearner();
  for (let i = 0; i < 40; i++) learner.addFrame(frame(w, h, i + 1, i % 3 === 0), w, h);
  assert.ok(learner.acceptedFrames >= 30); assert.ok(learner.darkFrames >= 10);
  const mask = learner.finish();
  assert.ok(mask, learner.failureReason);
  assert.ok(Math.abs(mask.maxAlpha - 0.35) < 0.05, `alpha ${mask.maxAlpha}`);
  const [ox, oy] = mask.origin, [ew, eh] = mask.extent;
  // Box (with its 6 px of padding) around x 300..340, y 20..36.
  assert.ok(Math.abs(ox * w - 294) <= 2 && Math.abs(oy * h - 14) <= 2, `origin ${ox * w},${oy * h}`);
  assert.ok(Math.abs(ew * w - 52) <= 3 && Math.abs(eh * h - 28) <= 3, `extent ${ew * w},${eh * h}`);
  assert.ok(mask.expectedError < 0.05);
});
test('a still picture is not counted twice and too few scenes give a reason', () => {
  const learner = new WatermarkLearner(), still = frame(96, 54, 7, false);
  for (let i = 0; i < 5; i++) learner.addFrame(still, 96, 54);
  assert.equal(learner.acceptedFrames, 1);
  assert.equal(learner.finish(), null); assert.match(learner.failureReason, /부족/);
});
test('an mpv mask shader from the PC tool or the Android app is read', () => {
  const text = '//!HOOK MAIN\n//!BIND HOOKED\n//!BIND wm\nconst vec2 WM_ORIGIN = vec2(0.75000000, 0.05000000);\nconst vec2 WM_EXTENT = vec2(0.20000000, 0.10000000);\nvec4 hook() { return HOOKED_tex(HOOKED_pos); }\n\n//!TEXTURE wm\n//!SIZE 2 1\n//!FORMAT rgba8\n//!FILTER LINEAR\n//!BORDER CLAMP\n00000000\n80808080\n';
  const mask = parseMaskShader(text);
  assert.deepEqual([mask.width, mask.height, mask.origin, mask.extent], [2, 1, [0.75, 0.05], [0.2, 0.1]]);
  assert.deepEqual([...mask.rgba], [0, 0, 0, 0, 128, 128, 128, 128]);
  assert.throws(() => parseMaskShader('nothing'), /형식/);
});
