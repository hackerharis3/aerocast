#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <ctime>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "dsp_biquad_eq.h"
#include "codec_framers.h"
#include "chirp_calibrator.h"

#define LOG_TAG "AirMusicNativeEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {
constexpr size_t kFrameBytes = 1408; // 352 stereo 16-bit PCM samples @ 44,100 Hz
constexpr size_t kSampleRate = 44100;
constexpr size_t kChannels = 2;
constexpr size_t kBytesPerSecond = kSampleRate * kChannels * sizeof(int16_t);
constexpr size_t kRingSeconds = 60;
constexpr size_t kMaxPackets = (kBytesPerSecond * kRingSeconds) / kFrameBytes; // 7,517 packets

struct AudioPacketSlot {
    uint8_t pcm[kFrameBytes];
    int64_t monotonicTimestampMs;
    uint64_t sequenceNumber;
};

class LockFreeDelayRingBuffer {
public:
    LockFreeDelayRingBuffer() : slots_(kMaxPackets), writeIndex_(0), latestRmsDb_(-96.0f), lastNonSilentMs_(0) {}

    void reset() {
        std::lock_guard<std::mutex> lock(mutex_);
        writeIndex_.store(0, std::memory_order_release);
        latestRmsDb_.store(-96.0f, std::memory_order_relaxed);
    }

    int64_t nowMonotonicMs() const {
        struct timespec ts {};
        clock_gettime(CLOCK_MONOTONIC, &ts);
        return static_cast<int64_t>(ts.tv_sec) * 1000LL + (ts.tv_nsec / 1000000LL);
    }

    void pushFrame(const uint8_t* data, size_t len) {
        if (len != kFrameBytes) return;

        const auto* samples = reinterpret_cast<const int16_t*>(data);
        const size_t numSamples = kFrameBytes / sizeof(int16_t);
        double sumSquares = 0.0;
        bool hasNonZero = false;

        for (size_t i = 0; i < numSamples; ++i) {
            int16_t s = samples[i];
            if (s != 0) hasNonZero = true;
            double norm = static_cast<double>(s) / 32768.0;
            sumSquares += norm * norm;
        }

        const int64_t nowMs = nowMonotonicMs();
        if (hasNonZero) {
            lastNonSilentMs_.store(nowMs, std::memory_order_relaxed);
        }

        double rms = std::sqrt(sumSquares / static_cast<double>(numSamples));
        float db = (rms > 1e-5) ? static_cast<float>(20.0 * std::log10(rms)) : -96.0f;
        latestRmsDb_.store(db, std::memory_order_relaxed);

        uint64_t seq = writeIndex_.fetch_add(1, std::memory_order_acq_rel);
        AudioPacketSlot& slot = slots_[seq % kMaxPackets];
        std::memcpy(slot.pcm, data, kFrameBytes);
        slot.monotonicTimestampMs = nowMs;
        slot.sequenceNumber = seq;
    }

    bool readDelayedFrame(uint64_t requestedSeq, int32_t effectiveDelayMs, uint8_t* outBuf) const {
        uint64_t currentWrite = writeIndex_.load(std::memory_order_acquire);
        if (requestedSeq >= currentWrite) return false;
        if (currentWrite - requestedSeq >= kMaxPackets) return false;

        const AudioPacketSlot& slot = slots_[requestedSeq % kMaxPackets];
        if (slot.sequenceNumber != requestedSeq) return false;

        const int64_t targetPlayTimeMs = slot.monotonicTimestampMs + effectiveDelayMs;
        if (nowMonotonicMs() < targetPlayTimeMs) {
            return false;
        }

        std::memcpy(outBuf, slot.pcm, kFrameBytes);
        return true;
    }

    uint64_t currentSequence() const { return writeIndex_.load(std::memory_order_acquire); }
    float latestRmsDb() const { return latestRmsDb_.load(std::memory_order_relaxed); }
    int64_t lastNonSilentMs() const { return lastNonSilentMs_.load(std::memory_order_relaxed); }

private:
    mutable std::mutex mutex_;
    std::vector<AudioPacketSlot> slots_;
    std::atomic<uint64_t> writeIndex_;
    std::atomic<float> latestRmsDb_;
    std::atomic<int64_t> lastNonSilentMs_;
};

LockFreeDelayRingBuffer gRingBuffer;
std::mutex gDspMutex;
std::unordered_map<std::string, airmusic::dsp::SinkDspProcessor> gSinkProcessors;
airmusic::codecs::Mp3FrameEncoder gMp3Encoder;

std::string jstringToStdString(JNIEnv* env, jstring jstr) {
    if (!jstr) return "default";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    if (!chars) return "default";
    std::string res(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return res;
}
} // namespace

extern "C" JNIEXPORT void JNICALL
Java_app_airmusic_modern_nativebridge_NativeAudioEngine_nativeResetEngine(
        JNIEnv* /* env */, jobject /* thiz */) {
    gRingBuffer.reset();
    LOGI("Phase 3 Native DSP + 60s Ring Buffer Reset");
}

extern "C" JNIEXPORT void JNICALL
Java_app_airmusic_modern_nativebridge_NativeAudioEngine_nativePushPcmFrame(
        JNIEnv* env, jobject /* thiz */, jbyteArray pcmChunk, jint length) {
    if (pcmChunk == nullptr || length != static_cast<jint>(kFrameBytes)) return;
    jbyte* rawBytes = env->GetByteArrayElements(pcmChunk, nullptr);
    if (rawBytes != nullptr) {
        gRingBuffer.pushFrame(reinterpret_cast<const uint8_t*>(rawBytes), static_cast<size_t>(length));
        env->ReleaseByteArrayElements(pcmChunk, rawBytes, JNI_ABORT);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_airmusic_modern_nativebridge_NativeAudioEngine_nativeReadDelayedFrame(
        JNIEnv* env, jobject /* thiz */, jlong sequenceNumber, jint effectiveDelayMs, jbyteArray outBuffer) {
    if (outBuffer == nullptr || env->GetArrayLength(outBuffer) < static_cast<jsize>(kFrameBytes)) {
        return JNI_FALSE;
    }
    uint8_t temp[kFrameBytes];
    bool ready = gRingBuffer.readDelayedFrame(
            static_cast<uint64_t>(sequenceNumber),
            static_cast<int32_t>(effectiveDelayMs),
            temp);
    if (ready) {
        env->SetByteArrayRegion(outBuffer, 0, static_cast<jsize>(kFrameBytes), reinterpret_cast<const jbyte*>(temp));
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_airmusic_modern_nativebridge_NativeAudioEngine_nativeReadProcessedSinkPacket(
        JNIEnv* env,
        jobject /* thiz */,
        jstring sinkId,
        jlong sequenceNumber,
        jint effectiveDelayMs,
        jint codecMode,
        jbyteArray outBuffer) {
    if (outBuffer == nullptr) return 0;
    const jsize maxOutLen = env->GetArrayLength(outBuffer);
    if (maxOutLen < static_cast<jsize>(kFrameBytes)) return 0;

    uint8_t pcmFrame[kFrameBytes];
    if (!gRingBuffer.readDelayedFrame(static_cast<uint64_t>(sequenceNumber), effectiveDelayMs, pcmFrame)) {
        return 0;
    }

    std::string id = jstringToStdString(env, sinkId);
    {
        std::lock_guard<std::mutex> lock(gDspMutex);
        gSinkProcessors[id].processPcm1408(reinterpret_cast<int16_t*>(pcmFrame), 352);
    }

    if (codecMode == 1) { // ALAC (AirPlay 352-frame packet)
        uint8_t alacPacket[airmusic::codecs::AlacPacketFramer::kAlacVerbatimPacketBytes];
        size_t alacBytes = airmusic::codecs::AlacPacketFramer::encode352StereoFrame(
                reinterpret_cast<const int16_t*>(pcmFrame),
                alacPacket,
                sizeof(alacPacket));
        if (alacBytes > 0 && maxOutLen >= static_cast<jsize>(alacBytes)) {
            env->SetByteArrayRegion(outBuffer, 0, static_cast<jsize>(alacBytes), reinterpret_cast<const jbyte*>(alacPacket));
            return static_cast<jint>(alacBytes);
        }
        return 0;
    } else if (codecMode == 2) { // MP3 320 kbps
        std::vector<uint8_t> mp3Buf(static_cast<size_t>(maxOutLen));
        size_t mp3Bytes = gMp3Encoder.encode352StereoFrame(
                reinterpret_cast<const int16_t*>(pcmFrame),
                mp3Buf.data(),
                mp3Buf.size());
        if (mp3Bytes > 0) {
            env->SetByteArrayRegion(outBuffer, 0, static_cast<jsize>(mp3Bytes), reinterpret_cast<const jbyte*>(mp3Buf.data()));
            return static_cast<jint>(mp3Bytes);
        }
        return 0;
    } else { // Raw 16-bit Stereo LPCM / WAV body (1,408 bytes)
        env->SetByteArrayRegion(outBuffer, 0, static_cast<jsize>(kFrameBytes), reinterpret_cast<const jbyte*>(pcmFrame));
        return static_cast<jint>(kFrameBytes);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_airmusic_modern_nativebridge_NativeAudioEngine_nativeConfigureSinkDsp(
        JNIEnv* env,
        jobject /* thiz */,
        jstring sinkId,
        jint volumePercent,
        jboolean muted,
        jboolean stereoToMono,
        jboolean eqEnabled,
        jboolean limiterEnabled) {
    std::string id = jstringToStdString(env, sinkId);
    std::lock_guard<std::mutex> lock(gDspMutex);
    auto& proc = gSinkProcessors[id];
    proc.setVolumePercent(static_cast<int>(volumePercent), muted == JNI_TRUE);
    proc.setStereoToMono(stereoToMono == JNI_TRUE);
    proc.setEqEnabled(eqEnabled == JNI_TRUE);
    proc.setLimiterEnabled(limiterEnabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_app_airmusic_modern_nativebridge_NativeAudioEngine_nativeSetSinkEqBandGain(
        JNIEnv* env,
        jobject /* thiz */,
        jstring sinkId,
        jint bandIndex,
        jfloat gainDb) {
    std::string id = jstringToStdString(env, sinkId);
    std::lock_guard<std::mutex> lock(gDspMutex);
    gSinkProcessors[id].setBandGainDb(static_cast<size_t>(bandIndex), static_cast<float>(gainDb));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_airmusic_modern_nativebridge_NativeAudioEngine_nativeBuildEndlessWavHeader(
        JNIEnv* env,
        jobject /* thiz */,
        jint sampleRate,
        jint channels) {
    uint8_t header[airmusic::codecs::WavHeaderFramer::kWavHeaderBytes];
    airmusic::codecs::WavHeaderFramer::writeEndlessWavHeader(
            header,
            static_cast<uint32_t>(sampleRate),
            static_cast<uint16_t>(channels));
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(sizeof(header)));
    if (arr != nullptr) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(sizeof(header)), reinterpret_cast<const jbyte*>(header));
    }
    return arr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_airmusic_modern_nativebridge_NativeAudioEngine_nativeGenerateCalibrationChirpPcm(
        JNIEnv* env,
        jobject /* thiz */) {
    constexpr size_t kStereoSamples = airmusic::calibration::ChirpSyncCalibrator::kChirpMonoSamples * 2;
    std::vector<int16_t> pcm(kStereoSamples);
    airmusic::calibration::ChirpSyncCalibrator::generateStereoChirpPcm(
            pcm.data(),
            airmusic::calibration::ChirpSyncCalibrator::kChirpMonoSamples);
    const jsize byteLen = static_cast<jsize>(kStereoSamples * sizeof(int16_t));
    jbyteArray arr = env->NewByteArray(byteLen);
    if (arr != nullptr) {
        env->SetByteArrayRegion(arr, 0, byteLen, reinterpret_cast<const jbyte*>(pcm.data()));
    }
    return arr;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_airmusic_modern_nativebridge_NativeAudioEngine_nativeEstimateChirpLatencyMs(
        JNIEnv* env,
        jobject /* thiz */,
        jshortArray micMonoPcm) {
    if (micMonoPcm == nullptr) return 0;
    const jsize numSamples = env->GetArrayLength(micMonoPcm);
    jshort* samples = env->GetShortArrayElements(micMonoPcm, nullptr);
    if (!samples) return 0;

    int32_t offsetMs = airmusic::calibration::ChirpSyncCalibrator::estimateLatencyMs(
            reinterpret_cast<const int16_t*>(samples),
            static_cast<size_t>(numSamples));
    env->ReleaseShortArrayElements(micMonoPcm, samples, JNI_ABORT);
    return static_cast<jint>(offsetMs);
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_airmusic_modern_nativebridge_NativeAudioEngine_nativeGetWriteSequence(
        JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jlong>(gRingBuffer.currentSequence());
}

extern "C" JNIEXPORT jfloat JNICALL
Java_app_airmusic_modern_nativebridge_NativeAudioEngine_nativeGetLatestRmsDb(
        JNIEnv* /* env */, jobject /* thiz */) {
    return gRingBuffer.latestRmsDb();
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_airmusic_modern_nativebridge_NativeAudioEngine_nativeGetLastNonSilentTimestampMs(
        JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jlong>(gRingBuffer.lastNonSilentMs());
}
