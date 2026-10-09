(() => {
  const send = body => window.webkit.messageHandlers.lilacMedia.postMessage(body);
  const seen = new Set(), pending = new Set(), loaded = new Set();
  const absolute = value => { try { return new URL(value, location.href).href; } catch { return ''; } };
  const report = (value, kind, manifest = '') => {
    const url = absolute(value);
    if (!/^https?:\/\//.test(url) || seen.has(url)) return;
    seen.add(url); send({url, kind, referer: '', manifest});
  };
  const playlist = (text, base) => {
    if (!String(text).trimStart().startsWith('#EXTM3U')) return false;
    loaded.add(base);
    const lines = String(text).split(/\r?\n/).map(x => x.trim()), variants = [];
    for (let i = 0; i < lines.length; i++) {
      if (!lines[i].startsWith('#EXT-X-STREAM-INF:')) continue;
      const next = lines.slice(i + 1).find(x => x && !x.startsWith('#'));
      if (next) variants.push({url: new URL(next, base).href, bandwidth: Number((lines[i].match(/(?:^|[:,])BANDWIDTH=(\d+)/) || [])[1]) || 0});
    }
    if (variants.length) variants.sort((a,b) => b.bandwidth-a.bandwidth).forEach(x => requestPlaylist(x.url, true));
    // The /m3/ token endpoint also needs the player's session. Keep the validated
    // media playlist so native playback/downloads do not have to request it again.
    else if (String(text).length <= 4_000_000 && lines.reduce((sum,line) => sum + (line.startsWith('#EXTINF:') ? Number(line.slice(8).split(',')[0]) || 0 : 0), 0) > 30) report(base, 'ohliVariant', String(text));
    return true;
  };
  const isPlaylist = url => /\/master\.txt(?:[?#]|$)|\.m3u8(?:[?#]|$)|\/m3\/[^/?#]+/i.test(String(url));
  const originalFetch = window.fetch;
  window.fetch = function(input, init) {
    const url = absolute(typeof input === 'string' || input instanceof URL ? String(input) : input.url);
    const response = originalFetch.apply(this, arguments);
    if (isPlaylist(url)) response.then(r => r.clone().text().then(text => { if (playlist(text, r.url || url)) loaded.add(url); })).catch(() => {});
    return response;
  };
  const originalOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(method, value) {
    const url = absolute(value);
    if (isPlaylist(url)) this.addEventListener('load', () => { try {
      const text = this.responseType === 'arraybuffer' ? new TextDecoder().decode(this.response) : this.responseText;
      if (playlist(text, this.responseURL || url)) loaded.add(url);
    } catch {} });
    return originalOpen.apply(this, arguments);
  };
  const requestPlaylist = (value, force = false) => {
    const url = absolute(value);
    if ((!force && !isPlaylist(url)) || seen.has(url) || pending.has(url) || loaded.has(url)) return;
    pending.add(url);
    // master.txt needs the player's cookie and its no-referrer policy. Read it in this WK session.
    originalFetch.call(window, url, {credentials: 'include', referrerPolicy: 'no-referrer'})
      .then(r => r.text().then(text => { if (playlist(text, r.url || url)) loaded.add(url); }))
      .catch(() => {}).finally(() => pending.delete(url));
  };
  // Safari can request HLS through its native media stack instead of fetch/XHR.
  // Read the observed master in the same cookie-bearing page session as well.
  try {
    new PerformanceObserver(list => list.getEntries().forEach(entry => requestPlaylist(entry.name))).observe({entryTypes:['resource']});
    performance.getEntriesByType('resource').forEach(entry => requestPlaylist(entry.name));
  } catch {}
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
