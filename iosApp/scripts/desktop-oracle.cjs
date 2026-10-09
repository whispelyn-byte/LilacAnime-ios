// Run with Node: desktop-oracle.cjs /path/to/LilacAnime-desktop.
// Expected values come from the desktop working files, not a second implementation.
const fs = require('fs'), path = require('path'), vm = require('vm'), crypto = require('crypto');
const desktop = path.resolve(process.argv[2]), root = path.resolve(__dirname, '../..');
const app = fs.readFileSync(path.join(desktop, 'src/app.js'), 'utf8');
const trackBody = app.match(/function translationSourceTrack\(\)\{[\s\S]*?\n\}/)[0];
const koreanBody = app.match(/function isKoreanTrack\(track\)\{[^\n]+/)[0];
const savedBody = app.match(/const savedPreferred=([^;]+);if\(savedPreferred\)/)[1];
const meta = require(path.join(desktop, 'src/anime-metadata.js'));
const audio = require(path.join(desktop, 'electron/oped-fingerprint.cjs'));
const decode = require(path.join(desktop, 'electron/catalog-updates.cjs')).decodeData;
const translator = fs.readFileSync(path.join(desktop,'electron/subtitle-translator.cjs'),'utf8');
const main = fs.readFileSync(path.join(desktop,'electron/main.cjs'),'utf8');
const pure = ['simpleTitle','titleKey','communitySeason','titleSeason','cleanKoreanTitle','withSeason','searchSeason','titleScore','hangulEditSimilarity','communityScore','communityEpisodes','communityPostEpisodes','communityPostTitle','communityTitle','communityLinks','tmdbQueries','siblingTitle','titleCompareKey'].map(name=>{
  const start=main.indexOf('function '+name+'('),next=main.indexOf('\n}',start);
  const firstLine=main.slice(start,main.indexOf('\n',start));
  return firstLine.endsWith('}')?firstLine:main.slice(start,next+2);
}).join('\n');
const helper = ['hasHangul','NOT_ANIME','hasSeasonMark','COMMUNITY_TRAILING_EPISODE'].map(name => main.split(/\r?\n/).find(line => line.startsWith('const '+name+'='))).join('\n');
const context={cheerio:require(path.join(desktop,'node_modules/cheerio')),absoluteUrl:(u,b)=>new URL(u,b).href};
vm.runInNewContext(helper+pure+';globalThis.rules={titleKey,titleSeason,cleanKoreanTitle,withSeason,communityLinks,communityScore,tmdbQueries,siblingTitle,titleCompareKey}',context);
const winPngBody = main.slice(main.indexOf('async function winPngEntries(')).match(/executeJavaScript\(`([\s\S]*?)`,true\)/)[1];
fs.writeFileSync(path.join(root,'iosApp/LilacAnime/Resources/winpng-reader.js'),vm.runInNewContext('`'+winPngBody+'`')+'\n');
const systemBody = translator.match(/const system = \(context = \{\}, wrapped = false\) => \{[\s\S]*?\n  \};/)[0];
const prompts = [false,true].map(wrapped=>vm.runInNewContext(systemBody+';system({},wrapped)',{wrapped,characterTerms:()=>[]}));
fs.writeFileSync(path.join(root,'shared/src/commonMain/kotlin/com/lilac/anime/shared/DesktopCloudPrompt.kt'),
  'package com.lilac.anime.shared\n\n// Copied by desktop-oracle.cjs from electron/subtitle-translator.cjs/system.\nobject DesktopCloudPrompt {\n    private val array = """'+prompts[0]+'"""\n    private val wrapped = """'+prompts[1]+'"""\n    fun build(context: String, objectInput: Boolean): String {\n        val source = if (objectInput) wrapped else array\n        if (context.isBlank()) return source\n        return source.replace("\\n\\nFORMAT", "\\n" + context.trim() + "\\n\\nFORMAT")\n    }\n}\n');
const track = (label, language = '', url = 'https://fixture.test/sub.ass') => ({label, language, url});
const trackSets = [[], [track('English signs', 'en'), track('English dialogue', 'en')],
  [track('English full', 'en'), track('日本語', 'ja')], [track('English (AI)', 'en'), track('English', 'en')],
  [track('English forced', 'en'), track('English songs', 'en')], [track('French','fr')],
  [track('Custom', 'en-US'), track('Japanese', 'ja-JP')], [track('Default', 'en'), track('English dubtitle', 'en')]];
const korean = [track('한국어'),track('Korean','kor'),track('Custom','ko'),track('Custom','ko-KR'),track('Custom','en','https://fixture.test/x_kor_1.ass'),track('English','en'),track('한국어','en'),track('Custom','kok'),track('Custom','en','https://fixture.test/ko.ass')];
const savedCases = [
  {preferred:'kairan',saved:[{source:'gemini'},{source:'kairan'}]},
  {preferred:'reanime',saved:[{source:'gemini'},{source:'reanime'}]},
  {preferred:'jimaku',saved:[{source:'kairan'},{source:'gemini'},{source:'jimaku'}]},
  {preferred:'csora',saved:[{source:'kairan'}]},
  {preferred:'reanime',saved:[{source:'jimaku'},{source:'gemini'}]}
];
const metadata = [
  {year:2026,season:'FALL',availableEpisodes:0,totalEpisodes:12},
  {year:2026,season:'SPRING',availableEpisodes:4,totalEpisodes:12},
  {year:2026,season:'SUMMER',totalEpisodes:1},
  {year:2026,season:'WINTER'}, {aired:'2025-12-24',year:2026,season:'FALL'},
  {startedOn:'2026-02',availableEpisodes:13,totalEpisodes:0},
  {availableEpisodes:0,totalEpisodes:null}, {aired:'bad',year:2025}
];
function noise(frames, seed) { let x=seed|0; const values=[]; for(let f=0;f<frames;f++){const row=new Float32Array(32);for(let b=0;b<32;b++){x^=x<<13;x^=x>>>17;x^=x<<5;row[b]=x/2147483648}values.push(row)}return values }
const regions = [{frames:1000,from:220,to:410,length:500},{frames:1000,from:0,to:100,length:300},{frames:600,from:10,to:50,length:450},{frames:250,from:0,to:0,length:100}].map(c=>{
  const a=noise(c.frames,123),b=noise(c.frames,456);for(let i=0;i<c.length;i++)b[c.to+i]=a[c.from+i];
  return {...c,expected:audio.findRepeatedRegion(a,b)};
});
const tables = [[{latestAired:1},[2],{id:3,episode:4},'test',{aired:5},['Date','2026-10-08T03:00:00.000Z']], [{a:1,b:1,__proto__:2},'same','ignored'],[[1,2,3],'ok',null,-1]];
const result = {
  tmdbQueries:[['Kamisama Kiss◎ OVA'],['Ascendance of a Bookworm: Adopted Daughter of an Archduke Season 3'],['Anime 2nd Season','Anime 2nd Season','한글 제목'],['Rascal Does Not Dream of Santa Claus']].map(input=>({input,expected:context.rules.tmdbQueries(input)})),
  tmdbSiblings:[['청춘 돼지는 바니걸 선배의 꿈을 꾸지 않는다','청춘 돼지는 산타클로스의 꿈을 꾸지 않는다'],['외톨이 THE ROCK!','봇치 더 록!'],['장송의 프리렌','장송의 프리렌']].map(input=>({input,expected:context.rules.siblingTitle(...input)})),
  seasonNames:[['오늘부터 신령님','Kamisama Kiss◎ OVA','OVA'],['진격의 거인','Attack on Titan Season 2','TV'],['작품','Anime','SPECIAL'],['작품 OVA','Anime OVA','OVA'],['한글 이름','한글 원제','TV']].map(input=>({input,expected:context.rules.withSeason(...input)})),
  titles:['Overlord IV','The Angel Next Door Spoils Me Rotten2','Kaiju No. 8','Mob Psycho 100','Part 2','무직전생3','무직전생 Ⅱ','카구야 님은 고백받고 싶어 (애니메이션 1기)','봇치 더 록! (애니메이션)','진격의 거인(비디오 게임 시리즈)','나 혼자만 레벨업 1화','Anime Ｓｅａｓｏｎ ２'].map(input=>({input,key:context.rules.titleKey(input),season:context.rules.titleSeason(input)??1,clean:context.rules.cleanKoreanTitle(input)})),
  community:[
    {title:'작품 3화',html:'<a href="/3.ass">자막</a><a href="https://drive.google.com/file/d/fonts/view">폰트</a>',episode:3},
    {title:'작품',html:'<a href="/13.ass">13화</a><a href="/14.ass">14화</a><a href="/fonts.zip">폰트</a>',episode:2},
    {title:'작품',html:'<a href="/bundle.zip">1 ~ 12화</a>',episode:4},
    {title:'작품 4화',html:'<a href="/4.ass">자막</a>',episode:3},
    {title:'극장판',html:'<a href="/movie.zip">자막</a>',episode:1}
  ].map(input=>{const post={title:input.title,html:input.html,url:'https://fixture.test/post'};return {input,expected:context.rules.communityLinks(post,input.episode)}}),
  subtitleTracks:trackSets.map(tracks=>({tracks,expected:vm.runInNewContext(trackBody+';translationSourceTrack()', {currentPlaybackContext:{subtitleTracks:tracks}})?.label ?? null})),
  korean:korean.map(input=>({input,expected:vm.runInNewContext(koreanBody+';isKoreanTrack(track)',{track:input})})),
  saved:savedCases.map(c=>({...c,expected:vm.runInNewContext(savedBody,c)?.source ?? null})),
  metadata:metadata.map(input=>({input,release:meta.releaseDate(input),label:meta.episodeLabel(input)})), regions,
  svelte:tables.map(input=>({input,expected:decode(input)})),
  pcm:{frames:audio.fingerprint(Float32Array.from({length:24000},(_,i)=>Math.sin(i*.021)+.3*Math.sin(i*.057))).length,
       first:audio.fingerprint(Float32Array.from({length:24000},(_,i)=>Math.sin(i*.021)+.3*Math.sin(i*.057))).slice(0,2).flatMap(x=>Array.from(x))}
};
const localSource = fs.readFileSync(path.join(desktop, 'electron/local-ai.cjs'), 'utf8');
const localContext = {};
vm.runInNewContext(localSource.slice(localSource.indexOf('const INSTRUCTION ='), localSource.indexOf('function createLocalAi(')) +
  localSource.match(/  function clean\(output, original\) \{[\s\S]*?\n  \}/)[0] + ';globalThis.rules={request,modelKind,clean}', localContext);
const glossary = require(path.join(desktop, 'electron/anime-glossary.cjs'));
const characters = [
  {name:'Sota Hori',native:'堀 創太',first:'Sota',last:'Hori',gender:'Male'},
  {name:'Kyoko Hori',native:'堀 京子',first:'Kyoko',last:'Hori',gender:'Female'},
  {name:'Amane Fujimiya',native:'藤宮周',first:'Amane',last:'Fujimiya',gender:'Male'}
];
const cast = characters.map(c => { const t=glossary.characterTerms(characters).find(t=>t.ja===c.native.trim()); return t ? `${t.ja} = ${t.ko} (${c.gender.toLowerCase()})` : ''; }).filter(Boolean).join('\n');
result.localPrompts = ['gemma-4-e4b','aya-expanse-8b','Hy-MT2-7B','Hy-MT2-30B-A3B','HY-MT1.5-7B','ja-ko-vn','Qwen3-8B'].flatMap(model => [false,true].map(context => {
  const source='（創太）お姉ちゃん、ただいま！', before=context?['前の台詞','その次の台詞']:[];
  const terms=context?[...glossary.termsFor(source,glossary.characterTerms(characters)),...glossary.speakerTerms(source,characters)]:[];
  const kind=localContext.rules.modelKind({id:model,file:model,label:model}), body=localContext.rules.request(source,terms,kind,before,context?cast.split('\n'):[]);
  return {model,source,before:before.join('\n'),terms:terms.map(t=>`${t.ja}=${t.ko}`).join('\n'),cast:context?cast:'',kind,
    prompt:body.messages.map(m=>m.content).join('\u001e'),temperature:body.temperature,topP:body.top_p,topK:body.top_k??40,repetition:body.repeat_penalty??1};
}));
result.localKinds = [['model-Q4_K_M.gguf','고유명사 및 용어 규칙'],['custom.gguf','<|turn>'],['gemma-3-4b',''],['Qwen3.5-9B',''],['Aya-Expanse-8B',''],['HY-MT1.5','']].map(([model,template])=>({model,template,expected:localContext.rules.modelKind({id:model},template)}));
result.localClean = [
  ['前の文\n앞 문맥 번역\n현재 대사<|END_OF_TURN_TOKEN|>','一行'], ['```korean\n번역 1\n번역 2\n```','一行\n二行'],
  ['<target>한국어</target>','一行'], ['explanation\n<TARGET>올바른\n두 줄</TARGET>','一行\n二行'], ['\r\n  \r\n',''],
  ['원문 번역\r\n다음 번역<|im_end|>','一行\n二行']
].map(([output,original])=>({output,original,expected:localContext.rules.clean(output,original)}));
result.localTerms = ['（創太）お姉ちゃん、ただいま！','（京子）お兄ちゃん','藤宮周くんと周辺','ただいまー','ただいま走っています','（堀）お兄ちゃん'].map(source=>({source,characters,
  expected:[...glossary.termsFor(source,glossary.characterTerms(characters)),...glossary.speakerTerms(source,characters)].map(t=>`${t.ja}=${t.ko}`).join('\n'),cast}));
const target=path.join(root,'iosApp/LilacAnimeTests/Fixtures/desktop-oracle.json');
fs.writeFileSync(target,JSON.stringify(result,null,2)+'\n');
const kotlin = `package com.lilac.anime.shared\n\n// Generated by iosApp/scripts/desktop-oracle.cjs from desktop source.\ninternal val desktopOracle = kotlinx.serialization.json.Json.parseToJsonElement("""\n${JSON.stringify(result)}\n""").jsonObject\n`;
fs.writeFileSync(path.join(root,'shared/src/commonTest/kotlin/com/lilac/anime/shared/DesktopOracleFixture.kt'),kotlin.replace('internal val','import kotlinx.serialization.json.jsonObject\n\ninternal val'));
console.log(`Wrote desktop oracle (${result.tmdbQueries.length+result.tmdbSiblings.length+result.seasonNames.length+result.titles.length+result.community.length+result.subtitleTracks.length+result.korean.length+result.saved.length+result.metadata.length+result.regions.length+result.svelte.length+1} cases)`);
