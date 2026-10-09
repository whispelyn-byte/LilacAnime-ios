"""Inventory every desktop runtime/build/test file and every preload API.

Hashes identify the exact working tree reviewed, including uncommitted desktop work.
This is a coverage manifest; matching file hashes does not prove behavior parity.
"""
from pathlib import Path
import subprocess, hashlib, json, re, sys

desktop = Path(sys.argv[1]).resolve()
root = Path(__file__).resolve().parents[2]
def git(*args):
    return subprocess.check_output(['git', '-c', 'safe.directory='+desktop.as_posix(), '-C', str(desktop), *args])
shared = 'shared/src/commonMain/kotlin/com/lilac/anime/shared/'
ios = 'iosApp/LilacAnime/'
mapping = {
 'electron/anime-glossary.cjs': [shared+'AnimeGlossary.kt'],
 'electron/catalog-browser.cjs': [shared+'SourceRepository.kt',shared+'DesktopCatalogTaxonomy.kt'],
 'electron/catalog-updates.cjs': [shared+'DesktopCatalogUpdates.kt',shared+'SourceRepository.kt'],
 'electron/download-manager.cjs': [ios+'DownloadStore.swift',ios+'DownloadTransfer.swift',ios+'DesktopDownloads.swift'],
 'electron/flix-proxy.cjs': [ios+'HLSProxy.swift'],
 'electron/local-ai.cjs': [ios+'LocalModelsView.swift',ios+'DesktopModelInstaller.swift',shared+'DesktopTranslationPrompt.kt'],
 'electron/main.cjs': [shared+'SourceRepository.kt',shared+'DesktopSources.kt',shared+'DesktopMetadata.kt',shared+'SubtitleDiscovery.kt',shared+'DesktopCommunity.kt',ios+'PlaybackResolver.swift',ios+'WinPNGReader.swift'],
 'electron/oped-fingerprint.cjs': [shared+'AudioFingerprint.kt',shared+'DesktopAudioFingerprint.kt',ios+'OfflineAnalyzer.swift',ios+'NativeAudio.m'],
 'electron/preload.cjs': [shared+'IosServices.kt',ios+'LibraryStore.swift'],
 'electron/subtitle-store.cjs': [ios+'EpisodeSubtitleStore.swift'],
 'electron/subtitle-translator.cjs': [shared+'CloudTranslator.kt',shared+'DesktopCloudPrompt.kt',ios+'TranslationCoordinator.swift',ios+'CloudSubtitleScheduler.swift'],
 'electron/updater.cjs': [ios+'DesktopUpdater.swift','.github/workflows/ipa.yml'],
 'src/anime-metadata.js': [shared+'DesktopAnimeMetadata.kt',ios+'UIComponents.swift'],
 'src/app.js': [ios+'DesktopShell.swift',ios+'HomeView.swift',ios+'DesktopCatalog.swift',ios+'EpisodePlayerView.swift',ios+'DesktopSubtitlePreparer.swift',ios+'DesktopSubtitlePolicy.swift',ios+'DesktopStreamPolicy.swift'],
 'src/ass-renderer.js': [ios+'MPVEngine.swift',ios+'SubtitleFiles.swift'],
 'src/catalog-browser.js': [ios+'DesktopWorkspace.swift',ios+'CatalogView.swift'],
 'src/catalog-updates.js': [ios+'DesktopRecentUpdates.swift',ios+'HomeView.swift',ios+'DesktopShell.swift'],
 'src/index.html': [ios+'DesktopShell.swift',ios+'DesktopWorkspace.swift',ios+'SettingsView.swift',ios+'EpisodePlayerView.swift'],
 'src/ott.js': [ios+'HomeView.swift',ios+'UIComponents.swift'],
 'src/player.js': [ios+'EpisodePlayerView.swift',ios+'MPVEngine.swift',ios+'PlayerInteraction.swift'],
 'scripts/build-jassub.cjs': ['iosApp/project.yml',ios+'MPVEngine.swift'],
 'scripts/build-windows-icon.cjs': ['iosApp/project.yml'],
}
groups = [
 ('season top search detail linkkfHome linkkfDetail linkkfEpisodes linkkfSchedule linkkfSections linkkfFilterTags linkkfFilter linkkfSearch linkkfExtras linkkfRecordView providerCatalog catalogFacets catalogBrowse catalogUpdates providerSeason providerAiring providerDetail', [shared+'IosServices.kt',shared+'SourceRepository.kt']),
 ('linkkfPlay linkkfResolve providerPlay providerResolve providerSubtitleTracks', [ios+'PlaybackResolver.swift',shared+'DesktopSources.kt']),
 ('coverData', [ios+'UIComponents.swift',ios+'DownloadStore.swift']),
 ('opEdSkip clearOpEd onOpEdStatus', [ios+'OfflineAnalyzer.swift',shared+'AniSkip.kt']),
 ('downloadMedia onDownloadProgress downloads addDownload cancelDownload resumeDownload removeDownload playDownload openDownloadsFolder downloadRoot chooseDownloadRoot clearDownloads onDownloadsChanged', [ios+'DownloadStore.swift',ios+'DownloadTransfer.swift']),
 ('findSubtitle anissiaMakers remoteSubtitle', [shared+'SubtitleDiscovery.kt',shared+'AnissiaDiscovery.kt',ios+'SubtitleFiles.swift',ios+'WinPNGReader.swift']),
 ('savedSubtitles saveSubtitle removeSavedSubtitle subtitleCacheUsage cleanSubtitleCache clearSubtitleCache', [ios+'EpisodeSubtitleStore.swift']),
 ('mpvStatus mpvPlay setPlayerFullscreen', [ios+'MPVEngine.swift',ios+'EpisodePlayerView.swift']),
 ('chooseVideo chooseSubtitle chooseSubtitleDetails defaultSubtitleFont chooseFont', [ios+'EpisodePlayerView.swift',ios+'SettingsView.swift',ios+'SubtitleFiles.swift']),
 ('setWindowTheme setWindowButtons', [ios+'LilacAnimeApp.swift',ios+'DesktopShell.swift']),
 ('updateState checkUpdate downloadUpdate installUpdate onUpdateState updateNotes', [ios+'DesktopUpdater.swift','.github/workflows/ipa.yml']),
 ('tmdbKey resolveTitles animeOverview titleVariants catalogKoreanSearch catalogIndexState activateCatalog onCatalogIndexState setTmdbKey', [ios+'DesktopCatalog.swift',shared+'DesktopMetadata.kt',shared+'TmdbTitleResolver.kt']),
 ('geminiSettings setGeminiSettings translateSubtitle cancelTranslation jumpTranslation prepareEpisodeSubtitle onTranslateProgress', [ios+'SettingsView.swift',ios+'LibraryStore.swift',ios+'TranslationCoordinator.swift',ios+'DesktopSubtitlePreparer.swift']),
 ('jimakuList jimakuDownload', [shared+'JimakuRules.kt',shared+'SubtitleDiscovery.kt',ios+'DesktopSubtitlePreparer.swift']),
 ('installLocalModel cancelLocalModelInstall addLocalModelFile removeLocalModel onLocalModelProgress', [ios+'DesktopModelInstaller.swift',ios+'LocalModelsView.swift']),
 ('openExternal', [ios+'EpisodePlayerView.swift',ios+'SettingsView.swift',ios+'DesktopUpdater.swift']),
]
api_map = {name: targets for names,targets in groups for name in names.split()}
apis = re.findall(r'^  (\w+):', (desktop/'electron/preload.cjs').read_text(encoding='utf-8'), re.M)
assert apis, 'No preload APIs found'
assert not set(apis)-api_map.keys(), 'Unmapped APIs: '+str(set(apis)-api_map.keys())
files = git('ls-files').decode().splitlines()
inventory = []
for file in files:
    path = desktop/file
    if not path.is_file(): continue
    if not (file.startswith(('src/','electron/','scripts/','tests/','.github/workflows/')) or file in ['package.json','package-lock.json','electron-builder.yml']): continue
    targets = mapping.get(file)
    category = 'runtime'
    if targets is None and file.startswith('src/') and file.endswith('.css'):
        targets = [ios+'DesktopShell.swift',ios+'UIComponents.swift',ios+'PlayerSettingsPanel.swift',ios+'EpisodePlayerView.swift']; category='native-layout'
    elif targets is None and file.startswith('tests/'):
        targets = ['iosApp/LilacAnimeTests','iosApp/LilacAnimeUITests','shared/src/commonTest']; category='validation'
    elif targets is None and (file.startswith('.github/') or file in ['package.json','package-lock.json','electron-builder.yml']):
        targets = ['iosApp/project.yml','shared/build.gradle.kts','.github/workflows/kmp.yml','.github/workflows/ipa.yml'];category='platform-build'
    elif targets is None and path.suffix.lower() in ['.png','.ico','.icns','.svg']:
        targets = ['iosApp/project.yml',ios+'UIComponents.swift'];category='native-assets'
    assert targets is not None, 'Unmapped source: '+file
    for target in targets: assert (root/target).exists(), 'Missing target: '+target
    data = path.read_bytes().replace(b'\r\n',b'\n')
    committed = git('show','HEAD:'+file).replace(b'\r\n',b'\n')
    inventory.append(dict(desktop=file,sha256=hashlib.sha256(data).hexdigest(),uncommitted=data!=committed,category=category,ios=targets))
manifest = dict(desktopCommit=git('rev-parse','HEAD').decode().strip(),desktopVersion=json.loads((desktop/'package.json').read_text())['version'], files=inventory,preloadAPIs=[dict(name=name,ios=api_map[name]) for name in apis])
(root/'docs/desktop-audit.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(f"Inventoried {len(inventory)} files and {len(apis)} APIs; desktop {manifest['desktopVersion']} at {manifest['desktopCommit'][:7]}.")
