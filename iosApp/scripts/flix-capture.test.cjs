const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const script = fs.readFileSync(path.join(__dirname, '../LilacAnime/Resources/flix-discovery.js'), 'utf8');
function page(blocked = false) {
  const messages=[], media=['https://cdn.test/audio.m3u8'];
  let requests=0, plays=0, pauses=0, clicks=0;
  const video={muted:false,play(){plays++;return Promise.resolve();},pause(){pauses++;}};
  const button={innerText:'Play',dataset:{},click(){clicks++;}};
  const document={title:'Player',body:{innerText:blocked?'Sorry, you have been blocked':''},
    documentElement:{innerHTML:'<script>subtitles:[{url:"https://cdn.test/ko.ass?x=1\\u0026y=2",language:"ko",label:"Korean [team]",format:"ass"},{url:"https://cdn.test/en.vtt",language:"en",label:"English"}]</script>'},
    querySelectorAll(selector){return selector==='video'||selector==='video,audio'?[video]:selector.startsWith('button')?[button]:[];}};
  const window={webkit:{messageHandlers:{lilacMedia:{postMessage:m=>messages.push(m)}}},
    fetch(){requests++;throw new Error('Discovery must not fetch media');},addEventListener(){},
    jwplayer:()=>({getPlaylistItem:()=>({file:media[0]}),play(){}})};
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
