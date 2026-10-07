#pragma once
#include <stdbool.h>
#ifdef __cplusplus
extern "C" {
#endif
int LilacDecodeAudio(const char *source, const char *destination, bool (*cancelled)(void *), void *opaque, char **error);
void LilacAudioFreeError(char *error);
#ifdef __cplusplus
}
#endif
