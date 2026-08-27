#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <jni.h>
#include <string.h>
#include "whisper.h"
#include "ggml.h"

#define UNUSED(x) (void)(x)
#define TAG "WhisperJNI"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

typedef struct {
    JNIEnv *env;
    jobject listener;
    jmethodID on_progress;
    int last_reported;
} progress_ctx;

static int is_chinese_language(const char *language) {
    return language != NULL && (
            strcmp(language, "zh") == 0 ||
            strcmp(language, "chinese") == 0
    );
}

static const char *ZH_INITIAL_PROMPT =
        "以下是普通话句子，请使用中文标点符号。";

static void whisper_android_progress_cb(
        struct whisper_context *ctx,
        struct whisper_state *state,
        int progress,
        void *user_data) {
    UNUSED(ctx);
    UNUSED(state);
    progress_ctx *pctx = (progress_ctx *) user_data;
    if (pctx == NULL || pctx->listener == NULL || pctx->on_progress == NULL) {
        return;
    }
    if (progress < pctx->last_reported + 2 && progress < 100) {
        return;
    }
    pctx->last_reported = progress;
    (*pctx->env)->CallVoidMethod(
            pctx->env, pctx->listener, pctx->on_progress, (jint) progress);
}

JNIEXPORT jlong JNICALL
Java_com_dafei_voicenotes_asr_WhisperLib_00024Companion_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    UNUSED(thiz);
    const char *model_path_chars = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    struct whisper_context *context = whisper_init_from_file_with_params(
            model_path_chars, whisper_context_default_params());
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path_chars);
    return (jlong) context;
}

JNIEXPORT void JNICALL
Java_com_dafei_voicenotes_asr_WhisperLib_00024Companion_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    whisper_free(context);
}

JNIEXPORT void JNICALL
Java_com_dafei_voicenotes_asr_WhisperLib_00024Companion_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads,
        jstring language_str, jfloatArray audio_data, jobject progress_listener) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);

    const char *language = "zh";
    if (language_str != NULL) {
        language = (*env)->GetStringUTFChars(env, language_str, NULL);
    }

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = language;
    params.n_threads = num_threads;
    params.offset_ms = 0;
    params.no_context = false;
    params.single_segment = false;
    if (is_chinese_language(language)) {
        params.initial_prompt = ZH_INITIAL_PROMPT;
        params.carry_initial_prompt = true;
    }

    progress_ctx pctx = {
            .env = env,
            .listener = NULL,
            .on_progress = NULL,
            .last_reported = -10,
    };

    if (progress_listener != NULL) {
        jclass listener_class = (*env)->FindClass(env, "com/dafei/voicenotes/asr/AsrProgressListener");
        if (listener_class != NULL) {
            pctx.listener = (*env)->NewGlobalRef(env, progress_listener);
            pctx.on_progress = (*env)->GetMethodID(env, listener_class, "onProgress", "(I)V");
            params.progress_callback = whisper_android_progress_cb;
            params.progress_callback_user_data = &pctx;
        }
    }

    whisper_reset_timings(context);
    if (whisper_full(context, params, audio_data_arr, audio_data_length) != 0) {
        LOGW("Failed to run whisper_full");
    } else {
        whisper_print_timings(context);
        if (pctx.listener != NULL && pctx.on_progress != NULL && pctx.last_reported < 100) {
            (*env)->CallVoidMethod(env, pctx.listener, pctx.on_progress, (jint) 100);
        }
    }

    if (pctx.listener != NULL) {
        (*env)->DeleteGlobalRef(env, pctx.listener);
    }

    if (language_str != NULL) {
        (*env)->ReleaseStringUTFChars(env, language_str, language);
    }
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
}

JNIEXPORT jint JNICALL
Java_com_dafei_voicenotes_asr_WhisperLib_00024Companion_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_n_segments(context);
}

JNIEXPORT jstring JNICALL
Java_com_dafei_voicenotes_asr_WhisperLib_00024Companion_getTextSegment(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    const char *text = whisper_full_get_segment_text(context, index);
    return (*env)->NewStringUTF(env, text);
}

JNIEXPORT jstring JNICALL
Java_com_dafei_voicenotes_asr_WhisperLib_00024Companion_getSystemInfo(
        JNIEnv *env, jobject thiz) {
    UNUSED(thiz);
    const char *sysinfo = whisper_print_system_info();
    return (*env)->NewStringUTF(env, sysinfo);
}
