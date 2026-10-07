(function(){
  if(window.__lilacReAnimeFlixStarted)return;
  window.__lilacReAnimeFlixStarted=true;
  window.__lilacReAnimeFlixResult='';
  const fail=(e)=>{window.__lilacReAnimeFlixResult=JSON.stringify({ok:false,error:String(e&&e.message||e)});console.error('[LilacFlix] '+String(e&&e.stack||e));};
  const esc=s=>String(s).replace(/[.*+?^$()|[\]\\]/g,'\\$&');
  const get=(html,key)=>{const m=html.match(new RegExp('(?:["\\']?)'+esc(key)+'(?:["\\']?)\\s*:\\s*["\\']([^"\\']*)["\\']'));return m?m[1]:null};
  const b64=s=>{const x=atob(String(s).replace(/-/g,'+').replace(/_/g,'/'));const a=new Uint8Array(x.length);for(let i=0;i<x.length;i++)a[i]=x.charCodeAt(i);return a};
  const b64s=a=>{let s='';for(let i=0;i<a.length;i+=0x8000)s+=String.fromCharCode.apply(null,a.subarray(i,Math.min(i+0x8000,a.length)));return btoa(s)};
  const sha=async s=>{const d=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(s));return Array.from(new Uint8Array(d)).map(x=>x.toString(16).padStart(2,'0')).join('')};
  (async()=>{
    try{
      const html=document.documentElement.innerHTML;
      const seed=get(html,'obfuscation_seed');
      const payload=get(html,'w_payload');
      if(!seed||!payload)throw new Error('embedded data missing seed='+!!seed+' payload='+!!payload);
      let e=seed;for(let i=0;i<3;i++)e=await sha(e+i);let a=e;for(let i=0;i<3;i++)a=await sha(a+i);
      const names={videoField:'vf_'+e.substring(0,8),keyField:'kf_'+e.substring(8,16),ivField:'ivf_'+e.substring(16,24),containerName:'cd_'+e.substring(24,32),arrayName:'ad_'+e.substring(32,40),objectName:'od_'+e.substring(40,48),tokenField:e.substring(48,64)+'_'+e.substring(56,64),keyFrag2Field:a.substring(0,16)+'_'+a.substring(16,24)};
      const frag=get(html,names.keyField),iv=get(html,names.ivField),key2=get(html,names.keyFrag2Field),token=get(html,names.tokenField);
      if(!frag||!iv||!key2||!token)throw new Error('crypto fields missing frag='+!!frag+' iv='+!!iv+' key2='+!!key2+' token='+!!token);
      const api=await fetch('/api/m3u8/'+encodeURIComponent(token),{credentials:'same-origin'});if(!api.ok)throw new Error('token HTTP '+api.status);const w=await api.json();
      const vf=await sha(token+'vid');const kf=await sha(token+'key');const E=w[vf.substring(0,10)],Y=w[kf.substring(0,10)];if(!E||!Y)throw new Error('token fields missing');
      const wasm=b64(payload), inst=(await WebAssembly.instantiate(wasm,{})).instance, ex=inst.exports, mem=ex.memory;if(mem.buffer.byteLength===0)mem.grow(1);const bytes=new Uint8Array(mem.buffer);
      const t=b64(frag), k=b64(key2), y=b64(Y), n=t.length, p1=1000,p2=p1+n,p3=p2+n,p4=p3+n;bytes.set(t,p1);bytes.set(k,p2);bytes.set(y,p3);ex._s(parseInt(seed.substring(0,8),16));ex._r(p1,p2,p3,p4,n);
      const H=new Uint8Array(ex.memory.buffer).slice(p4,p4+n);
      const pkPtr=ex._c(), pkBytes=new Uint8Array(ex.memory.buffer).slice(pkPtr,pkPtr+32), pk=b64s(pkBytes);
      const base=await crypto.subtle.importKey('raw',H,{name:'PBKDF2'},false,['deriveBits']);const bits=await crypto.subtle.deriveBits({name:'PBKDF2',salt:new TextEncoder().encode(seed),iterations:1000,hash:'SHA-256'},base,256);const J=new Uint8Array(bits);for(let i=0;i<32;i++)J[i]^=seed.charCodeAt(i%seed.length);
      const digest=new Uint8Array(await crypto.subtle.digest('SHA-256',J));const aes=await crypto.subtle.importKey('raw',digest,{name:'AES-CBC'},false,['decrypt']);const plain=await crypto.subtle.decrypt({name:'AES-CBC',iv:b64(iv)},aes,b64(E));const m3u8=new TextDecoder().decode(plain).trim();if(!m3u8)throw new Error('empty HLS URL');
      window.__lilacReAnimeFlixResult=JSON.stringify({ok:true,m3u8:m3u8,pk:pk});
      console.log('[LilacFlix] BOOTSTRAP_OK '+m3u8);
    }catch(e){fail(e)}
  })();
})();
