(async()=>{
      // The viewer answers an image it cannot read with prompt() and alert(), which would open real dialogs.
      window.alert=()=>{};window.prompt=()=>null;window.confirm=()=>false;
      const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
      for(let i=0;i<20&&typeof window.downloadZip!=='function';i++)await wait(250);
      if(typeof window.downloadZip!=='function')return [];
      const read=async url=>{try{return url?await (await fetch(url)).text():null}catch{return null}};
      for(const img of document.querySelectorAll('.contents_style img, .tt_article_useless_p_margin img, article img')){
        img.click();let entries=[];
        // Done when every file is listed and none is still being converted.
        for(let i=0;i<60;i++){await wait(500);entries=[...document.querySelectorAll('a[data-href]')];if(entries.length&&!document.querySelector('a.processing'))break}
        if(!entries.length)continue;
        const out=[];
        for(const a of entries){const name=a.download||'';out.push({name,ass:await read(a.getAttribute('data-ass')),smi:await read(a.getAttribute('data-smi')||a.getAttribute('data-cleared')),raw:/\.(?:ass|ssa|srt|vtt)$/i.test(name)?await read(a.getAttribute('data-href')):null})}
        return out;
      }
      return [];
    })()
