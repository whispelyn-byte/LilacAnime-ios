#include "LilacLocalAI.h"
#include <llama/llama.h>
#include <atomic>
#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

struct LilacModel {
    llama_model *model = nullptr;
    llama_context *context = nullptr;
    std::atomic<bool> cancelled{false};
    std::string error;
};
static std::once_flag backend;
extern "C" LilacModel *lilac_model_open(const char *path, int context, int threads) {
    std::call_once(backend, [] { llama_backend_init(); });
    auto *state = new LilacModel();
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 99;
    state->model = llama_model_load_from_file(path, mp);
    if (!state->model) { delete state; return nullptr; }
    auto cp = llama_context_default_params();
    cp.n_ctx = std::max(512, context);
    cp.n_batch = 512;
    cp.n_threads = cp.n_threads_batch = std::max(1, threads);
    state->context = llama_init_from_model(state->model, cp);
    if (!state->context) { llama_model_free(state->model); delete state; return nullptr; }
    return state;
}
extern "C" void lilac_model_close(LilacModel *state) {
    if (!state) return;
    llama_free(state->context); llama_model_free(state->model); delete state;
}
extern "C" void lilac_cancel(LilacModel *state) { if (state) state->cancelled = true; }
extern "C" const char *lilac_error(LilacModel *state) { return state ? state->error.c_str() : "GGUF model could not be loaded"; }
extern "C" void lilac_string_free(char *text) { free(text); }
extern "C" char *lilac_generate(LilacModel *state, const char *prompt, int max_tokens, float temperature, float top_p, int top_k, float repetition) {
    if (!state || !prompt) return nullptr;
    state->cancelled = false; state->error.clear();
    const char *chat_template = llama_model_chat_template(state->model, nullptr);
    llama_chat_message message{"user", prompt};
    std::vector<char> formatted(strlen(prompt) + 4096);
    int written = llama_chat_apply_template(chat_template, &message, 1, true, formatted.data(), (int)formatted.size());
    if (written < 0) { state->error = "This model chat template is not supported by llama.cpp b5046"; return nullptr; }
    if (written >= (int)formatted.size()) {
        formatted.resize(written + 1);
        written = llama_chat_apply_template(chat_template, &message, 1, true, formatted.data(), (int)formatted.size());
    }
    if (written < 0) { state->error = "Chat template formatting failed"; return nullptr; }
    formatted.resize(written + 1); formatted[written] = 0;
    prompt = formatted.data();
    const auto *vocab = llama_model_get_vocab(state->model);
    int count = -llama_tokenize(vocab, prompt, (int)strlen(prompt), nullptr, 0, true, true);
    if (count <= 0 || count + max_tokens >= (int)llama_n_ctx(state->context)) {
        state->error = "Prompt exceeds the configured context; reduce context cues or increase context size"; return nullptr;
    }
    std::vector<llama_token> tokens(count);
    llama_tokenize(vocab, prompt, (int)strlen(prompt), tokens.data(), count, true, true);
    llama_kv_self_clear(state->context);
    for (int start = 0; start < count; start += 512) {
        if (state->cancelled) { state->error = "Cancelled"; return nullptr; }
        auto batch = llama_batch_get_one(tokens.data() + start, std::min(512, count - start));
        if (llama_decode(state->context, batch) != 0) { state->error = "Prompt decoding failed"; return nullptr; }
    }
    auto *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(64, repetition, 0, 0));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(top_p, 1));
    if (temperature <= 0) llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    else {
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }
    std::string output;
    for (int i = 0; i < max_tokens && !state->cancelled; ++i) {
        auto token = llama_sampler_sample(sampler, state->context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        std::vector<char> piece(256);
        int size = llama_token_to_piece(vocab, token, piece.data(), (int)piece.size(), 0, true);
        if (size < 0) { piece.resize(-size); size = llama_token_to_piece(vocab, token, piece.data(), (int)piece.size(), 0, true); }
        if (size > 0) output.append(piece.data(), size);
        if (llama_decode(state->context, llama_batch_get_one(&token, 1)) != 0) { state->error = "Generation decoding failed"; break; }
    }
    llama_sampler_free(sampler);
    if (state->cancelled) state->error = "Cancelled";
    if (!state->error.empty()) return nullptr;
    return strdup(output.c_str());
}
