// Run with Node: desktop-oracle.cjs /path/to/LilacAnime-desktop.
// Expected values come from the desktop working files, not a second implementation.
const fs = require('fs'), path = require('path'), vm = require('vm'), crypto = require('crypto');
const desktop = path.resolve(process.argv[2]), root = path.resolve(__dirname, '../..');
const app = fs.readFileSync(path.join(desktop, 'src/app.js'), 'utf8');
const subtitleSource = require(path.join(desktop, 'src/subtitle-source.js'));
const koreanBody = app.match(/function isKoreanTrack\(track\)\{[^\n]+/)[0];
const savedBody = app.match(/const savedPreferred=([^;]+);if\(savedPreferred\)/)[1];
const meta = require(path.join(desktop, 'src/anime-metadata.js'));
const audio = require(path.join(desktop, 'electron/oped-fingerprint.cjs'));
const decode = require(path.join(desktop, 'electron/catalog-updates.cjs')).decodeData;
const translator = fs.readFileSync(path.join(desktop,'electron/subtitle-translator.cjs'),'utf8');
const main = fs.readFileSync(path.join(desktop,'electron/main.cjs'),'utf8');
const pure = ['simpleTitle','titleKey','communitySeason','titleSeason','cleanKoreanTitle','withSeason','searchSeason','titleScore','hangulEditSimilarity','communityScore','communityEpisodes','communityPostEpisodes','communityPostTitle','communityTitle','communitySeasons','communityLinks','tmdbQueries','koreanTmdbQueries','siblingTitle','titleCompareKey'].map(name=>{
  const start=main.indexOf('function '+name+'('),next=main.indexOf('\n}',start);
  if (start < 0 && name === 'communityEpisodes' && fs.existsSync(path.join(desktop,'electron/community-matching.cjs'))) return '';
  if (start < 0) throw new Error('Missing desktop function: '+name);
  const firstLine=main.slice(start,main.indexOf('\n',start));
  return firstLine.endsWith('}')?firstLine:main.slice(start,next+2);
}).join('\n');
const helper = ['hasHangul','NOT_ANIME','hasSeasonMark','COMMUNITY_TRAILING_EPISODE'].map(name => main.split(/\r?\n/).find(line => line.startsWith('const '+name+'='))).join('\n');
const context={cheerio:require(path.join(desktop,'node_modules/cheerio')),absoluteUrl:(u,b)=>new URL(u,b).href};
// communityLinks plans its links with the module's helpers (planCommunityLinks, communityLinkEpisodes since v0.5.43).
if (fs.existsSync(path.join(desktop,'electron/community-matching.cjs'))) Object.assign(context,require(path.join(desktop,'electron/community-matching.cjs')));
vm.runInNewContext(helper+pure+';globalThis.rules={titleKey,titleSeason,cleanKoreanTitle,withSeason,communitySeason,communitySeasons,communityLinks,communityScore,tmdbQueries,koreanTmdbQueries,siblingTitle,titleCompareKey}',context);
const winPngBody = main.slice(main.indexOf('async function winPngEntries(')).match(/executeJavaScript\(`([\s\S]*?)`,true\)/)[1];
fs.writeFileSync(path.join(root,'iosApp/LilacAnime/Resources/winpng-reader.js'),vm.runInNewContext('`'+winPngBody+'`')+'\n');
const systemBody = translator.match(/const system = \(context = \{\}, wrapped = false\) => \{[\s\S]*?\n  \};/)[0];
// 번역 프롬프트: the app's rules, which the user's own replace (custom) or are followed by (extra).
const rulesBody = translator.match(/const TRANSLATION_RULES = \[[\s\S]*?\]\.join\('\\n'\);/)[0];
const translationRules = vm.runInNewContext(rulesBody+';TRANSLATION_RULES');
const prompts = [false,true].map(wrapped=>vm.runInNewContext(systemBody+';system({},wrapped)',{wrapped,characterTerms:()=>[],TRANSLATION_RULES:translationRules}));
fs.writeFileSync(path.join(root,'shared/src/commonMain/kotlin/com/lilac/anime/shared/DesktopCloudPrompt.kt'),
  'package com.lilac.anime.shared\n\n// Copied by desktop-oracle.cjs from electron/subtitle-translator.cjs/system.\nobject DesktopCloudPrompt {\n    private val array = """'+prompts[0]+'"""\n    private val wrapped = """'+prompts[1]+'"""\n    /** TRANSLATION_RULES: 번역 프롬프트 > 기본 규칙 불러오기 starts from it. */\n    val rules = """'+translationRules+'"""\n    /** 번역 프롬프트 (system\'s context.rules): custom takes the place of [rules], extra comes after them and is followed first. */\n    fun build(context: String, objectInput: Boolean, extra: String = "", custom: String = ""): String {\n        var source = if (objectInput) wrapped else array\n        if (custom.isNotBlank()) source = source.replace(rules, custom.trim())\n        if (extra.isNotBlank()) source += "\\n\\nADDITIONAL RULES FROM THE USER (follow these first where they differ from the rules above)\\n" + extra.trim()\n        source = source.replace(Regex("\\n{3,}"), "\\n\\n").trim()\n        if (context.isBlank()) return source\n        return source.replace("\\n\\nFORMAT", "\\n" + context.trim() + "\\n\\nFORMAT")\n    }\n}\n');
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
  communityFiles: (fs.existsSync(path.join(desktop,'electron/community-matching.cjs')) ? [
    {names:['Kaiju No. 8 [1080p] [5A2B3C4D].ass','Anime E05.srt'],episode:8,strict:true},
    {names:['Anime S01E05.ass','Anime S02E05.ass'],episode:5,season:2,strict:true},
    {names:['Anime - 03v2.ass','Anime - 03.srt'],episode:3,strict:true},
    {names:['Anime 2025 1080p.ass','Anime 2025 720p.srt'],episode:1,strict:true},
    {names:['Anime 2025 1080p.ass'],episode:2,strict:true,bundle:true},
    {names:['NCOP.ass','Anime - 01.srt'],episode:1},
    {names:['Movie.smi','Movie.ass'],episode:1,strict:true},
    {names:['Movie.ass','Song.srt'],episode:2,strict:true,bundle:true},
    {names:['Anime E12.5.ass','Anime E12.ass'],episode:12.5,strict:true},
    {names:['작품 1~12화.ass','작품 12화.srt'],episode:12,strict:true,bundle:true},
    {names:['Anime.ass','Anime E05.ass'],episode:6},
    {names:['예고편.ass','작품.ass'],episode:1,strict:true},
    {names:['Kaiju No. 8.ass','Show E08.srt'],episode:8,strict:true},
    {names:['Show Part 2.ass','Show E02.srt'],episode:2,strict:true},
    {names:['[Show][01v2].ass','Show 01.srt'],episode:1,strict:true},
    {names:['Show_01_NCOP.ass','Show 01.srt'],episode:1},
    {names:['Show_01_PV.ass','Show 01.srt'],episode:1},
    // v0.5.41-43: release names, glued release groups, numbered sets and season-marked files.
    {names:['[SubsPlease] Black Torch - 06 (1080p) [6D89AB2F].srt','[SubsPlease] Black Torch - 07 (1080p) [7D89AB2F].srt'],episode:7,strict:true},
    {names:['[Ohys-Raws] Kikansha no Mahou wa Tokubetsu Desu - 01 (BS11 1280x720 x264 AAC).smi'],episode:1,strict:true},
    {names:['Hotaru Yome 01_SubsPlease.srt','Hotaru Yome 02_SubsPlease.srt'],episode:2,strict:true},
    {names:['약사의 혼잣말03.ass','약사의 혼잣말04.ass'],episode:3,strict:true},
    {names:['꽃피우는 그대들에게2.smi'],episode:2,strict:true},
    {names:[1,2,3,4,5,6,7,8,9].map(n=>`Yasei no Last Boss ga Arawareta - 0${n}SubsPlease.smi`).concat('Yasei no Last Boss ga Arawareta - 08SubsPlease.vtt'),episode:1},
    {names:['ab01cd.smi','ab02cd.smi','ab03cd.smi'],episode:2},
    {names:['ab01cd.smi','ab02cd.smi','ab03cd.smi'],episode:4},
    {names:['Clevatess S2 - 01SubsPlease.smi','Clevatess - 01SubsPlease.smi'],episode:1,season:1},
    {names:['Clevatess S2 - 01SubsPlease.smi','Clevatess - 01SubsPlease.smi'],episode:1,season:2},
    {names:['Clevatess S2 - 01SubsPlease.smi','Clevatess - 01SubsPlease.smi'],episode:1},
    {names:['Link.Click.S03E02.1080p.ass'],episode:2,strict:true,bundle:true},
    {names:['[SubsPlease] Iseleve - OVA (1080p) [FEE28781].smi'],episode:1,strict:true},
    {names:['Show - 2nd.ass','Show - 01.ass'],episode:1,strict:true}
  ].map(input=>{const files=input.names.map((name,i)=>({file:name,name,size:100+i}));return {input,expected:require(path.join(desktop,'electron/community-matching.cjs')).selectCommunityFile(files,input)?.name??null}}) : []),
  // pickCommunityFile (v0.5.43): a link named for the episode alone gives its one episode whatever its number.
  communityPick: (fs.existsSync(path.join(desktop,'electron/community-matching.cjs')) && require(path.join(desktop,'electron/community-matching.cjs')).pickCommunityFile ? [
    {names:['[SubsPlease] Golden Kamuy - 62 (1080p) [851C234E].srt'],episode:13,exact:true},
    {names:['[SubsPlease] Golden Kamuy - 62 (1080p) [851C234E].srt'],episode:13},
    {names:['Show - 01.ass','Show - 02.ass'],episode:3,exact:true},
    {names:['anissia_file0.smi'],episode:4,exact:true},
    {names:['anissia_file0.smi'],episode:4,strict:true},
    {names:['anissia_file0.smi','anissia_file1.smi'],episode:1,exact:true,strict:false},
    {names:['Show - 03.ass','Show.ass'],episode:3,strict:false},
    {names:['Show.zip.ass','Other.ass'],episode:2,strict:false,bundle:true}
  ].map(input=>{const files=input.names.map((name,i)=>({file:name,name,size:100+i}));return {input,expected:require(path.join(desktop,'electron/community-matching.cjs')).pickCommunityFile(files,input)?.name??null}}) : []),
  communityLinkEpisodes: (fs.existsSync(path.join(desktop,'electron/community-matching.cjs')) && require(path.join(desktop,'electron/community-matching.cjs')).communityLinkEpisodes ? [
    '이세계 유유자적 농가 1-12 SubsPlease','Kami no Shizuku1-24.Zip 0.26MB','클레바테스 S2 03-04 SubsPlease','Kanata kara - 03Subsplease.smi','Hotaru Yome 01.zip 0.02MB',
    'LasTame S2.zip 0.17MB','[CoNe] 애니 나소흑전기 - 회차 전체 통합.zip 7.29MB','자막 다운로드','BLACK TORCH','작품 1+5화','작품 1·5화','작품 1화~3화','작품 1화 - 3화','작품 2기 - 5화','13화 SRT','Golden Kamuy - 62.zip'
  ].map(input=>{const found=require(path.join(desktop,'electron/community-matching.cjs')).communityLinkEpisodes(input);return {input,list:found.list,ranges:found.ranges}}) : []),
  communitySeasons:['자판기 1기/2기/3기','작품 2기','클레바테스Ⅱ ~마수왕과 가짜 용사 전승~','시광대리인 Ⅱ -Link Click Ⅱ-','시광대리인 -Link Click- 영도편','Overlord IV','작품 3기 12화','작품'].map(input=>({input,season:context.rules.communitySeason(input)??null,seasons:context.rules.communitySeasons?[...context.rules.communitySeasons(input)]:[]})),
  koreanTmdbQueries:context.rules.koreanTmdbQueries?['장송의 프리렌 2기','죠죠의 기묘한 모험 5부: 황금의 바람','귀멸의 칼날 (자막) 3','시광대리인 ~Link Click~','어떤 작품 시즌 2','Frieren','약사의 혼잣말 ORIGINAL'].map(input=>({input,expected:context.rules.koreanTmdbQueries(input)})):[],
  reanime: [
    {anime_id:'sample', title:{english:'Sample',native:'原題'},average_score:87,popularity:99,season:'SPRING',season_year:2025,status:'Not yet released',started_on:'2025-04-12',subbed:0,dubbed:0},
    {anime_id:'second', title:{romaji:'Second'},average_score:91,season:'FALL',season_year:2026,start_date:'2026-10-08',subbed:2,episodes:12}
  ].map(input=>({input,expected:vm.runInNewContext(main.slice(main.indexOf('function reanimeItems('),main.indexOf('\n}',main.indexOf('function reanimeItems('))+2)+';reanimeItems({data:[input]})[0]',{input,REANIME_WEB:'https://reanime.to'})})),
  tmdbQueries:[['Kamisama Kiss◎ OVA'],['Ascendance of a Bookworm: Adopted Daughter of an Archduke Season 3'],['Anime 2nd Season','Anime 2nd Season','한글 제목'],['Rascal Does Not Dream of Santa Claus'],['葬送のフリーレン 2期'],['葬送のフリーレン 第2期'],['薬屋のひとりごと シーズン 2']].map(input=>({input,expected:context.rules.tmdbQueries(input)})),
  tmdbSiblings:[['청춘 돼지는 바니걸 선배의 꿈을 꾸지 않는다','청춘 돼지는 산타클로스의 꿈을 꾸지 않는다'],['외톨이 THE ROCK!','봇치 더 록!'],['장송의 프리렌','장송의 프리렌']].map(input=>({input,expected:context.rules.siblingTitle(...input)})),
  seasonNames:[['오늘부터 신령님','Kamisama Kiss◎ OVA','OVA'],['진격의 거인','Attack on Titan Season 2','TV'],['작품','Anime','SPECIAL'],['작품 OVA','Anime OVA','OVA'],['한글 이름','한글 원제','TV']].map(input=>({input,expected:context.rules.withSeason(...input)})),
  titles:['Overlord IV','The Angel Next Door Spoils Me Rotten2','Kaiju No. 8','Mob Psycho 100','Part 2','무직전생3','무직전생 Ⅱ','카구야 님은 고백받고 싶어 (애니메이션 1기)','봇치 더 록! (애니메이션)','진격의 거인(비디오 게임 시리즈)','나 혼자만 레벨업 1화','Anime Ｓｅａｓｏｎ ２'].map(input=>({input,key:context.rules.titleKey(input),season:context.rules.titleSeason(input)??1,clean:context.rules.cleanKoreanTitle(input)})),
  community:[
    {title:'작품 3화',html:'<a href="/3.ass">자막</a><a href="https://drive.google.com/file/d/fonts/view">폰트</a>',episode:3},
    {title:'작품',html:'<a href="/13.ass">13화</a><a href="/14.ass">14화</a><a href="/fonts.zip">폰트</a>',episode:2},
    {title:'작품',html:'<a href="/bundle.zip">1 ~ 12화</a>',episode:4},
    {title:'작품 4화',html:'<a href="/4.ass">자막</a>',episode:3},
    {title:'극장판',html:'<a href="/movie.zip">자막</a>',episode:1},
    {title:'작품 1~12화',html:'<a href="/bundle.zip">자막</a>',episode:1},
    {title:'작품 1, 2화',html:'<a href="/bundle.zip">자막</a>',episode:1},
    {title:'작품',html:'<a href="/1.ass">1화 ASS</a><a href="/1.srt">1화 SRT</a><a href="/2.ass">2화</a>',episode:1},
    {title:'작품 12.5화 자막',html:'<a href="/12.5.ass">자막</a>',episode:12.5},
    {title:'작품 12.5화 자막',html:'<a href="/12.5.ass">자막</a>',episode:5},
    {title:'작품',html:'<a href="/12.5.ass">12.5화</a><a href="/12.ass">12화</a>',episode:12.5},
    // v0.5.42-43: bundles written with + and ·, link tiers, bare ranges, last and cumulative posts, per-season links.
    {title:'작품 1+5화',html:'<a href="/b.zip">자막</a>',episode:5},
    {title:'작품 1화~3화',html:'<a href="/b.zip">자막</a>',episode:2},
    {title:'작품',html:'<a href="/all.zip">1~12화</a><a href="/3.zip">3화</a><a href="/f.zip">폰트</a><a href="/d.zip">자막 다운로드</a><a href="/4.zip">4화</a>',episode:3},
    {title:'작품 3화',html:'<a href="/d.zip">자막 다운로드</a>',episode:5},
    {title:'카구야 님은 고백받고 싶어 12화 자막(完)',html:'<a href="/k.zip">카구야 님은 고백받고 싶어.zip</a>',episode:5},
    {title:'타코피의 원죄 6화',html:'<a href="/t.zip">Takopii_no _Genzai_ 01~06.zip 0.06MB</a>',episode:3},
    {title:'무리무리 10화',html:'<a href="/m.zip">무리무리.zip 0.17MB</a>',episode:3},
    {title:'무리무리 10화',html:'<a href="/m.zip">무리무리.zip 0.02MB</a>',episode:3},
    {title:'어떤 암부의 소녀공서 2화 자막',html:'<a href="/a.zip">어떤 암부의 소녀공서 2화 [이전 화 포함].zip 2.62MB</a>',episode:1},
    {title:'골든 카무이 5기 13화 자막',html:'<a href="/g.zip">Golden Kamuy - 62.zip</a><a href="/13.srt">13화 SRT</a>',episode:13},
    {title:'이세계 유유자적 농가',html:'<a href="/n.zip">이세계 유유자적 농가 1-12 SubsPlease</a><a href="/n.zip">중복</a>',episode:4},
    {title:'클레바테스',html:'<a href="/s2.zip">클레바테스 S2 1-4 SubsPlease</a><a href="/s1.zip">클레바테스 1-12 SubsPlease</a><a href="/v2.zip">자판기 2기 1-12 SubsPlease</a>',episode:1,season:1},
    {title:'클레바테스',html:'<a href="/s2.zip">클레바테스 S2 1-4 SubsPlease</a><a href="/s1.zip">클레바테스 1-12 SubsPlease</a><a href="/v2.zip">자판기 2기 1-12 SubsPlease</a>',episode:1,season:2},
    {title:'작품',html:'<a href="/13.ass">13화</a><a href="/14.ass">14화</a>',episode:1,offsets:[12],allowOffset:true,season:2},
    {title:'작품',html:'<a href="https://drive.google.com/drive/folders/abc">자막 폴더</a>',episode:2}
  ].map(input=>{const post={title:input.title,html:input.html,url:'https://fixture.test/post'};return {input,expected:context.rules.communityLinks(post,input.episode,{offsets:input.offsets||[],allowOffset:Boolean(input.allowOffset),season:input.season??null})}}),
  subtitleTracks:trackSets.map(tracks=>({tracks,expected:(subtitleSource.track(tracks,'ja') || subtitleSource.track(tracks,'en'))?.label ?? null})),
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
// 번역 프롬프트: the user's rules in the local models' requests and the APIs' system prompt.
const ruleSets = [{extra:'EXTRA RULE'},{custom:'CUSTOM RULES'},{extra:'둘째 규칙',custom:'첫째 규칙\n- 의역'}];
result.localRules = ['gemma-4-e4b','aya-expanse-8b','Qwen3-8B','Hy-MT2-7B','HY-MT1.5-7B','ja-ko-vn'].flatMap((model,i) => (i < 3 ? ruleSets : ruleSets.slice(2)).map(rules => {
  const source='（創太）お姉ちゃん、ただいま！', kind=localContext.rules.modelKind({id:model,file:model,label:model}), body=localContext.rules.request(source,[],kind,['前の台詞'],[],rules);
  return {model,kind,source,before:'前の台詞',extra:rules.extra||'',custom:rules.custom||'',prompt:body.messages.map(m=>m.content).join('\u001e')};
}));
result.cloudRules = [false,true].flatMap(wrapped => (wrapped ? ruleSets.slice(2) : ruleSets).map(rules => ({wrapped,extra:rules.extra||'',custom:rules.custom||'',
  expected:vm.runInNewContext(systemBody+';system({rules},wrapped)',{wrapped,rules,characterTerms:()=>[],TRANSLATION_RULES:translationRules})})));
// 효과음·화자 이름 빼고 번역 (desktop tests/drop-captions.test.cjs and more).
const { dropCaptions } = require(path.join(desktop, 'electron/subtitle-translator.cjs'));
result.dropCaptions = ['（創太）えっ？','（男の子）こんにちは\n（拍手）','（ため息）','（ドアの開く音）','そうだね（笑い）','創太：行くぞ','[laughs]','(SIGHS) Okay.','SOTA: Hey there.','Note: this works',
  'あっ','うう…','くっ','はあ…','あはは','ふふっ','Ugh!','Hahaha','うん','ええ','ううん','えっ','（どうしよう）','♪～','（足音）\n行こう','[Door opens] Come in.','KYOKO: (sighs) Fine.',
  'ホリ：（笑）そう？','（小声で）ねえ','Mr. Smith: Hello','(in English) Thank you','［拍手］ありがとう','うふふ…\nそれで？','Hmm...','Oh!','Ahh','Grr','Ow!'].map(source=>({source,expected:dropCaptions(source)}));
// 자막 싱크 맞추기 (subtitle-sync.cjs): retiming, the lines that are speech, the fit score and the segments kept.
const sync = require(path.join(desktop, 'electron/subtitle-sync.cjs'));
const pairs = cues => cues.map(cue => [cue[0], cue[1]]);
const syncVtt = 'WEBVTT\n\n1\n00:00:04.000 --> 00:00:07.500 line:85%\n12:00 시에\n\n00:01.000 --> 00:02.000\n둘째\n';
const syncSegments = [{from:0,shift:-1},{from:90,shift:89.25}];
const syncAss = '[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:07.20,0:00:10.64,Default,,0,0,0,,{\\an8}안녕, 0:00:01.00\nComment: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,메모\nDialogue: 0,0:01:58.50,0:02:00.00,Default,,0,0,0,,b\nStyle: Default,Arial,60\n';
result.syncRetime = [
  {kind:'vtt',text:syncVtt,offset:-3.09,scale:1,segments:null},{kind:'vtt',text:'00:00:10.000 --> 00:00:20.000\nx',offset:1,scale:1.001,segments:null},
  {kind:'vtt',text:'WEBVTT\n\n00:00:10.000 --> 00:00:12.000\na\n\n00:01:58.500 --> 00:02:00.000\nb\n',offset:89.25,scale:1,segments:syncSegments},
  {kind:'ass',text:syncAss,offset:-3.2,scale:1,segments:null},{kind:'ass',text:syncAss,offset:0,scale:1,segments:syncSegments},{kind:'ass',text:syncAss,offset:2.5,scale:1.0427,segments:null}
].map(c=>({...c,expected:c.kind==='vtt'?sync.retimeVtt(c.text,c.offset,c.scale,c.segments):sync.retimeAss(c.text,c.offset,c.scale,c.segments)}));
const stampAt = i => `00:${String(Math.floor(i / 60)).padStart(2, '0')}:${String(i % 60).padStart(2, '0')}.000`;
const dialogueLines = Array.from({ length: 60 }, (_, i) => `${stampAt(i * 2)} --> ${stampAt(i * 2 + 1)}\n${['（竜児）こんなもん…', 'つっかまえた～', '（拍手）', '♪ 歌詞', '{\\an8}看板', 'ハァ…'][i % 6]}`);
const assAt = t => `0:${String(Math.floor(t / 60)).padStart(2, '0')}:${String(t % 60).padStart(2, '0')}.00`;
const assRows = Array.from({ length: 60 }, (_, i) => `Dialogue: 0,${assAt(10 + i * 2)},${assAt(11 + i * 2)},${['Default', 'OP Romaji', 'ED English-furigana', 'Signs', 'Default-Alt', 'Typeset'][i % 6]},,0,0,0,,${i % 6 === 5 ? '{\\pos(10,10)}Sign text' : `line ${i}`}`);
const assHead = '[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n';
result.syncDialogue = [`WEBVTT\n\n${dialogueLines.join('\n\n')}\n`, `WEBVTT\n\n${dialogueLines.slice(0, 6).join('\n\n')}\n`].map(text=>({text,expected:sync.vttCues(sync.dialogueVtt(text))}));
result.syncSpeech = [assHead+assRows.join('\n')+'\n', assHead+assRows.slice(0,12).join('\n')+'\n'].map(text=>({text,expected:pairs(sync.assSpeech(text))}));
let syncSeed = 11; const syncRandom = () => (syncSeed = (syncSeed * 16807) % 2147483647) / 2147483647;
const syncRound = value => Math.round(value * 1000) / 1000;
const syncCues = []; for (let t = 1; t < 1200; t += 2 + syncRandom() * 6) syncCues.push([syncRound(t), syncRound(t + 1 + syncRandom() * 2)]);
const syncTurn = syncCues.find(([start]) => start >= 600)[0];
const syncSpeechTimes = syncCues.map(([s, e]) => [s + (s >= 600 ? 3 : 0), e + (s >= 600 ? 3 : 0)]);
const syncScore = parts => sync.fitScore(syncCues, syncSpeechTimes, sync.mover(0, 1, parts));
const syncTruth = [{ from: 0, shift: 0 }, { from: syncTurn, shift: 3 }];
const syncStray = [{ from: 0, shift: 0 }, { from: 300, shift: 60 }, { from: 360, shift: -60 }, { from: 420, shift: 0.1 }, { from: syncTurn, shift: 3 }];
result.syncKept = {cues:syncCues,speech:syncSpeechTimes,cases:[[syncStray,null],[syncTruth,[{startTime:90,endTime:180}]],[syncTruth,[{startTime:510,endTime:598}]]].map(([segments,chapters])=>({segments,chapters,expected:sync.keptSegments(segments,syncCues,chapters,syncScore)}))};
result.syncFit = [[[[9,11],[19,21]],[[10,12],[11,13],[20,22]],0,1],[[[9,11],[19,21]],[[10,12],[11,13],[20,22]],1,1],[[[9,11],[19,21]],[[10,12],[11,13],[20,22]],500,1],[syncCues.slice(0,25),syncSpeechTimes.slice(0,25),0,1],[syncCues.slice(0,25),syncSpeechTimes.slice(0,25),1.5,1.001]]
  .map(([cues,speech,offset,scale])=>({cues,speech,offset,scale,merged:sync.merged(speech),expected:sync.fitScore(cues,sync.merged(speech),sync.mover(offset,scale))}));
const target=path.join(root,'iosApp/LilacAnimeTests/Fixtures/desktop-oracle.json');
fs.writeFileSync(target,JSON.stringify(result,null,2)+'\n');
const kotlin = `package com.lilac.anime.shared\n\n// Generated by iosApp/scripts/desktop-oracle.cjs from desktop source.\ninternal val desktopOracle = kotlinx.serialization.json.Json.parseToJsonElement("""\n${JSON.stringify(result)}\n""").jsonObject\n`;
fs.writeFileSync(path.join(root,'shared/src/commonTest/kotlin/com/lilac/anime/shared/DesktopOracleFixture.kt'),kotlin.replace('internal val','import kotlinx.serialization.json.jsonObject\n\ninternal val'));
console.log(`Wrote desktop oracle (${result.communityFiles.length+result.reanime.length+result.tmdbQueries.length+result.tmdbSiblings.length+result.seasonNames.length+result.titles.length+result.community.length+result.subtitleTracks.length+result.korean.length+result.saved.length+result.metadata.length+result.regions.length+result.svelte.length+result.localPrompts.length+result.localKinds.length+result.localClean.length+result.localTerms.length+1} cases)`);
