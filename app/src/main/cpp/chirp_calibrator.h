#pragma once

#include <cmath>
#include <cstdint>
#include <vector>
#include <algorithm>

namespace airmusic::calibration {

/**
 * Mic-Assisted Acoustic Auto-Chirp Calibration Engine:
 * 1. Synthesizes a 44.1 kHz linear swept-sine chirp (1,000 Hz -> 4,500 Hz, Hann-windowed)
 *    spanning 20 AirMusic frames (20 * 352 = 7,040 samples = 159.6 ms).
 * 2. Performs normalized cross-correlation (matched filter) against a microphone capture buffer
 *    to locate the exact acoustic arrival sample and return the speaker latency in milliseconds.
 */
class ChirpSyncCalibrator {
public:
    static constexpr size_t kSampleRateHz = 44100;
    static constexpr size_t kChirpFrames = 20;
    static constexpr size_t kSamplesPerFrame = 352;
    static constexpr size_t kChirpMonoSamples = kChirpFrames * kSamplesPerFrame; // 7,040 samples (~159.6 ms)
    static constexpr double kPi = 3.14159265358979323846;

    static std::vector<float> buildReferenceMonoChirp() {
        std::vector<float> ref(kChirpMonoSamples);
        constexpr double f0 = 1000.0;
        constexpr double f1 = 4500.0;
        const double durationSec = static_cast<double>(kChirpMonoSamples) / static_cast<double>(kSampleRateHz);
        const double kRate = (f1 - f0) / durationSec;

        for (size_t n = 0; n < kChirpMonoSamples; ++n) {
            double t = static_cast<double>(n) / static_cast<double>(kSampleRateHz);
            double phase = 2.0 * kPi * (f0 * t + 0.5 * kRate * t * t);
            double hann = 0.5 * (1.0 - std::cos(2.0 * kPi * static_cast<double>(n) / static_cast<double>(kChirpMonoSamples - 1)));
            ref[n] = static_cast<float>(std::sin(phase) * hann * 0.85);
        }
        return ref;
    }

    static void generateStereoChirpPcm(int16_t* outStereoSamples, size_t maxStereoFrames) {
        auto ref = buildReferenceMonoChirp();
        size_t framesToWrite = std::min(maxStereoFrames, kChirpMonoSamples);
        for (size_t i = 0; i < framesToWrite; ++i) {
            int16_t s = static_cast<int16_t>(std::lrint(ref[i] * 32767.0f));
            outStereoSamples[i * 2] = s;
            outStereoSamples[i * 2 + 1] = s;
        }
    }

    static int32_t estimateLatencyMs(const int16_t* micMonoSamples, size_t numMicSamples) {
        if (numMicSamples <= kChirpMonoSamples) return 0;

        auto ref = buildReferenceMonoChirp();
        const size_t maxLag = numMicSamples - kChirpMonoSamples;
        constexpr size_t kStep = 4;

        double bestCorr = -1.0;
        size_t bestLag = 0;

        for (size_t lag = 0; lag < maxLag; lag += kStep) {
            double dot = 0.0;
            double energy = 1e-9;
            for (size_t i = 0; i < kChirpMonoSamples; i += kStep) {
                float m = static_cast<float>(micMonoSamples[lag + i]) / 32768.0f;
                dot += static_cast<double>(m * ref[i]);
                energy += static_cast<double>(m * m);
            }
            double normCorr = std::fabs(dot) / std::sqrt(energy);
            if (normCorr > bestCorr) {
                bestCorr = normCorr;
                bestLag = lag;
            }
        }

        int32_t delayMs = static_cast<int32_t>((bestLag * 1000ULL) / kSampleRateHz);
        int32_t snapped = ((std::clamp(delayMs, 10, 2000)) / 10) * 10;
        return -snapped;
    }
};

} // namespace airmusic::calibration
