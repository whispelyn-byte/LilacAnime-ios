// JNI over iOS's LilacLocalAI C API (LilacLocalAI.h), for com.lilac.anime.android.localai.LocalAI.
#include <jni.h>
#include <cstring>
#include <string>
#include "LilacLocalAI.h"

static LilacModel *model(jlong handle) { return reinterpret_cast<LilacModel *>(handle); }
static std::string text(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}
// NewStringUTF takes modified UTF-8: model output (emoji, 4-byte characters) goes through a byte array and String(bytes, UTF-8).
static jstring string(JNIEnv *env, const char *value) {
    if (!value) return nullptr;
    size_t length = strlen(value);
    jbyteArray bytes = env->NewByteArray((jsize)length);
    env->SetByteArrayRegion(bytes, 0, (jsize)length, reinterpret_cast<const jbyte *>(value));
    jclass stringClass = env->FindClass("java/lang/String");
    jmethodID constructor = env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto result = (jstring)env->NewObject(stringClass, constructor, bytes, charset);
    env->DeleteLocalRef(bytes); env->DeleteLocalRef(charset); env->DeleteLocalRef(stringClass);
    return result;
}

#define FN(name) Java_com_lilac_anime_android_localai_LocalAI_##name
extern "C" {
JNIEXPORT jlong JNICALL FN(nativeOpen)(JNIEnv *env, jobject, jstring path, jint context, jint threads, jint gpu) {
    return reinterpret_cast<jlong>(lilac_model_open_with_backend(text(env, path).c_str(), context, threads, gpu));
}
JNIEXPORT void JNICALL FN(nativeClose)(JNIEnv *, jobject, jlong handle) { if (handle) lilac_model_close(model(handle)); }
JNIEXPORT void JNICALL FN(nativeSetThinking)(JNIEnv *, jobject, jlong handle, jboolean on) { lilac_set_thinking(model(handle), on ? 1 : 0); }
JNIEXPORT jstring JNICALL FN(nativeChatTemplate)(JNIEnv *env, jobject, jlong handle) { return string(env, lilac_chat_template(model(handle))); }
JNIEXPORT jstring JNICALL FN(nativeBackend)(JNIEnv *env, jobject, jlong handle) { return string(env, lilac_backend(model(handle))); }
JNIEXPORT jint JNICALL FN(nativeOutputTokens)(JNIEnv *, jobject, jlong handle) { return lilac_output_tokens(model(handle)); }
JNIEXPORT jdouble JNICALL FN(nativeSeconds)(JNIEnv *, jobject, jlong handle) { return lilac_generation_seconds(model(handle)); }
JNIEXPORT void JNICALL FN(nativeCancel)(JNIEnv *, jobject, jlong handle) { if (handle) lilac_cancel(model(handle)); }
JNIEXPORT jstring JNICALL FN(nativeError)(JNIEnv *env, jobject, jlong handle) { return string(env, lilac_error(model(handle))); }
JNIEXPORT void JNICALL FN(nativeSetTranslationLanguage)(JNIEnv *env, jobject, jlong handle, jstring source) {
    if (!source) { lilac_set_translation_language(model(handle), nullptr); return; }
    lilac_set_translation_language(model(handle), text(env, source).c_str());
}
JNIEXPORT jstring JNICALL FN(nativeGenerate)(JNIEnv *env, jobject, jlong handle, jstring prompt, jint maxTokens, jfloat temperature, jfloat topP, jint topK, jfloat repetition) {
    char *result = lilac_generate(model(handle), text(env, prompt).c_str(), maxTokens, temperature, topP, topK, repetition);
    if (!result) return nullptr;
    jstring value = string(env, result);
    lilac_string_free(result);
    return value;
}
}
