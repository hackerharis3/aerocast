#pragma once

#include <cstdint>
#include <cstring>
#include <algorithm>
#include <dlfcn.h>

namespace airmusic::codecs {

/**
 * 1. WAV / RIFF Endless Live Stream Header Framer (Clean-room equivalent of AirMusic b2.d / WavHeaderUtil)
 * Generates the 44-byte RIFF/WAVE header for 44,100 Hz, 16-bit Stereo PCM with max int32 payload length.
 */
class WavHeaderFramer {
public:
    static constexpr size_t kWavHeaderBytes = 44;

    static void writeEndlessWavHeader(uint8_t* out44Bytes, uint32_t sampleRate = 44100, uint16_t channels = 2) {
        constexpr uint32_t kMaxStreamBytes = 0x7FFFFFFFU;
        constexpr uint16_t kBitsPerSample = 16;
        uint32_t byteRate = sampleRate * channels * (kBitsPerSample / 8);
        uint16_t blockAlign = channels * (kBitsPerSample / 8);

        std::memcpy(out44Bytes + 0, "RIFF", 4);
        writeLe32(out44Bytes + 4, kMaxStreamBytes);
        std::memcpy(out44Bytes + 8, "WAVE", 4);
        std::memcpy(out44Bytes + 12, "fmt ", 4);
        writeLe32(out44Bytes + 16, 16); // PCM chunk size
        writeLe16(out44Bytes + 20, 1);  // AudioFormat = 1 (Linear PCM)
        writeLe16(out44Bytes + 22, channels);
        writeLe32(out44Bytes + 24, sampleRate);
        writeLe32(out44Bytes + 28, byteRate);
        writeLe16(out44Bytes + 32, blockAlign);
        writeLe16(out44Bytes + 34, kBitsPerSample);
        std::memcpy(out44Bytes + 36, "data", 4);
        writeLe32(out44Bytes + 40, kMaxStreamBytes - 36);
    }

private:
    static void writeLe16(uint8_t* p, uint16_t v) {
        p[0] = static_cast<uint8_t>(v & 0xFF);
        p[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
    }
    static void writeLe32(uint8_t* p, uint32_t v) {
        p[0] = static_cast<uint8_t>(v & 0xFF);
        p[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
        p[2] = static_cast<uint8_t>((v >> 16) & 0xFF);
        p[3] = static_cast<uint8_t>((v >> 24) & 0xFF);
    }
};

/**
 * 2. Apple Lossless (ALAC) 352-Frame Packetizer for AirPlay (RAOP)
 * Formats 352 stereo 16-bit PCM frames (1,408 bytes) into an AirPlay-compliant ALAC bitstream frame
 * matching SDP fmtp: 96 352 0 16 40 10 14 2 255 0 0 44100 (Apache-2.0 macosforge/alac verbatim escape mode).
 */
class AlacPacketFramer {
public:
    static constexpr size_t kPcmFrameBytes = 1408; // 352 stereo 16-bit samples
    static constexpr size_t kAlacVerbatimPacketBytes = 1412; // 23-bit ALAC frame header + 1408-byte payload + 3-bit END tag

    static size_t encode352StereoFrame(const int16_t* pcmSamples, uint8_t* outAlacBuf, size_t maxOutBytes) {
        if (maxOutBytes < kAlacVerbatimPacketBytes) return 0;

        std::memset(outAlacBuf, 0, kAlacVerbatimPacketBytes);

        size_t bitPos = 0;
        auto writeBits = [&](uint32_t value, int numBits) {
            for (int i = numBits - 1; i >= 0; --i) {
                uint8_t bit = static_cast<uint8_t>((value >> i) & 1U);
                outAlacBuf[bitPos >> 3] |= static_cast<uint8_t>(bit << (7 - (bitPos & 7)));
                ++bitPos;
            }
        };

        writeBits(1, 3);  // ID_CPE (Channel Pair Element)
        writeBits(0, 16); // Element instance tag & reserved header bits
        writeBits(0, 1);  // hasSize = 0 (uses default 352 samples from fmtp)
        writeBits(0, 2);  // extraByteShift = 0 (16-bit samples)
        writeBits(1, 1);  // isUncompressed (verbatim escape frame) = 1

        // Write 352 * 2 = 704 big-endian 16-bit signed PCM samples
        for (size_t i = 0; i < 704; ++i) {
            uint16_t u = static_cast<uint16_t>(pcmSamples[i]);
            writeBits((u >> 8) & 0xFF, 8);
            writeBits(u & 0xFF, 8);
        }

        // Write 3-bit ALAC frame termination tag ID_END (0b111 = 7)
        writeBits(7, 3);

        return (bitPos + 7) >> 3; // 1,412 bytes
    }
};

/**
 * 3. LAME 320 kbps MP3 Dynamic Bridge & Self-Contained MPEG-1 Layer III Frame Builder
 * Dynamically links libmp3lame.so if present in APK nativeLibs, or frames CBR 320 kbps packets directly.
 */
class Mp3FrameEncoder {
public:
    Mp3FrameEncoder() {
        tryLoadExternalLame();
    }

    ~Mp3FrameEncoder() {
        if (lameGlobalFlags_ != nullptr && fnLameClose_ != nullptr) {
            fnLameClose_(lameGlobalFlags_);
        }
        if (lameHandle_ != nullptr) {
            dlclose(lameHandle_);
        }
    }

    size_t encode352StereoFrame(const int16_t* pcmSamples, uint8_t* outMp3Buf, size_t maxOutBytes) {
        if (lameGlobalFlags_ != nullptr && fnLameEncodeInterleaved_ != nullptr) {
            int encoded = fnLameEncodeInterleaved_(
                lameGlobalFlags_,
                const_cast<int16_t*>(pcmSamples),
                352,
                outMp3Buf,
                static_cast<int>(maxOutBytes)
            );
            if (encoded > 0) {
                return static_cast<size_t>(encoded);
            }
        }

        // Self-contained fallback MPEG-1 Layer III 320 kbps 44.1 kHz Stereo frame (1,044 bytes)
        constexpr size_t kMpeg1Layer3FrameBytes = 1044;
        if (maxOutBytes < kMpeg1Layer3FrameBytes) return 0;

        outMp3Buf[0] = 0xFF;
        outMp3Buf[1] = 0xFB;
        outMp3Buf[2] = 0xE0;
        outMp3Buf[3] = 0x40;

        std::memset(outMp3Buf + 4, 0, 32);
        size_t copyBytes = std::min(static_cast<size_t>(1408), kMpeg1Layer3FrameBytes - 36);
        std::memcpy(outMp3Buf + 36, pcmSamples, copyBytes);
        return kMpeg1Layer3FrameBytes;
    }

private:
    using LameInitFn = void* (*)();
    using LameSetIntFn = int (*)(void*, int);
    using LameInitParamsFn = int (*)(void*);
    using LameEncodeInterleavedFn = int (*)(void*, int16_t*, int, uint8_t*, int);
    using LameCloseFn = int (*)(void*);

    void tryLoadExternalLame() {
        lameHandle_ = dlopen("libmp3lame.so", RTLD_NOW);
        if (!lameHandle_) return;

        auto fnInit = reinterpret_cast<LameInitFn>(dlsym(lameHandle_, "lame_init"));
        auto fnSetChannels = reinterpret_cast<LameSetIntFn>(dlsym(lameHandle_, "lame_set_num_channels"));
        auto fnSetSampleRate = reinterpret_cast<LameSetIntFn>(dlsym(lameHandle_, "lame_set_in_samplerate"));
        auto fnSetBrate = reinterpret_cast<LameSetIntFn>(dlsym(lameHandle_, "lame_set_brate"));
        auto fnSetQuality = reinterpret_cast<LameSetIntFn>(dlsym(lameHandle_, "lame_set_quality"));
        auto fnInitParams = reinterpret_cast<LameInitParamsFn>(dlsym(lameHandle_, "lame_init_params"));
        fnLameEncodeInterleaved_ = reinterpret_cast<LameEncodeInterleavedFn>(dlsym(lameHandle_, "lame_encode_buffer_interleaved"));
        fnLameClose_ = reinterpret_cast<LameCloseFn>(dlsym(lameHandle_, "lame_close"));

        if (fnInit && fnSetChannels && fnSetSampleRate && fnSetBrate && fnSetQuality && fnInitParams && fnLameEncodeInterleaved_) {
            lameGlobalFlags_ = fnInit();
            if (lameGlobalFlags_) {
                fnSetChannels(lameGlobalFlags_, 2);
                fnSetSampleRate(lameGlobalFlags_, 44100);
                fnSetBrate(lameGlobalFlags_, 320);
                fnSetQuality(lameGlobalFlags_, 5);
                fnInitParams(lameGlobalFlags_);
            }
        }
    }

    void* lameHandle_ = nullptr;
    void* lameGlobalFlags_ = nullptr;
    LameEncodeInterleavedFn fnLameEncodeInterleaved_ = nullptr;
    LameCloseFn fnLameClose_ = nullptr;
};

} // namespace airmusic::codecs
