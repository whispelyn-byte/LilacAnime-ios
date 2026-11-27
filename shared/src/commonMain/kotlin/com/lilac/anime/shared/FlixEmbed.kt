package com.lilac.anime.shared

/** A FlixCloud player is an embed: since November 2026 it answers a page opened on its own (a top-level document) with
 *  "404: File not found", and plays only inside a frame (desktop 0.5.67). The apps open it as RE:Anime does, in an iframe
 *  of a page on the referring site's address ([embedder], the page's base URL), so it is asked with that site as its
 *  referrer.
 *
 *  The page scripts (flix-bootstrap.js, flix-discovery.js, the capture) run in every frame and post what they find from
 *  the player's frame as before. What the resolver reads from the top page (window.__pk, __lilacReAnimeFlixResult and
 *  __lilacFlixDiscovery.finish()) the player's frame passes up (flix-discovery.js posts {lilacFlixState}); [page] keeps
 *  the last of it on the top page under the same names. */
object FlixEmbed {
    private val host = Regex("""^https?://(?:[\w-]+\.)?flixcloud\.cc(?=[/?#:]|$)""", RegexOption.IGNORE_CASE)
    fun isPlayer(url: String): Boolean = host.containsMatchIn(url)

    /** The page the player is framed in: the origin of [referer] (the episode's page), else RE:Anime's. */
    fun embedder(referer: String): String {
        val live = SiteHost.live(referer.trim())
        val origin = Regex("""^(https?://[^/?#]+)""", RegexOption.IGNORE_CASE).find(live)?.groupValues?.get(1)
        return (origin ?: ReAnimeHost.WEB) + "/"
    }

    fun page(player: String): String {
        val src = player.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="referrer" content="origin">""" +
            """<meta name="viewport" content="width=device-width,initial-scale=1">""" +
            """<style>html,body{margin:0;height:100%;background:#000}iframe{border:0;width:100%;height:100%}</style></head>""" +
            """<body><iframe src="$src" allow="autoplay; fullscreen; encrypted-media" allowfullscreen></iframe><script>$RELAY</script></body></html>"""
    }

    private const val RELAY = """(()=>{
const frame=document.querySelector('iframe');let state={pk:'',result:'',tracks:[],stopped:false};
addEventListener('message',event=>{
  if(!frame||event.source!==frame.contentWindow)return;
  const s=event.data&&event.data.lilacFlixState;if(!s||typeof s!=='object')return;
  state={pk:String(s.pk||''),result:String(s.result||''),tracks:Array.isArray(s.tracks)?s.tracks:[],stopped:state.stopped||!!s.stopped};
});
Object.defineProperty(window,'__pk',{configurable:true,get:()=>state.pk});
Object.defineProperty(window,'__lilacReAnimeFlixResult',{configurable:true,get:()=>state.result});
window.__lilacFlixDiscovery={
  get stopped(){return state.stopped},
  scan(){},
  finish(){
    state.stopped=true;
    try{frame.contentWindow.postMessage({lilacStopDiscovery:true},'*')}catch(_){}
    return state.tracks.map(t=>({url:String(t&&t.url||''),language:String(t&&t.language||'und'),label:String(t&&(t.label||t.language)||''),format:String(t&&t.format||'')})).filter(t=>/^https?:/i.test(t.url));
  }
};
})();"""
}
