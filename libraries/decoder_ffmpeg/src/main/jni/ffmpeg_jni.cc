/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include <android/log.h>
#include <jni.h>

#include <climits>
#include <cstdint>
#include <cstring>
#include <memory>
#include <string>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavcodec/defs.h>
#include <libavcodec/version.h>
#include <libavutil/avutil.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/mem.h>
#include <libavutil/version.h>
#include <libswresample/swresample.h>
#include <libswresample/version.h>
}

#define LOG_TAG "ffmpeg_jni"
#define LOGE(...) \
  ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))
#define LOGD(...) \
  ((void)__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__))

#define ERROR_STRING_BUFFER_LENGTH 256

// Output format corresponding to AudioFormat.ENCODING_PCM_16BIT.
static const AVSampleFormat OUTPUT_FORMAT_PCM_16BIT = AV_SAMPLE_FMT_S16;
// Output format corresponding to AudioFormat.ENCODING_PCM_FLOAT.
static const AVSampleFormat OUTPUT_FORMAT_PCM_FLOAT = AV_SAMPLE_FMT_FLT;

// LINT.IfChange
static const int AUDIO_DECODER_ERROR_INVALID_DATA = -1;
static const int AUDIO_DECODER_ERROR_OTHER = -2;
static const int AUDIO_DECODER_END_OF_STREAM = -3;
// LINT.ThenChange(../java/androidx/media3/decoder/ffmpeg/FfmpegAudioDecoder.java)

static jmethodID growOutputBufferMethod;

// AV3A (AVS3-P3) neural decoder model path, read by avcodec_open2.
static std::string av3aModelPath;

/**
 * Returns the AVCodec with the specified name, or NULL if it is not available.
 */
const AVCodec* getCodecByName(JNIEnv* env, jstring codecName);

/**
 * Copies the provided extraData into the codec context as initialization data
 * if it is non-NULL. Returns whether the data was applied successfully.
 */
bool setCodecExtraData(JNIEnv* env, jbyteArray extraData,
                       AVCodecContext* codecContext);

struct ResampleState {
  SwrContext *context{};
  AVSampleFormat inFormat = AV_SAMPLE_FMT_NONE;
  AVSampleFormat outFormat = AV_SAMPLE_FMT_NONE;
  int inSampleRate = 0;
  int outSampleRate = 0;
  AVChannelLayout inLayout{};
  AVChannelLayout outLayout{};

  ~ResampleState() { reset(); }

  void reset() {
    swr_free(&context);
    av_channel_layout_uninit(&inLayout);
    av_channel_layout_uninit(&outLayout);
    inFormat = AV_SAMPLE_FMT_NONE;
    outFormat = AV_SAMPLE_FMT_NONE;
    inSampleRate = 0;
    outSampleRate = 0;
  }

  bool matches(const AVChannelLayout *inputLayout,
               const AVChannelLayout *outputLayout, AVSampleFormat inputFormat,
               AVSampleFormat outputFormat, int inputRate,
               int outputRate) const {
    return context && inFormat == inputFormat && outFormat == outputFormat &&
           inSampleRate == inputRate && outSampleRate == outputRate &&
           av_channel_layout_compare(&inLayout, inputLayout) == 0 &&
           av_channel_layout_compare(&outLayout, outputLayout) == 0;
  }

  int configure(const AVChannelLayout *inputLayout,
                const AVChannelLayout *outputLayout, AVSampleFormat inputFormat,
                AVSampleFormat outputFormat, int inputRate, int outputRate) {
    reset();
    int result = av_channel_layout_copy(&inLayout, inputLayout);
    if (result < 0) return result;
    result = av_channel_layout_copy(&outLayout, outputLayout);
    if (result < 0) {
      reset();
      return result;
    }
    result = swr_alloc_set_opts2(&context, &outLayout, outputFormat, outputRate,
                                 &inLayout, inputFormat, inputRate, 0, nullptr);
    if (result < 0) {
      reset();
      return result;
    }
    result = swr_init(context);
    if (result < 0) {
      reset();
      return result;
    }
    inFormat = inputFormat;
    outFormat = outputFormat;
    inSampleRate = inputRate;
    outSampleRate = outputRate;
    return 0;
  }
};

struct AudioCodecConfig {
  bool outputFloat = false;
  int rawSampleRate = -1;
  int rawChannelCount = -1;
  int rawBlockAlign = -1;
  int rawBitsPerCodedSample = -1;
  int rawBitRate = -1;
};

struct AudioJniContext {
  AVCodecContext *codecContext{};
  AVFrame *frame{};
  AVPacket *packet{};
  AudioCodecConfig config;
  ResampleState resampler;
  int targetSampleRate = 0;
  int outputChannelCount = 0;
  int outputSampleRate = 0;
  int64_t lastOutputTimeUs = AV_NOPTS_VALUE;
  int64_t nextOutputTimeUs = AV_NOPTS_VALUE;
  bool drainSent = false;
  bool drainComplete = false;
  ~AudioJniContext() {
    avcodec_free_context(&codecContext);
    av_packet_free(&packet);
    av_frame_free(&frame);
  }
};

static void updateOutputTiming(AudioJniContext *context, int outputSize,
                               int64_t outputTimeUs) {
  if (outputSize <= 0) {
    return;
  }
  if (outputTimeUs == AV_NOPTS_VALUE) {
    outputTimeUs = context->nextOutputTimeUs;
  }
  context->lastOutputTimeUs = outputTimeUs;
  if (outputTimeUs == AV_NOPTS_VALUE || context->outputChannelCount <= 0 ||
      context->outputSampleRate <= 0) {
    context->nextOutputTimeUs = AV_NOPTS_VALUE;
    return;
  }
  int bytesPerSample =
      context->config.outputFloat ? sizeof(float) : sizeof(int16_t);
  int bytesPerFrame = bytesPerSample * context->outputChannelCount;
  int64_t sampleCount = outputSize / bytesPerFrame;
  context->nextOutputTimeUs =
      outputTimeUs + av_rescale_q(sampleCount,
                                  AVRational{1, context->outputSampleRate},
                                  AV_TIME_BASE_Q);
}

static int computeDsdTargetSampleRate(int rawSampleRate) {
  if (rawSampleRate <= 192000) return 0;
  int rate = rawSampleRate;
  while (rate > 192000) rate /= 2;
  return rate;
}

static bool isDsdOrDstCodec(AVCodecID codecId) {
  return codecId == AV_CODEC_ID_DSD_MSBF || codecId == AV_CODEC_ID_DSD_LSBF ||
         codecId == AV_CODEC_ID_DSD_MSBF_PLANAR ||
         codecId == AV_CODEC_ID_DSD_LSBF_PLANAR || codecId == AV_CODEC_ID_DST;
}

static bool usesRawAudioParameters(AVCodecID codecId) {
  switch (codecId) {
    case AV_CODEC_ID_AAC:
    case AV_CODEC_ID_OPUS:
    case AV_CODEC_ID_PCM_MULAW:
    case AV_CODEC_ID_PCM_ALAW:
    case AV_CODEC_ID_PCM_S16LE:
    case AV_CODEC_ID_PCM_S16BE:
    case AV_CODEC_ID_PCM_U8:
    case AV_CODEC_ID_PCM_S24LE:
    case AV_CODEC_ID_PCM_S24BE:
    case AV_CODEC_ID_PCM_S32LE:
    case AV_CODEC_ID_PCM_S32BE:
    case AV_CODEC_ID_PCM_F32LE:
    case AV_CODEC_ID_PCM_F64LE:
    case AV_CODEC_ID_ADPCM_MS:
    case AV_CODEC_ID_ADPCM_IMA_WAV:
    case AV_CODEC_ID_COOK:
    case AV_CODEC_ID_ATRAC3:
    case AV_CODEC_ID_ATRAC3P:
    case AV_CODEC_ID_SIPR:
    case AV_CODEC_ID_WMAV1:
    case AV_CODEC_ID_WMAV2:
    case AV_CODEC_ID_WMAPRO:
    case AV_CODEC_ID_WMALOSSLESS:
    case AV_CODEC_ID_WMAVOICE:
      return true;
    default:
      return false;
  }
}

static void setRawAudioParameters(AVCodecContext *context,
                                  const AudioCodecConfig &config) {
  if (config.rawSampleRate > 0) {
    context->sample_rate = config.rawSampleRate;
  }
  if (config.rawChannelCount > 0) {
    av_channel_layout_default(&context->ch_layout, config.rawChannelCount);
  }
}

static void updateTargetSampleRate(AudioJniContext *jniContext) {
  jniContext->targetSampleRate = 0;
  AVCodecContext *codecContext = jniContext->codecContext;
  if (!codecContext || !isDsdOrDstCodec(codecContext->codec_id)) return;
  jniContext->targetSampleRate =
      computeDsdTargetSampleRate(codecContext->sample_rate);
  if (jniContext->targetSampleRate > 0) {
    LOGD("DSD/DST: downsampling PCM output from %d Hz to %d Hz.",
         codecContext->sample_rate, jniContext->targetSampleRate);
  }
}

struct GrowOutputBufferCallback {
  uint8_t *operator()(int currentSize, int requiredSize) const;

  JNIEnv *env;
  jobject thiz;
  jobject decoderOutputBuffer;
};

struct AudioDecodeStatus {
  bool packetAccepted = false;
  bool decoderEof = false;
  int64_t outputTimeUs = AV_NOPTS_VALUE;
};

uint8_t *GrowOutputBufferCallback::operator()(int currentSize,
                                              int requiredSize) const {
  jobject newOutputData = env->CallObjectMethod(
      thiz, growOutputBufferMethod, decoderOutputBuffer, currentSize,
      requiredSize);
  if (env->ExceptionCheck()) {
    LOGE("growOutputBuffer() failed");
    env->ExceptionDescribe();
    return nullptr;
  }
  if (!newOutputData) {
    LOGE("growOutputBuffer() returned NULL.");
    return nullptr;
  }
  auto *newOutputBuffer =
      static_cast<uint8_t *>(env->GetDirectBufferAddress(newOutputData));
  env->DeleteLocalRef(newOutputData);
  if (!newOutputBuffer) {
    LOGE("growOutputBuffer() returned a non-direct buffer.");
  }
  return newOutputBuffer;
}

class AudioOutputBufferWriter final {
 public:
  AudioOutputBufferWriter(uint8_t *data, int capacity,
                          GrowOutputBufferCallback growBuffer)
      : data_(data), capacity_(capacity), growBuffer_(growBuffer) {}

  bool append(const uint8_t *source, int size) {
    uint8_t *destination = nullptr;
    if ((size > 0 && !source) || !reserve(size, &destination)) {
      return false;
    }
    if (size > 0) {
      memcpy(destination, source, static_cast<size_t>(size));
    }
    size_ += size;
    return true;
  }

  bool reserve(int additionalSize, uint8_t **destination) {
    if (!destination || additionalSize < 0 ||
        size_ > INT_MAX - additionalSize) {
      LOGE("Output buffer size overflow: current=%d additional=%d.", size_,
           additionalSize);
      return false;
    }
    const int requiredSize = size_ + additionalSize;
    if (requiredSize > capacity_) {
      data_ = growBuffer_(size_, requiredSize);
      if (!data_) {
        LOGE("Failed to reallocate output buffer.");
        return false;
      }
      capacity_ = requiredSize;
    }
    if (requiredSize > 0 && !data_) {
      LOGE("Output buffer is NULL.");
      return false;
    }
    *destination = data_ ? data_ + size_ : nullptr;
    return true;
  }

  bool commit(int size) {
    if (size < 0 || size > capacity_ - size_) {
      LOGE("Invalid output buffer commit: current=%d capacity=%d size=%d.",
           size_, capacity_, size);
      return false;
    }
    size_ += size;
    return true;
  }

  int size() const { return size_; }

 private:
  uint8_t *data_;
  int capacity_;
  int size_ = 0;
  GrowOutputBufferCallback growBuffer_;
};

/**
 * Allocates and opens a new AVCodecContext for the specified codec, passing the
 * provided extraData as initialization data for the decoder if it is non-NULL.
 * Returns the created context.
 */
static AVCodecContext *createContext(JNIEnv *env, const AVCodec *codec,
                                     jbyteArray extraData,
                                     const AudioCodecConfig &config);

/**
 * Decodes the packet into the output buffer, returning the number of bytes
 * written, or a negative AUDIO_DECODER_ERROR constant value in the case of an
 * error. A NULL packet drains the decoder.
 */
static int decodePacket(AudioJniContext *jniContext, AVPacket *packet,
                        bool sendPacket, int64_t fallbackTimeUs,
                        AudioOutputBufferWriter &output,
                        AudioDecodeStatus &status);

jstring ffmpegGetVersion(JNIEnv* env, jobject thiz) {
  return env->NewStringUTF(LIBAVCODEC_IDENT);
}

jint ffmpegGetInputBufferPaddingSize(JNIEnv* env, jobject thiz) {
  return (jint)AV_INPUT_BUFFER_PADDING_SIZE;
}

jboolean ffmpegHasDecoder(JNIEnv* env, jobject thiz, jstring codecName) {
  return getCodecByName(env, codecName) != NULL;
}

void ffmpegSetAv3aModelPath(JNIEnv *env, jobject thiz, jstring modelPath) {
  const char *path =
      modelPath ? env->GetStringUTFChars(modelPath, nullptr) : nullptr;
  av3aModelPath = path ? path : "";
  if (path) {
    env->ReleaseStringUTFChars(modelPath, path);
  }
}

jlong ffmpegInitialize(JNIEnv *env, jobject thiz, jstring codecName,
                       jbyteArray extraData, jboolean outputFloat,
                       jint rawSampleRate, jint rawChannelCount,
                       jint rawBlockAlign, jint rawBitsPerCodedSample,
                       jint rawBitRate) {
  const AVCodec *codec = getCodecByName(env, codecName);
  if (!codec) {
    LOGE("Codec not found.");
    return 0L;
  }
  const AudioCodecConfig config = {outputFloat != JNI_FALSE, rawSampleRate,
                                   rawChannelCount,         rawBlockAlign,
                                   rawBitsPerCodedSample,   rawBitRate};
  auto jniContext = std::make_unique<AudioJniContext>();
  jniContext->config = config;
  jniContext->codecContext = createContext(env, codec, extraData, config);
  if (!jniContext->codecContext) {
    return 0L;
  }
  jniContext->frame = av_frame_alloc();
  jniContext->packet = av_packet_alloc();
  if (!jniContext->frame || !jniContext->packet) {
    LOGE("Failed to allocate cached audio AVFrame/AVPacket.");
    return 0L;
  }

  updateTargetSampleRate(jniContext.get());
  return reinterpret_cast<jlong>(jniContext.release());
}

jint ffmpegDecode(JNIEnv *env, jobject thiz, jlong context, jobject inputData,
                  jint inputOffset, jint inputSize, jlong inputTimeUs,
                  jobject decoderOutputBuffer, jobject outputData,
                  jint outputSize) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return AUDIO_DECODER_ERROR_OTHER;
  }
  if (!inputData || !decoderOutputBuffer || !outputData) {
    LOGE("Input and output buffers must be non-NULL.");
    return AUDIO_DECODER_ERROR_OTHER;
  }
  if (inputOffset < 0 || inputSize < 0) {
    LOGE("Invalid input buffer size: %d.", inputSize);
    return AUDIO_DECODER_ERROR_OTHER;
  }
  if (outputSize < 0) {
    LOGE("Invalid output buffer length: %d", outputSize);
    return AUDIO_DECODER_ERROR_OTHER;
  }
  auto *jniContext = reinterpret_cast<AudioJniContext *>(context);
  const GrowOutputBufferCallback growOutputBuffer = {env, thiz,
                                                     decoderOutputBuffer};
  auto *inputBuffer =
      static_cast<uint8_t *>(env->GetDirectBufferAddress(inputData));
  auto *outputBuffer =
      static_cast<uint8_t *>(env->GetDirectBufferAddress(outputData));
  jlong inputCapacity = env->GetDirectBufferCapacity(inputData);
  jlong outputCapacity = env->GetDirectBufferCapacity(outputData);
  if ((inputSize > 0 && !inputBuffer) || inputCapacity < 0 ||
      static_cast<int64_t>(inputOffset) + inputSize > inputCapacity ||
      (outputSize > 0 && !outputBuffer) || outputCapacity < outputSize) {
    LOGE("Input and output buffers must be direct buffers.");
    return AUDIO_DECODER_ERROR_OTHER;
  }
  AVPacket *packet = jniContext->packet;
  if (!packet) {
    LOGE("Audio packet cache is not initialized.");
    return AUDIO_DECODER_ERROR_OTHER;
  }

  av_packet_unref(packet);
  packet->data = inputBuffer ? inputBuffer + inputOffset : nullptr;
  packet->size = inputSize;
  packet->pts = inputTimeUs;

  AudioDecodeStatus decodeStatus;
  AudioOutputBufferWriter output(outputBuffer, outputSize, growOutputBuffer);
  int decodedPacket = decodePacket(jniContext, packet, /* sendPacket= */ true,
                                   inputTimeUs, output, decodeStatus);
  av_packet_unref(packet);
  if (decodedPacket < 0) {
    return decodedPacket;
  }
  if (!decodeStatus.packetAccepted) {
    LOGE("Audio decoder rejected input with EAGAIN after output was drained.");
    return AUDIO_DECODER_ERROR_OTHER;
  }
  updateOutputTiming(jniContext, decodedPacket, decodeStatus.outputTimeUs);
  return decodedPacket;
}

jint ffmpegDrain(JNIEnv *env, jobject thiz, jlong context,
                 jobject decoderOutputBuffer, jobject outputData,
                 jint outputSize) {
  if (!context || !decoderOutputBuffer || !outputData || outputSize < 0) {
    return AUDIO_DECODER_ERROR_OTHER;
  }
  auto *jniContext = reinterpret_cast<AudioJniContext *>(context);
  const GrowOutputBufferCallback growOutputBuffer = {env, thiz,
                                                     decoderOutputBuffer};
  if (jniContext->drainComplete) {
    return AUDIO_DECODER_END_OF_STREAM;
  }
  auto *outputBuffer =
      static_cast<uint8_t *>(env->GetDirectBufferAddress(outputData));
  jlong outputCapacity = env->GetDirectBufferCapacity(outputData);
  if ((outputSize > 0 && !outputBuffer) || outputCapacity < outputSize) {
    return AUDIO_DECODER_ERROR_OTHER;
  }
  AudioDecodeStatus decodeStatus;
  AudioOutputBufferWriter output(outputBuffer, outputSize, growOutputBuffer);
  int decodedPacket = decodePacket(
      jniContext, /* packet= */ nullptr,
      /* sendPacket= */ !jniContext->drainSent,
      /* fallbackTimeUs= */ jniContext->nextOutputTimeUs, output, decodeStatus);
  jniContext->drainSent = jniContext->drainSent || decodeStatus.packetAccepted;
  jniContext->drainComplete =
      jniContext->drainComplete || decodeStatus.decoderEof;
  updateOutputTiming(jniContext, decodedPacket, decodeStatus.outputTimeUs);
  return decodedPacket == 0 && jniContext->drainComplete
             ? AUDIO_DECODER_END_OF_STREAM
             : decodedPacket;
}

jint ffmpegGetChannelCount(JNIEnv *env, jobject thiz, jlong context) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  auto *jniContext = reinterpret_cast<AudioJniContext *>(context);
  if (jniContext->outputChannelCount > 0) return jniContext->outputChannelCount;
  return jniContext->codecContext->ch_layout.nb_channels;
}

jint ffmpegGetSampleRate(JNIEnv *env, jobject thiz, jlong context) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  auto *jniContext = reinterpret_cast<AudioJniContext *>(context);
  if (jniContext->outputSampleRate > 0) return jniContext->outputSampleRate;
  if (jniContext->targetSampleRate > 0) return jniContext->targetSampleRate;
  return jniContext->codecContext->sample_rate;
}

jlong ffmpegGetLastOutputTimeUs(JNIEnv *env, jobject thiz, jlong context) {
  if (!context) {
    return AV_NOPTS_VALUE;
  }
  return reinterpret_cast<AudioJniContext *>(context)->lastOutputTimeUs;
}

jlong ffmpegReset(JNIEnv *env, jobject thiz, jlong jContext,
                  jbyteArray extraData) {
  auto *jniContext = reinterpret_cast<AudioJniContext *>(jContext);
  if (!jniContext || !jniContext->codecContext) {
    LOGE("Tried to reset without a context.");
    return 0L;
  }
  AVCodecContext *context = jniContext->codecContext;

  AVCodecID codecId = context->codec_id;
  jniContext->resampler.reset();
  jniContext->outputChannelCount = 0;
  jniContext->outputSampleRate = 0;
  jniContext->lastOutputTimeUs = AV_NOPTS_VALUE;
  jniContext->nextOutputTimeUs = AV_NOPTS_VALUE;
  jniContext->drainSent = false;
  jniContext->drainComplete = false;
  av_packet_unref(jniContext->packet);
  if (codecId == AV_CODEC_ID_TRUEHD) {
    avcodec_free_context(&context);
    jniContext->codecContext = nullptr;
    const AVCodec *codec = avcodec_find_decoder(codecId);
    if (!codec) {
      LOGE("Unexpected error finding codec %d.", codecId);
      delete jniContext;
      return 0L;
    }
    AVCodecContext *newCtx =
        createContext(env, codec, extraData, jniContext->config);
    if (!newCtx) {
      delete jniContext;
      return 0L;
    }
    jniContext->codecContext = newCtx;
    updateTargetSampleRate(jniContext);
    av_frame_unref(jniContext->frame);
    return reinterpret_cast<jlong>(jniContext);
  }

  avcodec_flush_buffers(context);
  av_frame_unref(jniContext->frame);
  return reinterpret_cast<jlong>(jniContext);
}

void ffmpegRelease(JNIEnv *env, jobject thiz, jlong context) {
  if (context) {
    auto *jniContext = reinterpret_cast<AudioJniContext *>(context);
    delete jniContext;
  }
}

const AVCodec *getCodecByName(JNIEnv *env, jstring codecName) {
  if (!codecName) {
    return nullptr;
  }
  const char *codecNameChars = env->GetStringUTFChars(codecName, nullptr);
  if (!codecNameChars) {
    return nullptr;
  }
  const AVCodec *codec = avcodec_find_decoder_by_name(codecNameChars);
  env->ReleaseStringUTFChars(codecName, codecNameChars);
  return codec;
}

bool setCodecExtraData(JNIEnv *env, jbyteArray extraData,
                       AVCodecContext *codecContext) {
  if (!extraData) {
    return true;
  }
  if (!env || !codecContext) {
    return false;
  }
  const jsize size = env->GetArrayLength(extraData);
  if (env->ExceptionCheck()) {
    LOGE("Failed to get codec extradata size.");
    return false;
  }
  if (size == 0) {
    return true;
  }

  const size_t allocationSize =
      static_cast<size_t>(size) + AV_INPUT_BUFFER_PADDING_SIZE;
  auto *data = static_cast<uint8_t *>(av_mallocz(allocationSize));
  if (!data) {
    LOGE("Failed to allocate codec extradata.");
    return false;
  }
  env->GetByteArrayRegion(extraData, 0, size, reinterpret_cast<jbyte *>(data));
  if (env->ExceptionCheck()) {
    LOGE("Failed to copy codec extradata.");
    av_free(data);
    return false;
  }
  codecContext->extradata = data;
  codecContext->extradata_size = size;
  return true;
}

static AVCodecContext *createContext(JNIEnv *env, const AVCodec *codec,
                                     jbyteArray extraData,
                                     const AudioCodecConfig &config) {
  AVCodecContext *context = avcodec_alloc_context3(codec);
  if (!context) {
    LOGE("Failed to allocate context.");
    return nullptr;
  }
  context->request_sample_fmt =
      config.outputFloat ? OUTPUT_FORMAT_PCM_FLOAT : OUTPUT_FORMAT_PCM_16BIT;
  if (!setCodecExtraData(env, extraData, context)) {
    avcodec_free_context(&context);
    return nullptr;
  }
  if (usesRawAudioParameters(context->codec_id)) {
    setRawAudioParameters(context, config);
  }
  if (config.rawBitsPerCodedSample > 0) {
    context->bits_per_coded_sample = config.rawBitsPerCodedSample;
  }
  if (context->codec_id == AV_CODEC_ID_ADPCM_IMA_WAV) {
    context->bits_per_coded_sample = 4;
  }
  if (isDsdOrDstCodec(context->codec_id)) {
    context->bits_per_coded_sample = 8;
    setRawAudioParameters(context, config);
  }
  if (config.rawBlockAlign > 0) {
    context->block_align = config.rawBlockAlign;
  }

  if (config.rawBitRate > 0) {
    context->bit_rate = config.rawBitRate;
  }
  if (context->codec_id == AV_CODEC_ID_AV3A && !av3aModelPath.empty()) {
    context->av3a_model_path = av_strdup(av3aModelPath.c_str());
  }
  context->pkt_timebase = AV_TIME_BASE_Q;
  int result = avcodec_open2(context, codec, nullptr);
  if (result < 0) {
    logError("avcodec_open2", result);
    avcodec_free_context(&context);
    return nullptr;
  }
  return context;
}

static int transformError(int errorNumber) {
  return errorNumber == AVERROR_INVALIDDATA ? AUDIO_DECODER_ERROR_INVALID_DATA
                                            : AUDIO_DECODER_ERROR_OTHER;
}

static int decodePacket(AudioJniContext *jniContext, AVPacket *packet,
                        bool sendPacket, int64_t fallbackTimeUs,
                        AudioOutputBufferWriter &output,
                        AudioDecodeStatus &status) {
  AVCodecContext *context = jniContext->codecContext;
  const bool draining = packet == nullptr;
  int result = 0;
  status = {};
  status.packetAccepted = !sendPacket;
  if (sendPacket) {
    result = avcodec_send_packet(context, packet);
    if (result == 0 || (draining && result == AVERROR_EOF)) {
      status.packetAccepted = true;
    } else if (result != AVERROR(EAGAIN)) {
      logError("avcodec_send_packet", result);
      return transformError(result);
    }
  }

  while (true) {
    av_frame_unref(jniContext->frame);
    result = avcodec_receive_frame(context, jniContext->frame);
    if (result) {
      if (result == AVERROR(EAGAIN)) {
        if (sendPacket && !status.packetAccepted) {
          result = avcodec_send_packet(context, packet);
          if (result == 0 || (draining && result == AVERROR_EOF)) {
            status.packetAccepted = true;
            continue;
          }
          if (result == AVERROR(EAGAIN)) {
            LOGE("FFmpeg returned EAGAIN from both send and receive.");
            return AUDIO_DECODER_ERROR_OTHER;
          }
          logError("avcodec_send_packet(retry)", result);
          return transformError(result);
        }
        break;
      }
      if (result == AVERROR_EOF) {
        status.decoderEof = true;
        break;
      }
      logError("avcodec_receive_frame", result);
      return transformError(result);
    }

    if (output.size() == 0) {
      int64_t frameTimeUs = jniContext->frame->pts;
      if (frameTimeUs == AV_NOPTS_VALUE) {
        frameTimeUs = jniContext->frame->best_effort_timestamp;
      }
      status.outputTimeUs =
          frameTimeUs != AV_NOPTS_VALUE ? frameTimeUs : fallbackTimeUs;
    }

    AVFrame *frame = jniContext->frame;
    AVSampleFormat sampleFormat = static_cast<AVSampleFormat>(frame->format);
    if (sampleFormat == AV_SAMPLE_FMT_NONE) {
      sampleFormat = context->sample_fmt;
    }
    const AVChannelLayout *channelLayout = frame->ch_layout.nb_channels > 0
                                               ? &frame->ch_layout
                                               : &context->ch_layout;
    const int channelCount = channelLayout->nb_channels;
    const int sampleRate =
        frame->sample_rate > 0 ? frame->sample_rate : context->sample_rate;
    const int sampleCount = frame->nb_samples;
    const int outSampleRate = jniContext->targetSampleRate > 0
                                  ? jniContext->targetSampleRate
                                  : sampleRate;
    const uint8_t *const *inputData =
        frame->extended_data
            ? reinterpret_cast<const uint8_t *const *>(frame->extended_data)
            : reinterpret_cast<const uint8_t *const *>(frame->data);
    if (sampleFormat == AV_SAMPLE_FMT_NONE || channelCount <= 0 ||
        sampleRate <= 0 || sampleCount < 0 || !inputData || !inputData[0]) {
      LOGE(
          "Invalid decoded audio params: format=%d channels=%d rate=%d "
          "samples=%d.",
          sampleFormat, channelCount, sampleRate, sampleCount);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    jniContext->outputChannelCount = channelCount;
    jniContext->outputSampleRate = outSampleRate;

    if (!av_sample_fmt_is_planar(sampleFormat) &&
        sampleFormat == context->request_sample_fmt &&
        (jniContext->targetSampleRate == 0 ||
         jniContext->targetSampleRate == sampleRate)) {
      int dataSize = av_samples_get_buffer_size(nullptr, channelCount,
                                                sampleCount, sampleFormat, 1);
      if (dataSize < 0) {
        logError("av_samples_get_buffer_size", dataSize);
        return AUDIO_DECODER_ERROR_OTHER;
      }
      if (!output.append(inputData[0], dataSize)) {
        return AUDIO_DECODER_ERROR_OTHER;
      }
      continue;
    }
    AVSampleFormat outputFormat = context->request_sample_fmt;
    if (!jniContext->resampler.matches(channelLayout, channelLayout,
                                       sampleFormat, outputFormat, sampleRate,
                                       outSampleRate)) {
      result = jniContext->resampler.configure(channelLayout, channelLayout,
                                               sampleFormat, outputFormat,
                                               sampleRate, outSampleRate);
      if (result < 0) {
        logError("swr_alloc_set_opts2", result);
        return transformError(result);
      }
    }
    SwrContext *resampleContext = jniContext->resampler.context;
    int outSampleSize = av_get_bytes_per_sample(outputFormat);
    int outSamples = swr_get_out_samples(resampleContext, sampleCount);
    if (outSampleSize <= 0 || outSamples < 0) {
      LOGE("Invalid resample sizing: sampleSize=%d channels=%d samples=%d.",
           outSampleSize, channelCount, outSamples);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    int64_t bufferOutSize64 =
        static_cast<int64_t>(outSampleSize) * channelCount * outSamples;
    if (bufferOutSize64 > INT_MAX) {
      LOGE("Resample output buffer too large: %lld.",
           (long long)bufferOutSize64);
      return AUDIO_DECODER_ERROR_OTHER;
    }
    uint8_t *convertedOutput = nullptr;
    if (!output.reserve(static_cast<int>(bufferOutSize64), &convertedOutput)) {
      return AUDIO_DECODER_ERROR_OTHER;
    }
    result = swr_convert(resampleContext, &convertedOutput, outSamples,
                         inputData, frame->nb_samples);
    if (result < 0) {
      logError("swr_convert", result);
      return AUDIO_DECODER_ERROR_INVALID_DATA;
    }

    const int64_t writtenSize =
        static_cast<int64_t>(outSampleSize) * channelCount * result;
    if (writtenSize > INT_MAX ||
        !output.commit(static_cast<int>(writtenSize))) {
      return AUDIO_DECODER_ERROR_OTHER;
    }
  }
  ResampleState &resampler = jniContext->resampler;
  if (draining && status.decoderEof && resampler.context) {
    int outSampleSize = av_get_bytes_per_sample(resampler.outFormat);
    int channelCount = resampler.outLayout.nb_channels;
    while (swr_get_delay(resampler.context, resampler.outSampleRate) > 0) {
      int outSamples = swr_get_out_samples(resampler.context, 0);
      if (outSampleSize <= 0 || channelCount <= 0 || outSamples <= 0) {
        break;
      }
      int64_t drainSize64 =
          static_cast<int64_t>(outSampleSize) * channelCount * outSamples;
      if (drainSize64 > INT_MAX) {
        return AUDIO_DECODER_ERROR_OTHER;
      }
      uint8_t *convertedOutput = nullptr;
      if (!output.reserve(static_cast<int>(drainSize64), &convertedOutput)) {
        return AUDIO_DECODER_ERROR_OTHER;
      }
      result = swr_convert(resampler.context, &convertedOutput, outSamples,
                           nullptr, 0);
      if (result < 0) {
        logError("swr_convert(drain)", result);
        return AUDIO_DECODER_ERROR_OTHER;
      }
      if (result == 0) {
        break;
      }
      const int64_t writtenSize =
          static_cast<int64_t>(outSampleSize) * channelCount * result;
      if (writtenSize > INT_MAX ||
          !output.commit(static_cast<int>(writtenSize))) {
        return AUDIO_DECODER_ERROR_OTHER;
      }
    }
  }
  return output.size();
}

void logError(const char *functionName, int errorNumber) {
  char buffer[ERROR_STRING_BUFFER_LENGTH];
  if (av_strerror(errorNumber, buffer, sizeof(buffer)) < 0) {
    LOGE("Error in %s: %d", functionName, errorNumber);
    return;
  }
  LOGE("Error in %s: %s", functionName, buffer);
}

static bool hasExpectedVersion(const char *library, unsigned runtimeVersion,
                               unsigned headerVersion) {
  if (runtimeVersion == headerVersion) {
    return true;
  }
  LOGE("%s version mismatch: headers=%d.%d.%d runtime=%d.%d.%d.", library,
       AV_VERSION_MAJOR(headerVersion), AV_VERSION_MINOR(headerVersion),
       AV_VERSION_MICRO(headerVersion), AV_VERSION_MAJOR(runtimeVersion),
       AV_VERSION_MINOR(runtimeVersion), AV_VERSION_MICRO(runtimeVersion));
  return false;
}

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
  JNIEnv* env;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    LOGE("JNI_OnLoad: GetEnv failed");
    return -1;
  }
  if (!hasExpectedVersion("libavcodec", avcodec_version(),
                          LIBAVCODEC_VERSION_INT) ||
      !hasExpectedVersion("libavutil", avutil_version(),
                          LIBAVUTIL_VERSION_INT) ||
      !hasExpectedVersion("libswresample", swresample_version(),
                          LIBSWRESAMPLE_VERSION_INT)) {
    LOGE("JNI_OnLoad: version check failed");
    return -1;
  }
  jclass clazz =
      env->FindClass("androidx/media3/decoder/ffmpeg/FfmpegAudioDecoder");
  if (!clazz) {
    LOGE("JNI_OnLoad: FindClass failed");
    return -1;
  }
  growOutputBufferMethod =
      env->GetMethodID(clazz, "growOutputBuffer",
                       "(Landroidx/media3/decoder/"
                       "SimpleDecoderOutputBuffer;II)Ljava/nio/ByteBuffer;");
  if (!growOutputBufferMethod) {
    LOGE("JNI_OnLoad: GetMethodID failed");
    return -1;
  }
  static const JNINativeMethod kFfmpegAudioDecoderMethods[] = {
      {"ffmpegInitialize", "(Ljava/lang/String;[BZIIIII)J",
       reinterpret_cast<void*>(ffmpegInitialize)},
      {"ffmpegDecode",
       "(JLjava/nio/ByteBuffer;IIJLandroidx/media3/decoder/"
       "SimpleDecoderOutputBuffer;Ljava/nio/ByteBuffer;I)I",
       reinterpret_cast<void*>(ffmpegDecode)},
      {"ffmpegDrain",
       "(JLandroidx/media3/decoder/SimpleDecoderOutputBuffer;Ljava/nio/"
       "ByteBuffer;I)I",
       reinterpret_cast<void*>(ffmpegDrain)},
      {"ffmpegGetChannelCount", "(J)I",
       reinterpret_cast<void*>(ffmpegGetChannelCount)},
      {"ffmpegGetSampleRate", "(J)I",
       reinterpret_cast<void*>(ffmpegGetSampleRate)},
      {"ffmpegGetLastOutputTimeUs", "(J)J",
       reinterpret_cast<void*>(ffmpegGetLastOutputTimeUs)},
      {"ffmpegReset", "(J[B)J", reinterpret_cast<void*>(ffmpegReset)},
      {"ffmpegRelease", "(J)V", reinterpret_cast<void*>(ffmpegRelease)},
  };
  if (env->RegisterNatives(clazz, kFfmpegAudioDecoderMethods,
                           sizeof(kFfmpegAudioDecoderMethods) /
                               sizeof(kFfmpegAudioDecoderMethods[0])) < 0) {
    LOGE("JNI_OnLoad: RegisterNatives failed for FfmpegAudioDecoder");
    return -1;
  }

  jclass libraryClazz =
      env->FindClass("androidx/media3/decoder/ffmpeg/FfmpegLibrary");
  if (!libraryClazz) {
    LOGE("JNI_OnLoad: FindClass failed for FfmpegLibrary");
    return -1;
  }
  static const JNINativeMethod kFfmpegLibraryMethods[] = {
      {"ffmpegGetVersion", "()Ljava/lang/String;",
       reinterpret_cast<void*>(ffmpegGetVersion)},
      {"ffmpegGetInputBufferPaddingSize", "()I",
       reinterpret_cast<void*>(ffmpegGetInputBufferPaddingSize)},
      {"ffmpegHasDecoder", "(Ljava/lang/String;)Z",
       reinterpret_cast<void*>(ffmpegHasDecoder)},
      {"ffmpegSetAv3aModelPath", "(Ljava/lang/String;)V",
       reinterpret_cast<void*>(ffmpegSetAv3aModelPath)},
  };
  if (env->RegisterNatives(libraryClazz, kFfmpegLibraryMethods,
                           sizeof(kFfmpegLibraryMethods) /
                               sizeof(kFfmpegLibraryMethods[0])) < 0) {
    LOGE("JNI_OnLoad: RegisterNatives failed for FfmpegLibrary");
    return -1;
  }

  return JNI_VERSION_1_6;
}
