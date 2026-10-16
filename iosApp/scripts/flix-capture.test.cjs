const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const swift = fs.readFileSync(path.join(__dirname, '../LilacAnime/PlaybackResolver.swift'), 'utf8');
const script = swift.split('private static let captureScript = """')[1].split('"""')[0].replaceAll('\\\\', '\\');
const messages = [];
let scan, requests = 0;
const media = ['https://cdn.test/audio.m3u8'];
const window = {
  webkit: { messageHandlers: { lilacMedia: { postMessage: message => messages.push(message) } } },
  fetch: () => { requests++; throw new Error('Discovery must not fetch media'); },
  jwplayer: () => ({ getPlaylistItem: () => ({ file: media[0] }), play() {} }),
};
function XMLHttpRequest() {}
XMLHttpRequest.prototype.open = () => {};
vm.runInNewContext(script, {
  window, URL, XMLHttpRequest,
  location: { hostname: 'flixcloud.cc', href: 'https://flixcloud.cc/e/test' },
  document: { querySelectorAll: () => [], addEventListener() {} },
  performance: { getEntriesByType: () => media.map(name => ({ name })) },
  PerformanceObserver: class { observe() {} },
  setInterval: callback => { scan = callback; },
});
scan();
assert.equal(messages.filter(message => message.kind === 'video').length, 1);
assert.equal(messages.filter(message => message.kind === 'flixKey').length, 0);
// Repeated URLs remain deduplicated; a late key still gets its own message.
window.__pk = 'late-key';
scan();
assert.equal(messages.filter(message => message.kind === 'video').length, 1);
assert.equal(messages.find(message => message.kind === 'flixKey').pk, 'late-key');
media.push('https://cdn.test/video.m3u8');
scan();
assert.equal(messages.filter(message => message.kind === 'video').length, 2);
assert.equal(messages.filter(message => message.kind === 'flixKey').length, 1);
assert.equal(requests, 0);
console.log('Flix capture: separate track discovery, late key delivery, URL deduplication and no media prefetch passed.');
