#pragma once
#include <stdbool.h>
#ifdef __OBJC__
#import "NativeWebKit.h"
#endif
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
#ifdef __cplusplus
}
#endif
