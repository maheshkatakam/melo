// JNI bridge between Melo and whisper.cpp.
//
// The built-in model is streamed straight from the APK's assets (no copy on disk); a
// downloaded model is loaded from its file. Progress and
// cancellation go through atomics so the Kotlin side can poll/cancel from any thread.

#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <string>
#include <vector>

#include "whisper.h"

#define TAG "MeloWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

struct MeloContext {
    whisper_context *ctx = nullptr;
    std::atomic<bool> abort{false};
    std::atomic<int> progress{0};
};

size_t asset_read(void *ctx, void *output, size_t read_size) {
    int n = AAsset_read(static_cast<AAsset *>(ctx), output, read_size);
    return n < 0 ? 0 : static_cast<size_t>(n);
}

bool asset_eof(void *ctx) {
    return AAsset_getRemainingLength64(static_cast<AAsset *>(ctx)) <= 0;
}

void asset_close(void *ctx) {
    AAsset_close(static_cast<AAsset *>(ctx));
}

bool on_abort(void *user_data) {
    return static_cast<MeloContext *>(user_data)->abort.load();
}

bool on_encoder_begin(whisper_context *, whisper_state *, void *user_data) {
    return !static_cast<MeloContext *>(user_data)->abort.load();
}

void on_progress(whisper_context *, whisper_state *, int progress, void *user_data) {
    static_cast<MeloContext *>(user_data)->progress.store(progress);
}

MeloContext *from_handle(jlong handle) {
    return reinterpret_cast<MeloContext *>(handle);
}

jbyteArray to_bytes(JNIEnv *env, const char *text) {
    if (text == nullptr) text = "";
    const auto len = static_cast<jsize>(strlen(text));
    jbyteArray out = env->NewByteArray(len);
    if (out != nullptr && len > 0) {
        env->SetByteArrayRegion(out, 0, len, reinterpret_cast<const jbyte *>(text));
    }
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_maheshkatakam_melo_lyrics_ai_WhisperNative_initFromAsset(
        JNIEnv *env, jobject, jobject asset_manager, jstring asset_path) {
    const char *path = env->GetStringUTFChars(asset_path, nullptr);
    AAssetManager *manager = AAssetManager_fromJava(env, asset_manager);
    AAsset *asset = manager ? AAssetManager_open(manager, path, AASSET_MODE_STREAMING) : nullptr;
    if (asset == nullptr) {
        LOGW("Could not open model asset %s", path);
        env->ReleaseStringUTFChars(asset_path, path);
        return 0;
    }
    env->ReleaseStringUTFChars(asset_path, path);

    whisper_model_loader loader{};
    loader.context = asset;
    loader.read = asset_read;
    loader.eof = asset_eof;
    loader.close = asset_close; // whisper closes the asset when it is done loading

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    whisper_context *ctx = whisper_init_with_params(&loader, cparams);
    if (ctx == nullptr) {
        LOGW("whisper_init_with_params failed");
        return 0;
    }
    auto *melo = new MeloContext();
    melo->ctx = ctx;
    LOGI("Model loaded. %s", whisper_print_system_info());
    return reinterpret_cast<jlong>(melo);
}

JNIEXPORT jlong JNICALL
Java_com_maheshkatakam_melo_lyrics_ai_WhisperNative_initFromFile(JNIEnv *env, jobject, jstring file_path) {
    const char *path = env->GetStringUTFChars(file_path, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(file_path, path);
    if (ctx == nullptr) {
        LOGW("whisper_init_from_file_with_params failed");
        return 0;
    }
    auto *melo = new MeloContext();
    melo->ctx = ctx;
    LOGI("Model loaded from file. %s", whisper_print_system_info());
    return reinterpret_cast<jlong>(melo);
}

JNIEXPORT void JNICALL
Java_com_maheshkatakam_melo_lyrics_ai_WhisperNative_free(JNIEnv *, jobject, jlong handle) {
    MeloContext *melo = from_handle(handle);
    if (melo == nullptr) return;
    if (melo->ctx != nullptr) whisper_free(melo->ctx);
    delete melo;
}

JNIEXPORT jint JNICALL
Java_com_maheshkatakam_melo_lyrics_ai_WhisperNative_transcribe(
        JNIEnv *env, jobject, jlong handle, jfloatArray samples, jint threads, jboolean translate,
        jstring language, jstring prompt, jint beam_size, jint max_len, jboolean retries) {
    MeloContext *melo = from_handle(handle);
    if (melo == nullptr || melo->ctx == nullptr) return -100;

    melo->abort.store(false);
    melo->progress.store(0);

    const jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    if (data == nullptr) return -101;

    // Copies: whisper keeps the pointers for the whole run.
    std::string lang = "auto";
    if (language != nullptr) {
        const char *l = env->GetStringUTFChars(language, nullptr);
        if (l != nullptr && l[0] != '\0') lang = l;
        env->ReleaseStringUTFChars(language, l);
    }
    std::string initial_prompt;
    if (prompt != nullptr) {
        const char *p = env->GetStringUTFChars(prompt, nullptr);
        if (p != nullptr) initial_prompt = p;
        env->ReleaseStringUTFChars(prompt, p);
    }

    whisper_full_params params = whisper_full_default_params(
            beam_size > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    if (beam_size > 1) params.beam_search.beam_size = beam_size;
    params.n_threads = threads;
    params.translate = translate == JNI_TRUE;
    params.language = lang.c_str();
    params.detect_language = false;
    if (!initial_prompt.empty()) params.initial_prompt = initial_prompt.c_str();
    params.no_context = true;          // avoids repetition loops across windows
    // Temperature fallback: when whisper isn't sure about a stretch it retries with more and
    // more randomness. For the large model on songs that produces junk in random languages and
    // scripts (and takes far longer), so Melo turns it off there. Tested on a Telugu film song:
    // with retries the large model wrote nonsense; without them it wrote the actual lyrics.
    // The small model is the opposite: without retries it gets stuck repeating one syllable.
    if (retries != JNI_TRUE) params.temperature_inc = 0.0f;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.suppress_blank = true;
    params.suppress_nst = true;        // drop music symbols and other non-speech tokens
    params.token_timestamps = true;    // needed for short, lyric-sized lines
    params.max_len = max_len;          // in bytes: Indian scripts need ~3 bytes per letter
    params.split_on_word = true;

    params.progress_callback = on_progress;
    params.progress_callback_user_data = melo;
    params.abort_callback = on_abort;
    params.abort_callback_user_data = melo;
    params.encoder_begin_callback = on_encoder_begin;
    params.encoder_begin_callback_user_data = melo;

    const int rc = whisper_full(melo->ctx, params, data, n);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    if (rc == 0) melo->progress.store(100);
    return rc;
}

JNIEXPORT void JNICALL
Java_com_maheshkatakam_melo_lyrics_ai_WhisperNative_cancel(JNIEnv *, jobject, jlong handle) {
    MeloContext *melo = from_handle(handle);
    if (melo != nullptr) melo->abort.store(true);
}

JNIEXPORT jint JNICALL
Java_com_maheshkatakam_melo_lyrics_ai_WhisperNative_progress(JNIEnv *, jobject, jlong handle) {
    MeloContext *melo = from_handle(handle);
    return melo == nullptr ? 0 : melo->progress.load();
}

JNIEXPORT jint JNICALL
Java_com_maheshkatakam_melo_lyrics_ai_WhisperNative_segmentCount(JNIEnv *, jobject, jlong handle) {
    MeloContext *melo = from_handle(handle);
    return melo == nullptr ? 0 : whisper_full_n_segments(melo->ctx);
}

JNIEXPORT jbyteArray JNICALL
Java_com_maheshkatakam_melo_lyrics_ai_WhisperNative_segmentText(JNIEnv *env, jobject, jlong handle, jint i) {
    MeloContext *melo = from_handle(handle);
    return to_bytes(env, melo == nullptr ? "" : whisper_full_get_segment_text(melo->ctx, i));
}

JNIEXPORT jlong JNICALL
Java_com_maheshkatakam_melo_lyrics_ai_WhisperNative_segmentStartMs(JNIEnv *, jobject, jlong handle, jint i) {
    MeloContext *melo = from_handle(handle);
    return melo == nullptr ? 0 : static_cast<jlong>(whisper_full_get_segment_t0(melo->ctx, i)) * 10;
}

JNIEXPORT jlong JNICALL
Java_com_maheshkatakam_melo_lyrics_ai_WhisperNative_segmentEndMs(JNIEnv *, jobject, jlong handle, jint i) {
    MeloContext *melo = from_handle(handle);
    return melo == nullptr ? 0 : static_cast<jlong>(whisper_full_get_segment_t1(melo->ctx, i)) * 10;
}

JNIEXPORT jfloat JNICALL
Java_com_maheshkatakam_melo_lyrics_ai_WhisperNative_segmentNoSpeechProb(JNIEnv *, jobject, jlong handle, jint i) {
    MeloContext *melo = from_handle(handle);
    return melo == nullptr ? 0.f : whisper_full_get_segment_no_speech_prob(melo->ctx, i);
}

// Language probabilities averaged over 30-second windows starting at the given seconds.
// Songs often open with a long instrumental, so listening only to the start (what whisper
// does on its own) easily picks the wrong language. Returns one value per language id.
JNIEXPORT jfloatArray JNICALL
Java_com_maheshkatakam_melo_lyrics_ai_WhisperNative_languageProbs(
        JNIEnv *env, jobject, jlong handle, jfloatArray samples, jint threads, jintArray offsets_sec) {
    MeloContext *melo = from_handle(handle);
    const int n_lang = whisper_lang_max_id() + 1;
    jfloatArray out = env->NewFloatArray(n_lang);
    if (melo == nullptr || melo->ctx == nullptr || out == nullptr) return out;
    melo->abort.store(false);

    const jsize n = env->GetArrayLength(samples);
    const jsize n_off = env->GetArrayLength(offsets_sec);
    std::vector<jint> offsets(n_off);
    env->GetIntArrayRegion(offsets_sec, 0, n_off, offsets.data());
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    if (data == nullptr) return out;

    std::vector<float> sum(n_lang, 0.f), probs(n_lang, 0.f);
    int used = 0;
    for (jint off : offsets) {
        if (melo->abort.load()) break;
        const long start = static_cast<long>(off) * WHISPER_SAMPLE_RATE;
        if (start < 0 || start >= n) continue;
        const int len = static_cast<int>(std::min<long>(30L * WHISPER_SAMPLE_RATE, n - start));
        if (len < WHISPER_SAMPLE_RATE * 5) continue;
        if (whisper_pcm_to_mel(melo->ctx, data + start, len, threads) != 0) continue;
        if (whisper_lang_auto_detect(melo->ctx, 0, threads, probs.data()) < 0) continue;
        for (int i = 0; i < n_lang; i++) sum[i] += probs[i];
        used++;
    }
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    if (used > 0) {
        for (float &v : sum) v /= static_cast<float>(used);
    }
    env->SetFloatArrayRegion(out, 0, n_lang, sum.data());
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_maheshkatakam_melo_lyrics_ai_WhisperNative_languageCode(JNIEnv *env, jobject, jint id) {
    const char *code = whisper_lang_str(id);
    return env->NewStringUTF(code == nullptr ? "" : code);
}

JNIEXPORT jstring JNICALL
Java_com_maheshkatakam_melo_lyrics_ai_WhisperNative_detectedLanguage(JNIEnv *env, jobject, jlong handle) {
    MeloContext *melo = from_handle(handle);
    if (melo == nullptr) return env->NewStringUTF("");
    const int id = whisper_full_lang_id(melo->ctx);
    const char *code = id >= 0 ? whisper_lang_str(id) : "";
    return env->NewStringUTF(code == nullptr ? "" : code);
}

} // extern "C"
