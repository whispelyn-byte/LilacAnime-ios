(() => {
  if (!location.hostname.includes('flixcloud') || window.__lilacFlixDiscovery) return;
  const post = value => window.webkit.messageHandlers.lilacMedia.postMessage({url:location.href,...value});
  const seen = new Set();
  let timer, stopped = false, lastKey = '';
  const key = () => {
    if (typeof window.__pk === 'string' && window.__pk) return window.__pk;
    try { return JSON.parse(window.__lilacReAnimeFlixResult || '{}').pk || ''; } catch (_) { return ''; }
  };
  const emit = url => {
    try {
      url = new URL(url,location.href).href;
      if (seen.has(url) || !/\.(m3u8|mp4|webm)(?:\?|$)/i.test(url) || /runative|magsrv|juneworewyjyna|pxltag/i.test(url)) return;
      seen.add(url); post({url,kind:'video',referer:location.href,pk:key()});
    } catch (_) {}
  };
  const stop = () => {
    stopped = true; clearInterval(timer);
    document.querySelectorAll('video,audio').forEach(media=>media.pause());
    document.querySelectorAll('iframe').forEach(frame=>frame.contentWindow?.postMessage({lilacStopDiscovery:true},'*'));
  };
  const scan = () => {
    if (stopped) return;
    const text = (document.title+' '+(document.body?.innerText||'')).toLowerCase();
    if (text.includes('you have been blocked')) {
      post({kind:'flixError',error:'FlixCloud가 이 앱 세션을 차단했습니다. 다른 영상 서버로 전환합니다.'});
      stop(); return;
    }
    const pk = key();
    if (pk && pk !== lastKey) { lastKey = pk; post({kind:'flixKey',pk}); }
    document.querySelectorAll('video').forEach(v=>{v.muted=true;v.play().catch(()=>{})});
    const els=[...document.querySelectorAll('button,[role=button],.play,.vjs-big-play-button,.jw-display-icon-container,.jw-icon-display,.jwplayer')];
    const play=els.find(e=>/play|재생|watch|jw-display|jw-icon-display/i.test(e.innerText||e.getAttribute('aria-label')||e.className||''));
    if (play && !play.dataset.lilacClicked) { play.dataset.lilacClicked='1';play.click(); }
    try {
      const api=window.jwplayer?.(),item=api?.getPlaylistItem?.();
      [item?.file,...(item?.sources||[]).map(s=>s.file)].filter(Boolean).forEach(emit);
      api?.play?.();
    } catch (_) {}
    performance.getEntriesByType('resource').forEach(e=>emit(e.name));
  };
  window.addEventListener('message',event=>{ if(event.data?.lilacStopDiscovery) stop(); });
  window.__lilacFlixDiscovery={
    get stopped(){return stopped;},
    finish(){const tracks=parseFlixSubtitleTracks(document.documentElement.innerHTML||'');stop();return tracks;},
    scan
  };
  timer=setInterval(scan,350);scan();
function decodeJsString(value=''){if(!value)return '';try{return JSON.parse(`"${value}"`).trim()}catch{return value.replace(/\\"/g,'"').replace(/\\\\/g,'\\').replace(/\\\//g,'/').trim()}}
function parseFlixSubtitleTracks(html=''){
  // Track names can contain brackets ("English [nedragrevev]"), so find the closing ']' outside strings.
  const text=String(html),start=text.search(/subtitles:\[/);if(start<0)return [];
  let end=-1,depth=0,quote='';
  for(let i=text.indexOf('[',start);i<text.length;i++){
    const c=text[i];
    if(quote){if(c==='\\')i++;else if(c===quote)quote='';continue}
    if(c==='"'||c==="'")quote=c;else if(c==='[')depth++;else if(c===']'&&--depth===0){end=i;break}
  }
  const block=end>0?text.slice(text.indexOf('[',start)+1,end):'';if(!block)return [];
  const tracks=[];
  for(const match of block.matchAll(/\{([^{}]*?url:"[^"]+"[^{}]*?)\}/g)){
    const fields={};for(const field of match[1].matchAll(/(?:^|,)\s*(url|language|label|format):"((?:\\.|[^"])*)"/g))fields[field[1]]=decodeJsString(field[2]);
    const url=String(fields.url||'').replace(/\u0026/g,'&');if(!/^https?:/i.test(url))continue;
    const language=fields.language||'und';
    tracks.push({url,language,label:fields.label||language,format:(fields.format||(/\.srt(?:$|\?)/i.test(url)?'srt':/\.ass(?:$|\?)/i.test(url)?'ass':'vtt')).toLowerCase()});
  }
  return tracks.filter((track,index,array)=>array.findIndex(x=>x.url===track.url)===index);
}
})();
