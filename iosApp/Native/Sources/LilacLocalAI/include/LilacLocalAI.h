#pragma once
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct LilacModel LilacModel;
LilacModel *lilac_model_open(const char *path, int context, int threads);
void lilac_model_close(LilacModel *model);
char *lilac_generate(LilacModel *model, const char *prompt, int max_tokens, float temperature, float top_p, int top_k, float repetition);
void lilac_cancel(LilacModel *model);
const char *lilac_error(LilacModel *model);
void lilac_string_free(char *text);
#ifdef __cplusplus
}
#endif
