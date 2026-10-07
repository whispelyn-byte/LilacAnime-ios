#include "NativeAudio.h"
@import Libavformat;
@import Libavcodec;
@import Libavutil;
@import Libswresample;
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

extern "C" void LilacAudioFreeError(char *error) { free(error); }
extern "C" int LilacDecodeAudio(const char *source, const char *destination, bool (*cancelled)(void *), void *opaque, char **error) {
    AVFormatContext *format = nullptr;
    AVCodecContext *codec = nullptr;
    SwrContext *resampler = nullptr;
    AVFrame *frame = nullptr;
    AVPacket *packet = nullptr;
    FILE *output = nullptr;
    std::string failure;
    int status = 0;
    auto cleanup = [&] {
        if (output) fclose(output);
        av_packet_free(&packet); av_frame_free(&frame);
        swr_free(&resampler); avcodec_free_context(&codec);
        avformat_close_input(&format);
    };
    auto fail = [&](const char *message, int code) {
        char reason[AV_ERROR_MAX_STRING_SIZE] = {};
        if (code < 0) av_strerror(code, reason, sizeof(reason));
        failure = std::string(message) + (reason[0] ? std::string(": ") + reason : "");
    };
    AVDictionary *options = nullptr;
    // Offline analysis reads only locally downloaded media and its AES keys.
    av_dict_set(&options, "protocol_whitelist", "file,crypto,data", 0);
    av_dict_set(&options, "allowed_extensions", "ALL", 0);
    status = avformat_open_input(&format, source, nullptr, &options);
    av_dict_free(&options);
    if (status < 0) { fail("Cannot open downloaded audio", status); cleanup(); *error = strdup(failure.c_str()); return -1; }
    status = avformat_find_stream_info(format, nullptr);
    if (status < 0) { fail("Cannot inspect audio", status); cleanup(); *error = strdup(failure.c_str()); return -1; }
    int stream = av_find_best_stream(format, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (stream < 0) { fail("Audio track not found", stream); cleanup(); *error = strdup(failure.c_str()); return -1; }
    const AVCodec *decoder = avcodec_find_decoder(format->streams[stream]->codecpar->codec_id);
    if (!decoder) { cleanup(); *error = strdup("Audio codec is unavailable"); return -1; }
    codec = avcodec_alloc_context3(decoder);
    if (!codec) { cleanup(); *error = strdup("Cannot allocate audio decoder"); return -1; }
    avcodec_parameters_to_context(codec, format->streams[stream]->codecpar);
    status = avcodec_open2(codec, decoder, nullptr);
    if (status < 0) { fail("Cannot initialize audio decoder", status); cleanup(); *error = strdup(failure.c_str()); return -1; }
    AVChannelLayout mono = AV_CHANNEL_LAYOUT_MONO;
    status = swr_alloc_set_opts2(&resampler, &mono, AV_SAMPLE_FMT_FLT, 8000, &codec->ch_layout, codec->sample_fmt, codec->sample_rate, 0, nullptr);
    if (status < 0 || swr_init(resampler) < 0) { cleanup(); *error = strdup("Cannot initialize mono audio resampling"); return -1; }
    frame = av_frame_alloc(); packet = av_packet_alloc(); output = fopen(destination, "wb");
    if (!frame || !packet || !output) { cleanup(); *error = strdup("Cannot create decoded audio cache"); return -1; }
    auto receive = [&]() -> bool {
        for (;;) {
            int result = avcodec_receive_frame(codec, frame);
            if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) return true;
            if (result < 0) { fail("Audio frame decoding failed", result); return false; }
            int capacity = (int)av_rescale_rnd(swr_get_delay(resampler, codec->sample_rate) + frame->nb_samples, 8000, codec->sample_rate, AV_ROUND_UP);
            std::vector<float> samples(capacity);
            uint8_t *bytes = reinterpret_cast<uint8_t *>(samples.data());
            int count = swr_convert(resampler, &bytes, capacity, const_cast<const uint8_t **>(frame->extended_data), frame->nb_samples);
            av_frame_unref(frame);
            if (count < 0) { fail("Audio resampling failed", count); return false; }
            if (fwrite(samples.data(), sizeof(float), count, output) != (size_t)count) { failure = "Audio cache write failed"; return false; }
        }
    };
    while ((status = av_read_frame(format, packet)) >= 0) {
        if (cancelled && cancelled(opaque)) { failure = "Cancelled"; av_packet_unref(packet); break; }
        if (packet->stream_index == stream) {
            int result = avcodec_send_packet(codec, packet);
            if (result < 0) { fail("Audio packet decoding failed", result); av_packet_unref(packet); break; }
            if (!receive()) { av_packet_unref(packet); break; }
        }
        av_packet_unref(packet);
    }
    if (failure.empty() && status != AVERROR_EOF) fail("Downloaded audio read failed", status);
    if (failure.empty()) {
        avcodec_send_packet(codec, nullptr); receive();
        int remaining = (int)av_rescale_rnd(swr_get_delay(resampler, codec->sample_rate), 8000, codec->sample_rate, AV_ROUND_UP);
        if (failure.empty() && remaining > 0) {
            std::vector<float> samples(remaining);
            uint8_t *bytes = reinterpret_cast<uint8_t *>(samples.data());
            int count = swr_convert(resampler, &bytes, remaining, nullptr, 0);
            if (count > 0 && fwrite(samples.data(), sizeof(float), count, output) != (size_t)count) failure = "Audio cache write failed";
        }
    }
    cleanup();
    if (!failure.empty()) { remove(destination); *error = strdup(failure.c_str()); return -1; }
    return 0;
}
