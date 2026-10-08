#include "LilacLocalAI.h"
#include <llama/llama.h>
#include <atomic>
#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>
#include <chrono>
#include "Jinja/parser.h"
#include "Jinja/json.h"

struct LilacModel {
    llama_model *model = nullptr;
    llama_context *context = nullptr;
    std::atomic<bool> cancelled{false};
    std::string error;
    bool gpu = false;
    bool thinking = false;
    int output_tokens = 0;
    double seconds = 0;
};
static std::once_flag backend;
extern "C" LilacModel *lilac_model_open_with_backend(const char *path, int context, int threads, int gpu) {
    std::call_once(backend, [] { llama_backend_init(); });
    auto *state = new LilacModel();
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = gpu ? 99 : 0;
    state->gpu = gpu && llama_supports_gpu_offload();
    state->model = llama_model_load_from_file(path, mp);
    if (!state->model) { delete state; return nullptr; }
    auto cp = llama_context_default_params();
    cp.n_ctx = std::max(512, context);
    cp.offload_kqv = gpu != 0;
    cp.op_offload = gpu != 0;
    cp.n_batch = 512;
    cp.n_threads = cp.n_threads_batch = std::max(1, threads);
    state->context = llama_init_from_model(state->model, cp);
    if (!state->context) { llama_model_free(state->model); delete state; return nullptr; }
    return state;
}
extern "C" LilacModel *lilac_model_open(const char *path, int context, int threads) {
    return lilac_model_open_with_backend(path, context, threads, 1);
}
extern "C" void lilac_set_thinking(LilacModel *state, int enabled) { if (state) state->thinking = enabled != 0; }
extern "C" const char *lilac_backend(LilacModel *state) { return state && state->gpu ? "Metal" : "CPU"; }
extern "C" int lilac_output_tokens(LilacModel *state) { return state ? state->output_tokens : 0; }
extern "C" double lilac_generation_seconds(LilacModel *state) { return state ? state->seconds : 0; }
extern "C" void lilac_model_close(LilacModel *state) {
    if (!state) return;
    llama_free(state->context); llama_model_free(state->model); delete state;
}
extern "C" void lilac_cancel(LilacModel *state) { if (state) state->cancelled = true; }
extern "C" const char *lilac_error(LilacModel *state) { return state ? state->error.c_str() : "GGUF model could not be loaded"; }
extern "C" void lilac_string_free(char *text) { free(text); }
static std::string format_prompt(const char *chat_template, const char *prompt, const char *bos, const char *eos, bool thinking = false) {
    std::string formatted;
        std::string input(prompt);
        const auto separator = input.find('\x1e');
        std::string system = separator == std::string::npos ? "" : input.substr(0, separator);
        std::string user = separator == std::string::npos ? input : input.substr(separator + 1);
        common_json messages = common_json::array();
        if (!system.empty()) messages.push_back(common_json::object({{"role", "system"}, {"content", system}}));
        messages.push_back(common_json::object({{"role", "user"}, {"content", user}}));
        if (chat_template && chat_template[0]) {
            common_json values = common_json::object({
                {"messages", messages}, {"add_generation_prompt", true}, {"enable_thinking", thinking},
                {"bos_token", std::string(bos ? bos : "")}, {"eos_token", std::string(eos ? eos : "")}
            });
            jinja::lexer lexer;
            auto ast = jinja::parse_from_tokens(lexer.tokenize(chat_template));
            jinja::context context(chat_template);
            jinja::global_from_json(context, values, true);
            jinja::runtime runtime(context);
            formatted = jinja::runtime::gather_string_parts(runtime.execute(ast))->as_string().str();
        } else {
            formatted = system + "\n" + user;
        }
    return formatted;
}
extern "C" char *lilac_format_prompt(const char *chat_template, const char *prompt, const char *bos, const char *eos, char **error) {
    if (error) *error = nullptr;
    try { return strdup(format_prompt(chat_template, prompt ? prompt : "", bos, eos).c_str()); }
    catch (const std::exception &failure) { if (error) *error = strdup(failure.what()); return nullptr; }
}
extern "C" char *lilac_generate(LilacModel *state, const char *prompt, int max_tokens, float temperature, float top_p, int top_k, float repetition) {
    if (!state || !prompt) return nullptr;
    state->cancelled = false; state->error.clear(); state->output_tokens = 0;
    const auto started = std::chrono::steady_clock::now();
    const auto *vocab = llama_model_get_vocab(state->model);
    const char *chat_template = llama_model_chat_template(state->model, nullptr);
    auto tokenText = [&](llama_token token) {
        char data[256]; int count = llama_token_to_piece(vocab, token, data, sizeof(data), 0, true);
        return count > 0 ? std::string(data, count) : std::string();
    };
    const auto bos = tokenText(llama_vocab_bos(vocab)), eos = tokenText(llama_vocab_eos(vocab));
    std::string formatted;
    try { formatted = format_prompt(chat_template, prompt, bos.c_str(), eos.c_str(), state->thinking); }
    catch (const std::exception &error) { state->error = std::string("Chat template: ") + error.what(); return nullptr; }
    prompt = formatted.c_str();
    int count = -llama_tokenize(vocab, prompt, (int)strlen(prompt), nullptr, 0, true, true);
    if (count <= 0 || count + max_tokens >= (int)llama_n_ctx(state->context)) {
        state->error = "Prompt exceeds the configured context; reduce context cues or increase context size"; return nullptr;
    }
    std::vector<llama_token> tokens(count);
    llama_tokenize(vocab, prompt, (int)strlen(prompt), tokens.data(), count, true, true);
    llama_memory_clear(llama_get_memory(state->context), true);
    for (int start = 0; start < count; start += 512) {
        if (state->cancelled) { state->error = "Cancelled"; return nullptr; }
        auto batch = llama_batch_get_one(tokens.data() + start, std::min(512, count - start));
        if (llama_decode(state->context, batch) != 0) { state->error = "Prompt decoding failed"; return nullptr; }
    }
    auto *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, repetition, 0, 0));
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
        ++state->output_tokens;
        std::vector<char> piece(256);
        int size = llama_token_to_piece(vocab, token, piece.data(), (int)piece.size(), 0, true);
        if (size < 0) { piece.resize(-size); size = llama_token_to_piece(vocab, token, piece.data(), (int)piece.size(), 0, true); }
        if (size > 0) output.append(piece.data(), size);
        if (llama_decode(state->context, llama_batch_get_one(&token, 1)) != 0) { state->error = "Generation decoding failed"; break; }
    }
    llama_sampler_free(sampler);
    if (state->cancelled) state->error = "Cancelled";
    if (!state->error.empty()) return nullptr;
    state->seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - started).count();
    return strdup(output.c_str());
}
