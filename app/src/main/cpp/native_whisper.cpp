#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <string>
#include <mutex>
#include <vector>

#include "whisper.h"

#define LOG_TAG "OfflineAssistantWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static std::mutex context_mutex;
static whisper_context *cached_context = nullptr;
static std::string cached_model_path;
static int inference_threads = 4;

static whisper_context *context_for_model(const char *model_path) {
    if (cached_context != nullptr && cached_model_path == model_path) {
        return cached_context;
    }
    if (cached_context != nullptr) {
        whisper_free(cached_context);
        cached_context = nullptr;
        cached_model_path.clear();
    }
    whisper_context_params context_params = whisper_context_default_params();
    cached_context = whisper_init_from_file_with_params(model_path, context_params);
    if (!cached_context) {
        throw std::runtime_error("failed to load whisper model");
    }
    cached_model_path = model_path;
    return cached_context;
}

static uint16_t read_u16_le(const uint8_t *p) {
    return static_cast<uint16_t>(p[0]) | (static_cast<uint16_t>(p[1]) << 8);
}

static uint32_t read_u32_le(const uint8_t *p) {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}

static std::vector<uint8_t> read_file(const char *path) {
    FILE *file = fopen(path, "rb");
    if (!file) {
        throw std::runtime_error("cannot open audio file");
    }
    fseek(file, 0, SEEK_END);
    const long size = ftell(file);
    fseek(file, 0, SEEK_SET);
    if (size <= 0) {
        fclose(file);
        throw std::runtime_error("empty audio file");
    }
    std::vector<uint8_t> bytes(static_cast<size_t>(size));
    const size_t read = fread(bytes.data(), 1, bytes.size(), file);
    fclose(file);
    if (read != bytes.size()) {
        throw std::runtime_error("failed to read audio file");
    }
    return bytes;
}

static std::vector<float> read_pcm16_wav_as_float(const char *path) {
    const std::vector<uint8_t> bytes = read_file(path);
    if (bytes.size() < 44 ||
        memcmp(bytes.data(), "RIFF", 4) != 0 ||
        memcmp(bytes.data() + 8, "WAVE", 4) != 0) {
        throw std::runtime_error("expected RIFF/WAVE audio");
    }

    size_t offset = 12;
    uint16_t audio_format = 0;
    uint16_t channels = 0;
    uint32_t sample_rate = 0;
    uint16_t bits_per_sample = 0;
    const uint8_t *data = nullptr;
    uint32_t data_size = 0;

    while (offset + 8 <= bytes.size()) {
        const uint8_t *chunk = bytes.data() + offset;
        const uint32_t chunk_size = read_u32_le(chunk + 4);
        const size_t payload_offset = offset + 8;
        if (payload_offset + chunk_size > bytes.size()) {
            throw std::runtime_error("invalid WAV chunk size");
        }
        if (memcmp(chunk, "fmt ", 4) == 0) {
            if (chunk_size < 16) {
                throw std::runtime_error("invalid WAV fmt chunk");
            }
            audio_format = read_u16_le(bytes.data() + payload_offset);
            channels = read_u16_le(bytes.data() + payload_offset + 2);
            sample_rate = read_u32_le(bytes.data() + payload_offset + 4);
            bits_per_sample = read_u16_le(bytes.data() + payload_offset + 14);
        } else if (memcmp(chunk, "data", 4) == 0) {
            data = bytes.data() + payload_offset;
            data_size = chunk_size;
        }
        offset = payload_offset + chunk_size + (chunk_size % 2);
    }

    if (audio_format != 1 || channels != 1 || sample_rate != 16000 || bits_per_sample != 16 || data == nullptr) {
        throw std::runtime_error("expected PCM16 mono 16kHz WAV");
    }

    const size_t sample_count = data_size / 2;
    std::vector<float> samples(sample_count);
    for (size_t i = 0; i < sample_count; ++i) {
        const int16_t sample = static_cast<int16_t>(read_u16_le(data + i * 2));
        samples[i] = static_cast<float>(sample) / 32768.0f;
    }
    return samples;
}

static std::string transcribe_file(const char *model_path, const char *audio_path) {
    std::vector<float> samples = read_pcm16_wav_as_float(audio_path);
    if (samples.empty()) {
        throw std::runtime_error("no audio samples");
    }

    whisper_context *context = context_for_model(model_path);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = "ru";
    params.n_threads = inference_threads;
    params.no_context = true;
    params.single_segment = false;

    LOGI("Running whisper_full on %zu samples", samples.size());
    const int result = whisper_full(context, params, samples.data(), static_cast<int>(samples.size()));
    if (result != 0) {
        throw std::runtime_error("whisper_full failed");
    }

    std::string transcript;
    const int segment_count = whisper_full_n_segments(context);
    for (int i = 0; i < segment_count; ++i) {
        const char *segment = whisper_full_get_segment_text(context, i);
        if (segment != nullptr) {
            transcript += segment;
        }
    }
    return transcript;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_offlineassistant_app_asr_JniWhisperNativeEngine_nativeSetThreadCount(
    JNIEnv *,
    jobject,
    jint thread_count
) {
    std::lock_guard<std::mutex> guard(context_mutex);
    inference_threads = static_cast<int>(thread_count);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_offlineassistant_app_asr_JniWhisperNativeEngine_nativePrepare(
    JNIEnv *env,
    jobject,
    jstring model_path
) {
    const char *model = env->GetStringUTFChars(model_path, nullptr);
    try {
        std::lock_guard<std::mutex> guard(context_mutex);
        context_for_model(model);
        env->ReleaseStringUTFChars(model_path, model);
    } catch (const std::exception &error) {
        env->ReleaseStringUTFChars(model_path, model);
        jclass exception_class = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(exception_class, error.what());
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_offlineassistant_app_asr_JniWhisperNativeEngine_nativeRelease(
    JNIEnv *,
    jobject
) {
    std::lock_guard<std::mutex> guard(context_mutex);
    if (cached_context != nullptr) {
        whisper_free(cached_context);
        cached_context = nullptr;
        cached_model_path.clear();
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_offlineassistant_app_asr_JniWhisperNativeEngine_nativeTranscribe(
    JNIEnv *env,
    jobject,
    jstring model_path,
    jstring audio_path
) {
    const char *model = env->GetStringUTFChars(model_path, nullptr);
    const char *audio = env->GetStringUTFChars(audio_path, nullptr);
    try {
        std::lock_guard<std::mutex> guard(context_mutex);
        const std::string transcript = transcribe_file(model, audio);
        env->ReleaseStringUTFChars(model_path, model);
        env->ReleaseStringUTFChars(audio_path, audio);
        return env->NewStringUTF(transcript.c_str());
    } catch (const std::exception &error) {
        env->ReleaseStringUTFChars(model_path, model);
        env->ReleaseStringUTFChars(audio_path, audio);
        jclass exception_class = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(exception_class, error.what());
        return nullptr;
    }
}
