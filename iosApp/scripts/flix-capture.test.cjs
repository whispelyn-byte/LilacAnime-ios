const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const script = fs.readFileSync(path.join(__dirname, '../LilacAnime/Resources/flix-discovery.js'), 'utf8');
function page(blocked = false, {title = 'Player', parent} = {}) {
  const messages=[], media=['https://cdn.test/audio.m3u8'];
  let requests=0, plays=0, pauses=0, clicks=0;
  const video={muted:false,play(){plays++;return Promise.resolve();},pause(){pauses++;}};
  const button={innerText:'Play',dataset:{},click(){clicks++;}};
  const document={title,body:{innerText:blocked?'Sorry, you have been blocked':''},
    documentElement:{innerHTML:'<script>subtitles:[{url:"https://cdn.test/ko.ass?x=1\\u0026y=2",language:"ko",label:"Korean [team]",format:"ass"},{url:"https://cdn.test/en.vtt",language:"en",label:"English"}]</script>'},
    querySelectorAll(selector){return selector==='video'||selector==='video,audio'?[video]:selector.startsWith('button')?[button]:[];}};
  const window={webkit:{messageHandlers:{lilacMedia:{postMessage:m=>messages.push(m)}}},
    fetch(){requests++;throw new Error('Discovery must not fetch media');},addEventListener(){},
    jwplayer:()=>({getPlaylistItem:()=>({file:media[0]}),play(){}})};
  if (parent) window.parent=parent;
  vm.runInNewContext(script,{window,document,URL,location:{hostname:'flixcloud.cc',href:'https://flixcloud.cc/e/test'},
    performance:{getEntriesByType:()=>media.map(name=>({name}))},setInterval(){return 1;},clearInterval(){}});
  return {window,messages,media,get counts(){return {requests,plays,pauses,clicks};}};
}
const fixture=page(), discovery=fixture.window.__lilacFlixDiscovery;
assert.equal(fixture.messages.filter(m=>m.kind==='video').length,1);
fixture.window.__pk='late-key'; discovery.scan();
assert.equal(fixture.messages.find(m=>m.kind==='flixKey').pk,'late-key');
fixture.media.push('https://cdn.test/video.m3u8');discovery.scan();
assert.equal(fixture.messages.filter(m=>m.kind==='video').length,2);
assert.equal(fixture.messages.filter(m=>m.kind==='flixKey').length,1);
assert.equal(fixture.counts.clicks,1);
const tracks=discovery.finish();
assert.equal(tracks.length,2);assert.equal(tracks[0].label,'Korean [team]');
assert.equal(tracks[0].url,'https://cdn.test/ko.ass?x=1&y=2');
assert.equal(tracks[0].format,'ass');
const plays=fixture.counts.plays;discovery.scan();
assert.equal(fixture.counts.plays,plays);assert.equal(fixture.counts.pauses,1);
assert.equal(fixture.counts.requests,0);
const blocked=page(true);
assert.equal(blocked.messages[0].kind,'flixError');
assert.equal(blocked.counts.plays,0);assert.equal(blocked.window.__lilacFlixDiscovery.stopped,true);
console.log('Flix discovery: late keys, separate tracks, play button, embedded subtitles, blocked page and stopped hidden playback passed.');

const adFixture=page();
adFixture.media.push('https://cdn.test/content.m3u8?token=a87cad42', 'https://cdn.test/ads/video.m3u8');
adFixture.window.__lilacFlixDiscovery.scan();
assert.ok(adFixture.messages.some(m=>m.url==='https://cdn.test/content.m3u8?token=a87cad42'));
assert.ok(!adFixture.messages.some(m=>m.url==='https://cdn.test/ads/video.m3u8'));
// Framed (FlixEmbed), the key, the bootstrap's result and the embedded tracks are passed up to the top page.
const posted=[];
const framed=page(false,{parent:{postMessage:(m,origin)=>posted.push([m,origin])}});
framed.window.__pk='frame-key';framed.window.__lilacReAnimeFlixResult='{"ok":true}';framed.window.__lilacFlixDiscovery.scan();
const state=posted.at(-1)[0].lilacFlixState;
assert.equal(posted.at(-1)[1],'*');assert.equal(state.pk,'frame-key');assert.equal(state.result,'{"ok":true}');
assert.equal(state.tracks.length,2);assert.equal(state.tracks[0].label,'Korean [team]');assert.equal(state.stopped,false);
framed.window.__lilacFlixDiscovery.finish();assert.equal(posted.at(-1)[0].lilacFlixState.stopped,true);
// A file taken down ("404: File not found") is reported at once, so the next server is tried.
const missing=page(false,{title:'404: File not found'});
assert.equal(missing.messages[0].kind,'flixError');assert.match(missing.messages[0].error,/찾지 못했습니다/);
assert.equal(missing.window.__lilacFlixDiscovery.stopped,true);
console.log('Flix discovery in a frame: state passed up to the top page, a missing file reported at once.');
