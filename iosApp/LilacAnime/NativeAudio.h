#pragma once
#include <stdbool.h>
#ifdef __OBJC__
#import "NativeWebKit.h"
#endif
/// The User-Agent of FFmpeg reading the player's proxy for 자막 싱크 or Whisper: served behind the player (HLSProxy).
#define LILAC_BACKGROUND_AGENT "LilacAnime-Background"
/// The seek bar preview's reader: served behind the player, and nothing is fetched ahead of it (HLSProxy).
#define LILAC_PREVIEW_AGENT "LilacAnime-Preview"
#ifdef __cplusplus
extern "C" {
#endif
int LilacDecodeAudio(const char *source, const char *destination, bool (*cancelled)(void *), void *opaque, char **error);
void LilacAudioFreeError(char *error);
/// A bounded 16 kHz mono float chunk of the episode, at its actual presentation time.
int LilacWhisperAudio(const char *source, const char *headers, const char *destination, double start, double seconds,
                      bool (*cancelled)(void *), void *opaque, char **error);
/// 자막 싱크 맞추기: an episode's dialogue level every 10 ms (dB, float32) written to destination — the centre of its mix
/// (dialoguenhance, as the desktop's ffmpeg does; the plain mono mix when the filter cannot run) at 16 kHz, 300–3400 Hz.
/// source: a file path or an http(s) stream, headers its request headers (one "Name: value" line each, CRLF-ended). progress gets 0–1 against
/// duration (seconds, 0 when unknown). Returns 0, or -1 with *error set.
int LilacSpeechLevels(const char *source, const char *headers, const char *destination, double duration,
                      bool (*cancelled)(void *), void (*progress)(void *, double), void *opaque, char **error);
/// 재생 막대 미리보기: an episode kept open to decode one picture at a time (a file path or an http(s) stream, headers as
/// above). Open returns NULL on failure (error, when given, freed with LilacAudioFreeError).
typedef struct LilacFrameReader LilacFrameReader;
LilacFrameReader *LilacFrameReaderOpen(const char *source, const char *headers, char **error);
/// The picture at seconds (one up to 2.5 s before it will do), scaled to fit maxWidth x maxHeight, as RGBX rows of
/// width * 4 bytes in *rgba (freed with LilacFrameFree). 0 on success.
int LilacFrameReaderRead(LilacFrameReader *reader, double seconds, int maxWidth, int maxHeight, unsigned char **rgba, int *width, int *height,
                         bool (*cancelled)(void *), void *opaque);
void LilacFrameReaderClose(LilacFrameReader *reader);
void LilacFrameFree(unsigned char *rgba);
#ifdef __cplusplus
}
#endif
