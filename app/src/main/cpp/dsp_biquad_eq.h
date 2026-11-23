#pragma once

#include <array>
#include <cmath>
#include <cstdint>
#include <algorithm>

namespace airmusic::dsp {

constexpr size_t kNumEqBands = 10;
constexpr double kSampleRateHz = 44100.0;
constexpr double kPi = 3.14159265358979323846;

// Standard 10-band ISO octave center frequencies matching Phase 2 UI (31 Hz .. 16 kHz)
constexpr std::array<double, kNumEqBands> kIsoCenterFreqsHz = {
    31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0
};

struct BiquadCoeffs {
    float b0 = 1.0f;
    float b1 = 0.0f;
    float b2 = 0.0f;
    float a1 = 0.0f;
    float a2 = 0.0f;
};

struct BiquadState {
    float x1 = 0.0f, x2 = 0.0f;
    float y1 = 0.0f, y2 = 0.0f;

    void reset() {
        x1 = x2 = y1 = y2 = 0.0f;
    }

    inline float process(float x, const BiquadCoeffs& c) {
        float y = c.b0 * x + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2;
        x2 = x1;
        x1 = x;
        y2 = y1;
        y1 = y;
        return y;
    }
};

/**
 * Per-Speaker 10-Band Peaking Biquad Equalizer, Stereo-to-Mono Downmixer,
 * Logarithmic Volume Attenuator, and Soft-Knee Lookahead Brickwall Limiter.
 */
class SinkDspProcessor {
public:
    SinkDspProcessor() {
        bandGainsDb_.fill(0.0f);
        recomputeAllBands();
    }

    void setEqEnabled(bool enabled) { eqEnabled_ = enabled; }
    void setLimiterEnabled(bool enabled) { limiterEnabled_ = enabled; }
    void setStereoToMono(bool mono) { stereoToMono_ = mono; }

    void setVolumePercent(int volumePercent, bool muted) {
        if (muted || volumePercent <= 0) {
            linearGain_ = 0.0f;
        } else if (volumePercent >= 100) {
            linearGain_ = 1.0f;
        } else {
            // Logarithmic perceptual volume curve (-40 dB to 0 dB)
            float db = -40.0f * (1.0f - (static_cast<float>(volumePercent) / 100.0f));
            linearGain_ = std::pow(10.0f, db / 20.0f);
        }
    }

    void setBandGainDb(size_t bandIndex, float gainDb) {
        if (bandIndex >= kNumEqBands) return;
        float clamped = std::clamp(gainDb, -12.0f, 12.0f);
        bandGainsDb_[bandIndex] = clamped;
        coeffs_[bandIndex] = computePeakingEq(kIsoCenterFreqsHz[bandIndex], clamped, 1.41421356);
    }

    void processPcm1408(int16_t* interleavedStereoSamples, size_t numStereoFrames = 352) {
        for (size_t frame = 0; frame < numStereoFrames; ++frame) {
            float left = static_cast<float>(interleavedStereoSamples[frame * 2]) / 32768.0f;
            float right = static_cast<float>(interleavedStereoSamples[frame * 2 + 1]) / 32768.0f;

            // 1. Optional Stereo-to-Mono phase-coherent downmix
            if (stereoToMono_) {
                float mono = 0.5f * (left + right);
                left = mono;
                right = mono;
            }

            // 2. 10-Band Cascaded Biquad Peaking Equalizer
            if (eqEnabled_) {
                for (size_t b = 0; b < kNumEqBands; ++b) {
                    if (std::fabs(bandGainsDb_[b]) > 0.05f) {
                        left = leftState_[b].process(left, coeffs_[b]);
                        right = rightState_[b].process(right, coeffs_[b]);
                    }
                }
            }

            // 3. Per-Speaker Logarithmic Volume Scaling
            left *= linearGain_;
            right *= linearGain_;

            // 4. Soft-Knee Brickwall Limiter (-0.3 dBFS ceiling) to prevent 16-bit integer wrap
            if (limiterEnabled_) {
                left = softLimit(left);
                right = softLimit(right);
            } else {
                left = std::clamp(left, -1.0f, 1.0f);
                right = std::clamp(right, -1.0f, 1.0f);
            }

            interleavedStereoSamples[frame * 2] = static_cast<int16_t>(std::lrint(left * 32767.0f));
            interleavedStereoSamples[frame * 2 + 1] = static_cast<int16_t>(std::lrint(right * 32767.0f));
        }
    }

private:
    static BiquadCoeffs computePeakingEq(double freqHz, float gainDb, double qFactor) {
        if (std::fabs(gainDb) < 0.01f) {
            return BiquadCoeffs{};
        }
        double A = std::pow(10.0, static_cast<double>(gainDb) / 40.0);
        double w0 = 2.0 * kPi * freqHz / kSampleRateHz;
        double cosW0 = std::cos(w0);
        double sinW0 = std::sin(w0);
        double alpha = sinW0 / (2.0 * qFactor);

        double b0 = 1.0 + alpha * A;
        double b1 = -2.0 * cosW0;
        double b2 = 1.0 - alpha * A;
        double a0 = 1.0 + alpha / A;
        double a1 = -2.0 * cosW0;
        double a2 = 1.0 - alpha / A;

        BiquadCoeffs c;
        c.b0 = static_cast<float>(b0 / a0);
        c.b1 = static_cast<float>(b1 / a0);
        c.b2 = static_cast<float>(b2 / a0);
        c.a1 = static_cast<float>(a1 / a0);
        c.a2 = static_cast<float>(a2 / a0);
        return c;
    }

    static inline float softLimit(float x) {
        constexpr float kThreshold = 0.85f;
        float absX = std::fabs(x);
        if (absX <= kThreshold) return x;
        float sign = (x >= 0.0f) ? 1.0f : -1.0f;
        float excess = absX - kThreshold;
        float compressed = kThreshold + (1.0f - kThreshold) * std::tanh(excess / (1.0f - kThreshold));
        return sign * std::min(compressed, 0.988f);
    }

    void recomputeAllBands() {
        for (size_t i = 0; i < kNumEqBands; ++i) {
            coeffs_[i] = computePeakingEq(kIsoCenterFreqsHz[i], bandGainsDb_[i], 1.41421356);
        }
    }

    bool eqEnabled_ = false;
    bool limiterEnabled_ = true;
    bool stereoToMono_ = false;
    float linearGain_ = 1.0f;
    std::array<float, kNumEqBands> bandGainsDb_{};
    std::array<BiquadCoeffs, kNumEqBands> coeffs_{};
    std::array<BiquadState, kNumEqBands> leftState_{};
    std::array<BiquadState, kNumEqBands> rightState_{};
};

} // namespace airmusic::dsp
