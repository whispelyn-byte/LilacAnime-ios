(() => {
  const send = body => window.webkit.messageHandlers.lilacMedia.postMessage(body);
  const seen = new Set(), pending = new Map(), loaded = new Set(), variantURLs = new Set();
  let selectedVariant = '';
  let complete = false;
  const absolute = value => { try { return new URL(value, location.href).href; } catch { return ''; } };
  const report = (value, kind, manifest = '') => {
    const url = absolute(value);
    if (!/^https?:\/\//.test(url)) return false;
    if (!seen.has(url)) { seen.add(url); send({url, kind, referer: '', manifest}); }
    if (kind === 'ohliVariant') complete = true;
    return true;
  };
  const playlist = (text, base, requested = base) => {
    if (!String(text).trimStart().startsWith('#EXTM3U')) return false;
    loaded.add(base);
    const lines = String(text).split(/\r?\n/).map(x => x.trim()), variants = [];
    for (let i = 0; i < lines.length; i++) {
      if (!lines[i].startsWith('#EXT-X-STREAM-INF:')) continue;
      const next = lines.slice(i + 1).find(x => x && !x.startsWith('#'));
      if (next) variants.push({url: new URL(next, base).href, bandwidth: Number((lines[i].match(/(?:^|[:,])BANDWIDTH=(\d+)/) || [])[1]) || 0});
    }
    if (variants.length) {
      variants.sort((a,b) => b.bandwidth-a.bandwidth).forEach(x => variantURLs.add(x.url));
      // Passive player requests can finish a lower rendition first. Only publish
      // the rendition currently being checked, then fall back on request failure.
      (async () => {
        for (const candidate of variants) {
          selectedVariant = candidate.url;
          if (await requestPlaylist(candidate.url, true)) return;
        }
        selectedVariant = '';
      })();
      return true;
    }
    // The /m3/ token endpoint also needs the player's session. Keep the validated
    // media playlist so native playback/downloads do not have to request it again.
    if (variantURLs.has(requested) && requested !== selectedVariant) return false;
    if (String(text).length <= 4_000_000 && lines.reduce((sum,line) => sum + (line.startsWith('#EXTINF:') ? Number(line.slice(8).split(',')[0]) || 0 : 0), 0) > 30) return report(base, 'ohliVariant', String(text));
    return false;
  };
  const isPlaylist = url => /\/master\.txt(?:[?#]|$)|\.m3u8(?:[?#]|$)|\/m3\/[^/?#]+/i.test(String(url));
  const isConfig = url => /\/player\/index\.php\?.*\bdo=getVideo\b/i.test(String(url));
  const inspect = (text, base, requested) => {
    if (isConfig(requested)) {
      try {
        const config = JSON.parse(text);
        if (config.hls && typeof config.videoSource === 'string') requestPlaylist(config.videoSource);
      } catch {}
      return false;
    }
    return playlist(text, base, requested);
  };
  const originalFetch = window.fetch;
  window.fetch = function(input, init) {
    const url = absolute(typeof input === 'string' || input instanceof URL ? String(input) : input.url);
    const response = originalFetch.apply(this, arguments);
    if (isPlaylist(url) || isConfig(url)) response.then(r => r.clone().text().then(text => { if (inspect(text, r.url || url, url)) loaded.add(url); })).catch(() => {});
    return response;
  };
  const originalOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(method, value) {
    const url = absolute(value);
    if (isPlaylist(url) || isConfig(url)) this.addEventListener('load', () => { try {
      const text = this.responseType === 'arraybuffer' ? new TextDecoder().decode(this.response) : this.responseText;
      if (inspect(text, this.responseURL || url, url)) loaded.add(url);
    } catch {} });
    return originalOpen.apply(this, arguments);
  };
  const requestPlaylist = (value, force = false) => {
    const url = absolute(value);
    if (seen.has(url)) return Promise.resolve(true);
    if ((!force && !isPlaylist(url)) || (!force && loaded.has(url))) return Promise.resolve(false);
    if (pending.has(url)) return pending.get(url);
    // master.txt needs the player's cookie and its no-referrer policy. Read it in this WK session.
    const task = originalFetch.call(window, url, {credentials: 'include', referrerPolicy: 'no-referrer'})
      .then(r => r.text().then(text => { const accepted = playlist(text, r.url || url, url); if (accepted) loaded.add(url); return accepted; }))
      .catch(() => false).finally(() => pending.delete(url));
    pending.set(url, task); return task;
  };
  // Safari can request HLS through its native media stack instead of fetch/XHR.
  // Read the observed master in the same cookie-bearing page session as well.
  try {
    new PerformanceObserver(list => list.getEntries().forEach(entry => requestPlaylist(entry.name))).observe({entryTypes:['resource']});
    performance.getEntriesByType('resource').forEach(entry => requestPlaylist(entry.name));
  } catch {}
  const scan = () => {
    if (complete) {
      try { window.jwplayer?.()?.pause?.(); } catch {}
      document.querySelectorAll('video').forEach(video => { try { video.pause(); } catch {} });
      return;
    }
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
