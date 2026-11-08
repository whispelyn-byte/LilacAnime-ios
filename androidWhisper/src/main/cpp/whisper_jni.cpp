// JNI over whisper.cpp for com.lilac.anime.whisper.Whisper: a model, and chunks of 16 kHz mono audio transcribed.
#include <jni.h>
#include <atomic>
#include <cstring>
#include <string>
#include "whisper.h"

struct Model { whisper_context *context = nullptr; std::atomic<bool> abort{false}; };

// NewStringUTF takes modified UTF-8: text goes through bytes and String(bytes, "UTF-8").
static jstring utf8(JNIEnv *env, const std::string &value) {
    jbyteArray bytes = env->NewByteArray((jsize)value.size());
    env->SetByteArrayRegion(bytes, 0, (jsize)value.size(), reinterpret_cast<const jbyte *>(value.data()));
    jclass stringClass = env->FindClass("java/lang/String");
    jmethodID constructor = env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto result = (jstring)env->NewObject(stringClass, constructor, bytes, charset);
    env->DeleteLocalRef(bytes); env->DeleteLocalRef(charset); env->DeleteLocalRef(stringClass);
    return result;
}

#define FN(name) Java_com_lilac_anime_whisper_Whisper_##name
extern "C" {
JNIEXPORT jlong JNICALL FN(nativeOpen)(JNIEnv *env, jobject, jstring path) {
    const char *chars = env->GetStringUTFChars(path, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *context = whisper_init_from_file_with_params(chars, params);
    env->ReleaseStringUTFChars(path, chars);
    if (!context) return 0;
    auto *model = new Model();
    model->context = context;
    return reinterpret_cast<jlong>(model);
}
JNIEXPORT void JNICALL FN(nativeClose)(JNIEnv *, jobject, jlong handle) {
    auto *model = reinterpret_cast<Model *>(handle);
    if (!model) return;
    whisper_free(model->context);
    delete model;
}
JNIEXPORT void JNICALL FN(nativeAbort)(JNIEnv *, jobject, jlong handle) {
    auto *model = reinterpret_cast<Model *>(handle);
    if (model) model->abort = true;
}
static bool aborted(void *data) { return static_cast<Model *>(data)->abort.load(); }
/** Segments as "start\tend\tnoSpeech\ttext" (seconds); null when transcription failed or was stopped. */
JNIEXPORT jobjectArray JNICALL FN(nativeTranscribe)(JNIEnv *env, jobject, jlong handle, jfloatArray samples, jstring language, jint threads) {
    auto *model = reinterpret_cast<Model *>(handle);
    if (!model) return nullptr;
    model->abort = false;
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    const char *lang = env->GetStringUTFChars(language, nullptr);
    std::string code(lang ? lang : "ja");
    env->ReleaseStringUTFChars(language, lang);
    params.language = code.c_str();
    params.n_threads = threads;
    params.translate = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.no_context = true;
    params.suppress_blank = true;
    params.abort_callback = aborted;
    params.abort_callback_user_data = model;
    jsize count = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    int result = whisper_full(model->context, params, data, count);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    if (result != 0 || model->abort) return nullptr;
    int segments = whisper_full_n_segments(model->context);
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray output = env->NewObjectArray(segments, stringClass, nullptr);
    for (int i = 0; i < segments; i++) {
        double start = whisper_full_get_segment_t0(model->context, i) / 100.0;
        double end = whisper_full_get_segment_t1(model->context, i) / 100.0;
        std::string line = std::to_string(start) + "\t" + std::to_string(end) + "\t0\t" + whisper_full_get_segment_text(model->context, i);
        jstring value = utf8(env, line);
        env->SetObjectArrayElement(output, i, value);
        env->DeleteLocalRef(value);
    }
    return output;
}
}
