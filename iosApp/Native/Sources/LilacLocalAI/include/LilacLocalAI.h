#pragma once
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct LilacModel LilacModel;
LilacModel *lilac_model_open(const char *path, int context, int threads);
LilacModel *lilac_model_open_with_backend(const char *path, int context, int threads, int gpu);
const char *lilac_backend(LilacModel *model);
int lilac_output_tokens(LilacModel *model);
double lilac_generation_seconds(LilacModel *model);
void lilac_model_close(LilacModel *model);
char *lilac_generate(LilacModel *model, const char *prompt, int max_tokens, float temperature, float top_p, int top_k, float repetition);
void lilac_cancel(LilacModel *model);
const char *lilac_error(LilacModel *model);
void lilac_string_free(char *text);
char *lilac_format_prompt(const char *chat_template, const char *prompt, const char *bos, const char *eos, char **error);
#ifdef __cplusplus
}
#endif
