#include "NativeAudio.h"
@import Libavformat;
@import Libavcodec;
@import Libavutil;
@import Libswresample;
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

typedef struct {
    AVCodecContext *codec;
    SwrContext *resampler;
    AVFrame *frame;
    FILE *output;
    char failure[512];
    bool (*cancelled)(void *);
    void *opaque;
} AudioDecodeState;

static void fail(AudioDecodeState *state, const char *message, int code) {
    char reason[AV_ERROR_MAX_STRING_SIZE] = {0};
    if (code < 0) av_strerror(code, reason, sizeof(reason));
    snprintf(state->failure, sizeof(state->failure), "%s%s%s", message, reason[0] ? ": " : "", reason);
}
static bool writeSamples(AudioDecodeState *state, const uint8_t **input, int inputCount, int capacity) {
    if (capacity <= 0) return true;
    int16_t *samples = calloc((size_t)capacity, sizeof(int16_t));
    if (!samples) { fail(state, "Cannot allocate audio samples", 0); return false; }
    uint8_t *bytes = (uint8_t *)samples;
    int count = swr_convert(state->resampler, &bytes, capacity, input, inputCount);
    bool success = count >= 0;
    if (count < 0) fail(state, "Audio resampling failed", count);
    else {
        float *normalized = calloc((size_t)(count > 0 ? count : 1), sizeof(float));
        if (!normalized) { fail(state, "Cannot allocate normalized audio samples", 0); success = false; }
        else {
            // Match desktop ffmpeg -f s16le and readInt16LE / 32768 before fingerprinting.
            for (int i = 0; i < count; i++) normalized[i] = samples[i] / 32768.0f;
            if (fwrite(normalized, sizeof(float), (size_t)count, state->output) != (size_t)count) {
                fail(state, "Audio cache write failed", 0); success = false;
            }
            free(normalized);
        }
    }
    free(samples);
    return success;
}
static bool receiveFrames(AudioDecodeState *state) {
    for (;;) {
        if (state->cancelled && state->cancelled(state->opaque)) { fail(state, "Cancelled", 0); return false; }
        int result = avcodec_receive_frame(state->codec, state->frame);
        if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) return true;
        if (result < 0) { fail(state, "Audio frame decoding failed", result); return false; }
        int capacity = (int)av_rescale_rnd(swr_get_delay(state->resampler, state->codec->sample_rate) + state->frame->nb_samples,
                                          8000, state->codec->sample_rate, AV_ROUND_UP);
        bool success = writeSamples(state, (const uint8_t **)state->frame->extended_data, state->frame->nb_samples, capacity);
        av_frame_unref(state->frame);
        if (!success) return false;
    }
}
void LilacAudioFreeError(char *error) { free(error); }
int LilacDecodeAudio(const char *source, const char *destination, bool (*cancelled)(void *), void *opaque, char **error) {
    AVFormatContext *format = NULL;
    AVPacket *packet = NULL;
    AVDictionary *options = NULL;
    AudioDecodeState state = {0};
    state.cancelled = cancelled; state.opaque = opaque;
    if (error) *error = NULL;
    // Only downloaded local media and its local AES keys are readable.
    av_dict_set(&options, "protocol_whitelist", "file,crypto,data", 0);
    av_dict_set(&options, "allowed_extensions", "ALL", 0);
    int status = avformat_open_input(&format, source, NULL, &options);
    av_dict_free(&options);
    if (status < 0) { fail(&state, "Cannot open downloaded audio", status); goto cleanup; }
    status = avformat_find_stream_info(format, NULL);
    if (status < 0) { fail(&state, "Cannot inspect audio", status); goto cleanup; }
    int stream = av_find_best_stream(format, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
    if (stream < 0) { fail(&state, "Audio track not found", stream); goto cleanup; }
    const AVCodec *decoder = avcodec_find_decoder(format->streams[stream]->codecpar->codec_id);
    if (!decoder) { fail(&state, "Audio codec is unavailable", 0); goto cleanup; }
    state.codec = avcodec_alloc_context3(decoder);
    if (!state.codec) { fail(&state, "Cannot allocate audio decoder", 0); goto cleanup; }
    status = avcodec_parameters_to_context(state.codec, format->streams[stream]->codecpar);
    if (status < 0) { fail(&state, "Cannot copy audio parameters", status); goto cleanup; }
    status = avcodec_open2(state.codec, decoder, NULL);
    if (status < 0) { fail(&state, "Cannot initialize audio decoder", status); goto cleanup; }
    AVChannelLayout mono = AV_CHANNEL_LAYOUT_MONO;
    status = swr_alloc_set_opts2(&state.resampler, &mono, AV_SAMPLE_FMT_S16, 8000, &state.codec->ch_layout,
                                state.codec->sample_fmt, state.codec->sample_rate, 0, NULL);
    if (status < 0) { fail(&state, "Cannot allocate audio resampling", status); goto cleanup; }
    status = swr_init(state.resampler);
    if (status < 0) { fail(&state, "Cannot initialize audio resampling", status); goto cleanup; }
    state.frame = av_frame_alloc(); packet = av_packet_alloc(); state.output = fopen(destination, "wb");
    if (!state.frame || !packet || !state.output) { fail(&state, "Cannot create decoded audio cache", 0); goto cleanup; }
    while ((status = av_read_frame(format, packet)) >= 0) {
        if (cancelled && cancelled(opaque)) { fail(&state, "Cancelled", 0); av_packet_unref(packet); break; }
        if (packet->stream_index == stream) {
            int result = avcodec_send_packet(state.codec, packet);
            if (result == AVERROR(EAGAIN)) {
                if (!receiveFrames(&state)) { av_packet_unref(packet); break; }
                result = avcodec_send_packet(state.codec, packet);
            }
            if (result < 0) { fail(&state, "Audio packet decoding failed", result); av_packet_unref(packet); break; }
            if (!receiveFrames(&state)) { av_packet_unref(packet); break; }
        }
        av_packet_unref(packet);
    }
    if (!state.failure[0] && status != AVERROR_EOF) fail(&state, "Downloaded audio read failed", status);
    if (!state.failure[0]) {
        status = avcodec_send_packet(state.codec, NULL);
        if (status < 0 && status != AVERROR_EOF) fail(&state, "Audio decoder drain failed", status);
        if (!state.failure[0]) receiveFrames(&state);
        int remaining = (int)av_rescale_rnd(swr_get_delay(state.resampler, state.codec->sample_rate),
                                           8000, state.codec->sample_rate, AV_ROUND_UP);
        if (!state.failure[0] && remaining > 0) writeSamples(&state, NULL, 0, remaining);
    }
cleanup:
    if (state.output && fclose(state.output) != 0 && !state.failure[0]) fail(&state, "Audio cache flush failed", 0);
    av_packet_free(&packet); av_frame_free(&state.frame);
    swr_free(&state.resampler); avcodec_free_context(&state.codec); avformat_close_input(&format);
    if (state.failure[0]) {
        remove(destination);
        if (error) *error = strdup(state.failure);
        return -1;
    }
    return 0;
}

// MARK: 자막 싱크 맞추기 — the dialogue level of an episode's audio

@import Libavfilter;
#include <math.h>

#define SPEECH_RATE 16000
#define SPEECH_FRAME 160

typedef struct { double b0, b1, b2, a1, a2, x1, x2, y1, y2; } Biquad;
// RBJ cookbook high- and low-pass (Q 0.707) at 16 kHz.
static Biquad biquad(double frequency, bool high) {
    double w = 2 * M_PI * frequency / SPEECH_RATE, alpha = sin(w) / (2 * 0.7071), c = cos(w), a0 = 1 + alpha;
    Biquad f = {0};
    if (high) { f.b0 = (1 + c) / 2 / a0; f.b1 = -(1 + c) / a0; f.b2 = (1 + c) / 2 / a0; }
    else { f.b0 = (1 - c) / 2 / a0; f.b1 = (1 - c) / a0; f.b2 = (1 - c) / 2 / a0; }
    f.a1 = -2 * c / a0; f.a2 = (1 - alpha) / a0;
    return f;
}
static double filterSample(Biquad *f, double x) {
    double y = f->b0 * x + f->b1 * f->x1 + f->b2 * f->x2 - f->a1 * f->y1 - f->a2 * f->y2;
    f->x2 = f->x1; f->x1 = x; f->y2 = f->y1; f->y1 = y;
    return y;
}

typedef struct {
    AVCodecContext *codec;
    SwrContext *resampler;
    AVFilterGraph *graph;
    AVFilterContext *source;
    AVFilterContext *sink;
    AVFrame *frame;
    AVFrame *filtered;
    FILE *output;
    Biquad high, low;
    double energy;
    int count;
    int64_t written;
    double duration;
    double reported;
    char failure[512];
    bool (*cancelled)(void *);
    void (*progress)(void *, double);
    void *opaque;
} SpeechState;

static void speechFail(SpeechState *state, const char *message, int code) {
    char reason[AV_ERROR_MAX_STRING_SIZE] = {0};
    if (code < 0) av_strerror(code, reason, sizeof(reason));
    snprintf(state->failure, sizeof(state->failure), "%s%s%s", message, reason[0] ? ": " : "", reason);
}
static int speechInterrupt(void *opaque) {
    SpeechState *state = opaque;
    return state->cancelled && state->cancelled(state->opaque) ? 1 : 0;
}
// Mono 16 kHz samples: one level (dB) every 10 ms.
static bool speechSamples(SpeechState *state, const float *samples, int count) {
    for (int i = 0; i < count; i++) {
        double value = filterSample(&state->low, filterSample(&state->high, samples[i]));
        state->energy += value * value;
        if (++state->count == SPEECH_FRAME) {
            float level = (float)(10 * log10(state->energy / SPEECH_FRAME + 1e-10));
            if (fwrite(&level, sizeof(float), 1, state->output) != 1) { speechFail(state, "Speech level write failed", 0); return false; }
            state->energy = 0; state->count = 0; state->written++;
        }
    }
    double seconds = (double)state->written * SPEECH_FRAME / SPEECH_RATE;
    if (state->progress && state->duration > 0 && seconds - state->reported >= 5) {
        state->reported = seconds; state->progress(state->opaque, fmin(1, seconds / state->duration));
    }
    return true;
}
// The centre of the stereo mix, where dialogue sits (desktop: dialoguenhance=original=0:enhance=3,pan=mono|c0=FC).
static int speechGraph(SpeechState *state) {
    const AVFilter *source = avfilter_get_by_name("abuffer"), *sink = avfilter_get_by_name("abuffersink");
    if (!source || !sink || !avfilter_get_by_name("dialoguenhance")) return AVERROR_FILTER_NOT_FOUND;
    state->graph = avfilter_graph_alloc();
    if (!state->graph) return AVERROR(ENOMEM);
    char arguments[128];
    snprintf(arguments, sizeof(arguments), "sample_rate=%d:sample_fmt=flt:channel_layout=stereo:time_base=1/%d", SPEECH_RATE, SPEECH_RATE);
    int status = avfilter_graph_create_filter(&state->source, source, "in", arguments, NULL, state->graph);
    if (status < 0) return status;
    status = avfilter_graph_create_filter(&state->sink, sink, "out", NULL, NULL, state->graph);
    if (status < 0) return status;
    AVFilterInOut *outputs = avfilter_inout_alloc(), *inputs = avfilter_inout_alloc();
    if (!outputs || !inputs) { avfilter_inout_free(&outputs); avfilter_inout_free(&inputs); return AVERROR(ENOMEM); }
    outputs->name = av_strdup("in"); outputs->filter_ctx = state->source; outputs->pad_idx = 0; outputs->next = NULL;
    inputs->name = av_strdup("out"); inputs->filter_ctx = state->sink; inputs->pad_idx = 0; inputs->next = NULL;
    status = avfilter_graph_parse_ptr(state->graph, "dialoguenhance=original=0:enhance=3,pan=mono|c0=FC,aformat=sample_fmts=flt:channel_layouts=mono", &inputs, &outputs, NULL);
    avfilter_inout_free(&inputs); avfilter_inout_free(&outputs);
    if (status < 0) return status;
    return avfilter_graph_config(state->graph, NULL);
}
static bool speechDrain(SpeechState *state) {
    for (;;) {
        int result = av_buffersink_get_frame(state->sink, state->filtered);
        if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) return true;
        if (result < 0) { speechFail(state, "Dialogue filter failed", result); return false; }
        bool success = speechSamples(state, (const float *)state->filtered->data[0], state->filtered->nb_samples);
        av_frame_unref(state->filtered);
        if (!success) return false;
    }
}
// One decoded frame (or the flush) as 16 kHz float samples: stereo for the filter, mono without it.
static bool speechConvert(SpeechState *state, const uint8_t **input, int inputCount) {
    int capacity = (int)av_rescale_rnd(swr_get_delay(state->resampler, state->codec->sample_rate) + inputCount, SPEECH_RATE, state->codec->sample_rate, AV_ROUND_UP);
    if (capacity <= 0) return true;
    AVFrame *converted = av_frame_alloc();
    if (!converted) { speechFail(state, "Cannot allocate speech samples", 0); return false; }
    converted->format = AV_SAMPLE_FMT_FLT; converted->sample_rate = SPEECH_RATE; converted->nb_samples = capacity;
    AVChannelLayout stereo = AV_CHANNEL_LAYOUT_STEREO, mono = AV_CHANNEL_LAYOUT_MONO;
    av_channel_layout_copy(&converted->ch_layout, state->graph ? &stereo : &mono);
    bool success = true;
    int status = av_frame_get_buffer(converted, 0);
    if (status < 0) { speechFail(state, "Cannot allocate speech samples", status); success = false; }
    else {
        int count = swr_convert(state->resampler, converted->data, capacity, input, inputCount);
        if (count < 0) { speechFail(state, "Audio resampling failed", count); success = false; }
        else if (count > 0) {
            converted->nb_samples = count; converted->pts = state->written * SPEECH_FRAME + state->count;
            if (state->graph) {
                status = av_buffersrc_add_frame_flags(state->source, converted, AV_BUFFERSRC_FLAG_KEEP_REF);
                if (status < 0) { speechFail(state, "Dialogue filter input failed", status); success = false; }
                else success = speechDrain(state);
            } else success = speechSamples(state, (const float *)converted->data[0], count);
        }
    }
    av_frame_free(&converted);
    return success;
}
static bool speechFrames(SpeechState *state) {
    for (;;) {
        if (state->cancelled && state->cancelled(state->opaque)) { speechFail(state, "Cancelled", 0); return false; }
        int result = avcodec_receive_frame(state->codec, state->frame);
        if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) return true;
        if (result < 0) { speechFail(state, "Audio frame decoding failed", result); return false; }
        bool success = speechConvert(state, (const uint8_t **)state->frame->extended_data, state->frame->nb_samples);
        av_frame_unref(state->frame);
        if (!success) return false;
    }
}
int LilacSpeechLevels(const char *source, const char *headers, const char *destination, double duration,
                      bool (*cancelled)(void *), void (*progress)(void *, double), void *opaque, char **error) {
    AVFormatContext *format = NULL;
    AVPacket *packet = NULL;
    AVDictionary *options = NULL;
    SpeechState state = {0};
    state.cancelled = cancelled; state.progress = progress; state.opaque = opaque; state.duration = duration;
    state.high = biquad(300, true); state.low = biquad(3400, false);
    if (error) *error = NULL;
    format = avformat_alloc_context();
    if (!format) { speechFail(&state, "Cannot allocate audio input", 0); goto cleanup; }
    format->interrupt_callback.callback = speechInterrupt; format->interrupt_callback.opaque = &state;
    // A downloaded episode (its local HLS too) or the stream; the player's headers go only to a remote one.
    av_dict_set(&options, "protocol_whitelist", "file,http,https,tcp,tls,crypto,data", 0);
    av_dict_set(&options, "allowed_extensions", "ALL", 0);
    if (headers && headers[0] && strncmp(source, "http://127.0.0.1", 16) != 0) av_dict_set(&options, "headers", headers, 0);
    if (strncmp(source, "http", 4) == 0) {
        av_dict_set(&options, "reconnect", "1", 0); av_dict_set(&options, "reconnect_streamed", "1", 0);
        av_dict_set(&options, "reconnect_delay_max", "5", 0);
        // A stream that stops sending for 90 s (the desktop's quiet watch) is taken as stuck.
        av_dict_set(&options, "rw_timeout", "90000000", 0);
    }
    int status = avformat_open_input(&format, source, NULL, &options);
    av_dict_free(&options);
    if (status < 0) { speechFail(&state, "Cannot open episode audio", status); goto cleanup; }
    status = avformat_find_stream_info(format, NULL);
    if (status < 0) { speechFail(&state, "Cannot inspect audio", status); goto cleanup; }
    int stream = av_find_best_stream(format, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
    if (stream < 0) { speechFail(&state, "Audio track not found", stream); goto cleanup; }
    for (unsigned i = 0; i < format->nb_streams; i++) if ((int)i != stream) format->streams[i]->discard = AVDISCARD_ALL;
    const AVCodec *decoder = avcodec_find_decoder(format->streams[stream]->codecpar->codec_id);
    if (!decoder) { speechFail(&state, "Audio codec is unavailable", 0); goto cleanup; }
    state.codec = avcodec_alloc_context3(decoder);
    if (!state.codec) { speechFail(&state, "Cannot allocate audio decoder", 0); goto cleanup; }
    status = avcodec_parameters_to_context(state.codec, format->streams[stream]->codecpar);
    if (status < 0) { speechFail(&state, "Cannot copy audio parameters", status); goto cleanup; }
    status = avcodec_open2(state.codec, decoder, NULL);
    if (status < 0) { speechFail(&state, "Cannot initialize audio decoder", status); goto cleanup; }
    // Resampling divides by the source rate.
    if (state.codec->sample_rate <= 0 || state.codec->ch_layout.nb_channels <= 0) { speechFail(&state, "Audio format is unknown", 0); goto cleanup; }
    // The filter takes the stereo mix (any layout is folded down to it first); without it, the plain mono mix.
    if (speechGraph(&state) < 0) { avfilter_graph_free(&state.graph); state.source = NULL; state.sink = NULL; }
    AVChannelLayout stereo = AV_CHANNEL_LAYOUT_STEREO, mono = AV_CHANNEL_LAYOUT_MONO;
    status = swr_alloc_set_opts2(&state.resampler, state.graph ? &stereo : &mono, AV_SAMPLE_FMT_FLT, SPEECH_RATE, &state.codec->ch_layout,
                                state.codec->sample_fmt, state.codec->sample_rate, 0, NULL);
    if (status < 0) { speechFail(&state, "Cannot allocate audio resampling", status); goto cleanup; }
    status = swr_init(state.resampler);
    if (status < 0) { speechFail(&state, "Cannot initialize audio resampling", status); goto cleanup; }
    state.frame = av_frame_alloc(); state.filtered = av_frame_alloc(); packet = av_packet_alloc(); state.output = fopen(destination, "wb");
    if (!state.frame || !state.filtered || !packet || !state.output) { speechFail(&state, "Cannot create speech level file", 0); goto cleanup; }
    while ((status = av_read_frame(format, packet)) >= 0) {
        if (cancelled && cancelled(opaque)) { speechFail(&state, "Cancelled", 0); av_packet_unref(packet); break; }
        if (packet->stream_index == stream) {
            int result = avcodec_send_packet(state.codec, packet);
            if (result == AVERROR(EAGAIN)) {
                if (!speechFrames(&state)) { av_packet_unref(packet); break; }
                result = avcodec_send_packet(state.codec, packet);
            }
            // A damaged packet in a stream is skipped, as a player does.
            if (result < 0 && result != AVERROR_INVALIDDATA) { speechFail(&state, "Audio packet decoding failed", result); av_packet_unref(packet); break; }
            if (!speechFrames(&state)) { av_packet_unref(packet); break; }
        }
        av_packet_unref(packet);
    }
    if (!state.failure[0] && status != AVERROR_EOF) speechFail(&state, cancelled && cancelled(opaque) ? "Cancelled" : "Episode audio read failed", status);
    if (!state.failure[0]) {
        status = avcodec_send_packet(state.codec, NULL);
        if (status < 0 && status != AVERROR_EOF) speechFail(&state, "Audio decoder drain failed", status);
        if (!state.failure[0]) speechFrames(&state);
        if (!state.failure[0]) speechConvert(&state, NULL, 0);
        if (!state.failure[0] && state.graph) {
            status = av_buffersrc_add_frame_flags(state.source, NULL, 0);
            if (status < 0) speechFail(&state, "Dialogue filter flush failed", status);
            else speechDrain(&state);
        }
    }
    if (!state.failure[0] && state.written == 0) speechFail(&state, "No audio was decoded", 0);
cleanup:
    if (state.output && fclose(state.output) != 0 && !state.failure[0]) speechFail(&state, "Speech level flush failed", 0);
    av_packet_free(&packet); av_frame_free(&state.frame); av_frame_free(&state.filtered);
    avfilter_graph_free(&state.graph);
    swr_free(&state.resampler); avcodec_free_context(&state.codec); avformat_close_input(&format);
    if (state.failure[0]) {
        remove(destination);
        if (error) *error = strdup(state.failure);
        return -1;
    }
    return 0;
}
