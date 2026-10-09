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
