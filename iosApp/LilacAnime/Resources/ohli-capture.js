(() => {
  const send = body => window.webkit.messageHandlers.lilacMedia.postMessage(body);
  const seen = new Set(), pending = new Set();
  const absolute = value => { try { return new URL(value, location.href).href; } catch { return ''; } };
  const report = (value, kind) => {
    const url = absolute(value);
    if (!/^https?:\/\//.test(url) || seen.has(url)) return;
    seen.add(url); send({url, kind, referer: ''});
  };
  const playlist = (text, base) => {
    if (!String(text).trimStart().startsWith('#EXTM3U')) return;
    const lines = String(text).split(/\r?\n/).map(x => x.trim()), variants = [];
    for (let i = 0; i < lines.length; i++) {
      if (!lines[i].startsWith('#EXT-X-STREAM-INF:')) continue;
      const next = lines.slice(i + 1).find(x => x && !x.startsWith('#'));
      if (next) variants.push({url: new URL(next, base).href, bandwidth: Number((lines[i].match(/(?:^|[:,])BANDWIDTH=(\d+)/) || [])[1]) || 0});
    }
    if (variants.length) variants.sort((a,b) => b.bandwidth-a.bandwidth).forEach(x => report(x.url, 'ohliVariant'));
    else if (lines.reduce((sum,line) => sum + (line.startsWith('#EXTINF:') ? Number(line.slice(8).split(',')[0]) || 0 : 0), 0) > 30) report(base, 'ohliVariant');
  };
  const isPlaylist = url => /\/(?:master\.txt)(?:[?#]|$)|\.m3u8(?:[?#]|$)/i.test(String(url));
  const originalFetch = window.fetch;
  window.fetch = function(input, init) {
    const url = absolute(typeof input === 'string' || input instanceof URL ? String(input) : input.url);
    const response = originalFetch.apply(this, arguments);
    if (isPlaylist(url)) response.then(r => r.clone().text().then(text => playlist(text, r.url || url))).catch(() => {});
    return response;
  };
  const originalOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(method, value) {
    const url = absolute(value);
    if (isPlaylist(url)) this.addEventListener('load', () => { try {
      const text = this.responseType === 'arraybuffer' ? new TextDecoder().decode(this.response) : this.responseText;
      playlist(text, this.responseURL || url);
    } catch {} });
    return originalOpen.apply(this, arguments);
  };
  const requestPlaylist = value => {
    const url = absolute(value);
    if (!isPlaylist(url) || seen.has(url) || pending.has(url)) return;
    pending.add(url);
    // master.txt needs the player's cookie and its no-referrer policy. Read it in this WK session.
    window.fetch(url, {credentials: 'include', referrerPolicy: 'no-referrer'}).catch(() => {}).finally(() => pending.delete(url));
  };
  const scan = () => {
    const player = document.querySelector('iframe#video') || document.querySelector('iframe[src*="cdndania"]');
    if (player) report(player.src, 'ohliPlayer');
    try {
      const jw = window.jwplayer?.(), item = jw?.getPlaylistItem?.();
      for (const source of [item?.file, ...(item?.sources || []).map(x => x.file)]) {
        requestPlaylist(source);
        if (/\.mp4(?:[?#]|$)/i.test(String(source)) && Number(jw.getDuration?.()) > 30) report(source, 'ohliMedia');
      }
      jw?.setMute?.(true); jw?.play?.();
    } catch {}
    document.querySelectorAll('video').forEach(video => {
      requestPlaylist(video.currentSrc || video.src);
      video.muted = true; try { video.play()?.catch(() => {}); } catch {}
    });
    const start = document.querySelector('.play-button-outer');
    if (start && start.style.display !== 'none' && !start.dataset.lilacStarted) { start.dataset.lilacStarted = '1'; start.click(); }
  };
  document.addEventListener('DOMContentLoaded', scan);
  setInterval(scan, 1000);
})();
