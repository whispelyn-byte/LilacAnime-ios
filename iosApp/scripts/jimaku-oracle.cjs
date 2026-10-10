// Generates fixtures by executing the actual desktop functions, without editing that repo.
const fs = require('fs'), path = require('path'), vm = require('vm');
const desktop = path.resolve(process.argv[2]);
const main = fs.readFileSync(path.join(desktop, 'electron/main.cjs'), 'utf8');
const extract = name => {
  const start = main.search(new RegExp('(?:async )?function ' + name + '\\('));
  if (start < 0) throw new Error('Missing desktop function: ' + name);
  const line = main.slice(start, main.indexOf('\n', start)).trimEnd();
  return line.endsWith('}') ? line : main.slice(start, main.indexOf('\n}', start) + 2);
};
const helpers = ['hasHangul', 'hasSeasonMark', 'NOT_ANIME', 'COMMUNITY_TRAILING_EPISODE'].map(name => main.split(/\r?\n/).find(line => line.startsWith('const ' + name + '=')) || '').join('\n');
const functions = ['simpleTitle', 'communitySeason', 'titleSeason', 'jimakuFiles', 'jimakuEpisodeScore', 'jimakuRelease', 'jimakuLikeness', 'jimakuEpisodeFiles'].map(extract).join('\n');
const file = (name, size = 0) => ({name, size, url: 'https://jimaku.cc/entry/123/download/' + encodeURIComponent(name)});
const sets = [
  {title:'Anime',episode:3,files:['Anime S01E03.srt','Anime E03.ass','Anime 03.ass','Anime 02.ass'].map(x=>file(x))},
  {title:'Anime 2기',episode:3,files:['[Other] Anime S02E03.furigana.ass','[Chosen] Anime S02E03.srt','[Chosen] Anime S01E03.srt','[Chosen] Anime S02E04.srt'].map(x=>file(x)),preferred:'[Chosen] Anime S02E02.srt'},
  {title:'Movie',episode:1,files:['Movie.srt','Movie.ja.ass','Movie 03.ass'].map(x=>file(x))},
  {title:'Anime',episode:3,files:['Anime 01-12.ass','Anime E03.srt','Anime S01E01-S01E12.ass','Anime 04-12.ass'].map(x=>file(x))},
  {title:'Anime',episode:3,files:['Anime 03.zip','Anime 03.sami','Anime 03.vtt','Anime 03.ttml'].map(x=>file(x))},
  {title:'Anime',episode:3,files:[file('A 03.srt',100),file('B 03.srt',1000),file('C 03.srt',500)]},
  {title:'Anime',episode:3,files:['[Chosen] Anime 03.SRT','[Other] Anime 03.furigana.ass'].map(x=>file(x)),preferred:'[Chosen] Anime 02.srt'},
  {title:'Anime',episode:7,files:['Anime S02E03.ass','Anime 02.srt'].map(x=>file(x))}
];
const names = ['Anime S02E03.ass','Anime E03.ass','Anime ep03.srt','Anime episode03.srt','Anime 03화.smi','Anime 03.ass','Anime 01-12.ass','Anime S01E01-S01E12.ass','Anime 04-12.ass','Anime 03a.ass','Movie.ass','Anime S01E03-03.ass','Anime 003話.srt','Anime 03-02.ass'];
(async () => {
  const context = {jimakuAnilistId:async()=>1,jimakuEntryId:async()=>123,
    cheerio:require(path.join(desktop,'node_modules/cheerio')),absoluteUrl:(url,base)=>new URL(url,base).href,
    providerFetch:async()=>context.files.map(file=>`<div class="entry" data-extra='${JSON.stringify(file).replace(/'/g,'&#39;')}'></div>`).join('')};
  vm.createContext(context);
  vm.runInContext(helpers + '\n' + functions + '\nconst JIMAKU_WEB="https://jimaku.cc",JIMAKU_FORMATS={ass:100,ssa:96,srt:90,vtt:86,smi:84,sami:84};globalThis.rules={jimakuEpisodeFiles,jimakuEpisodeScore}', context);
  const scores = names.flatMap(name=>[1,2,3,12].map(episode=>({name,episode,expected:context.rules.jimakuEpisodeScore(name,episode)})));
  const ranks = [];
  for (const input of sets) { context.files=input.files; ranks.push({...input,expected:(await context.rules.jimakuEpisodeFiles({title:input.title},input.episode,input.preferred||'')).map(x=>x.name)}); }
  const data = JSON.stringify({scores,ranks});
  const output = path.resolve(__dirname,'../../shared/src/commonTest/kotlin/com/lilac/anime/shared/JimakuOracleData.kt');
  fs.writeFileSync(output,'package com.lilac.anime.shared\n// Generated from desktop main.cjs by jimaku-oracle.cjs.\ninternal object JimakuOracleData { val json = """'+data+'""" }\n');
  console.log(`Generated ${scores.length} score and ${ranks.length} ranking cases from desktop code`);
})().catch(error=>{console.error(error);process.exitCode=1});
