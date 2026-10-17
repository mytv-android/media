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
package androidx.media3.decoder.ffmpeg;

import static com.google.common.base.Preconditions.checkNotNull;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.nio.ByteBuffer;

/** FFmpeg audio decoder. */
@UnstableApi
public final class FfmpegAudioDecoder
    extends SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, FfmpegDecoderException> {

  private static final int INITIAL_OUTPUT_BUFFER_SIZE_16BIT = 65535;
  private static final int INITIAL_OUTPUT_BUFFER_SIZE_32BIT = INITIAL_OUTPUT_BUFFER_SIZE_16BIT * 2;

  private static final int AUDIO_DECODER_ERROR_INVALID_DATA = -1;
  private static final int AUDIO_DECODER_ERROR_OTHER = -2;
  private static final int AUDIO_DECODER_END_OF_STREAM = -3;

  private final String codecName;
  @Nullable private final byte[] extraData;
  private final @C.PcmEncoding int encoding;
  private int outputBufferSize;

  private long nativeContext; // May be reassigned on resetting the codec.
  private boolean hasOutputFormat;
  private volatile int channelCount;
  private volatile int sampleRate;

  public FfmpegAudioDecoder(
      Format format,
      int numInputBuffers,
      int numOutputBuffers,
      int initialInputBufferSize,
      boolean outputFloat)
      throws FfmpegDecoderException {
    super(new DecoderInputBuffer[numInputBuffers], new SimpleDecoderOutputBuffer[numOutputBuffers]);
    boolean initialized = false;
    try {
      if (!FfmpegLibrary.isAvailable()) {
        throw new FfmpegDecoderException("Failed to load decoder native libraries.");
      }
      checkNotNull(format.sampleMimeType);
      codecName = checkNotNull(FfmpegLibrary.getCodecName(format));
      FfmpegInitializationData initializationData = FfmpegInitializationData.forAudio(format);
      extraData = initializationData.extraData;
      encoding = outputFloat ? C.ENCODING_PCM_FLOAT : C.ENCODING_PCM_16BIT;
      outputBufferSize =
          outputFloat ? INITIAL_OUTPUT_BUFFER_SIZE_32BIT : INITIAL_OUTPUT_BUFFER_SIZE_16BIT;
      nativeContext =
          ffmpegInitialize(
              codecName,
              extraData,
              outputFloat,
              format.sampleRate,
              format.channelCount,
              initializationData.blockAlign,
              initializationData.bitsPerCodedSample,
              format.averageBitrate,
              FfmpegLibrary.getAv3aMaxOutputChannelCount());
      if (nativeContext == 0) {
        throw new FfmpegDecoderException("Initialization failed.");
      }
      setInitialInputBufferSize(initialInputBufferSize);
      initialized = true;
    } finally {
      if (!initialized) {
        try {
          super.release();
        } finally {
          if (nativeContext != 0) {
            ffmpegRelease(nativeContext);
            nativeContext = 0;
          }
        }
      }
    }
  }

  @Override
  public String getName() {
    return "ffmpeg" + FfmpegLibrary.getVersion() + "-" + codecName;
  }

  @Override
  protected DecoderInputBuffer createInputBuffer() {
    return new DecoderInputBuffer(
        DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT,
        FfmpegLibrary.getInputBufferPaddingSize());
  }

  @Override
  protected SimpleDecoderOutputBuffer createOutputBuffer() {
    return new SimpleDecoderOutputBuffer(this::releaseOutputBuffer);
  }

  @Override
  protected FfmpegDecoderException createUnexpectedDecodeException(Throwable error) {
    return new FfmpegDecoderException("Unexpected decode error", error);
  }

  @Override
  @Nullable
  protected FfmpegDecoderException decode(
      DecoderInputBuffer inputBuffer, SimpleDecoderOutputBuffer outputBuffer, boolean reset) {
    @Nullable FfmpegDecoderException resetError = maybeResetDecoder(reset);
    if (resetError != null) {
      return resetError;
    }
    ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
    ByteBuffer outputData = outputBuffer.init(inputBuffer.timeUs, outputBufferSize);
    int result =
        ffmpegDecode(
            nativeContext,
            inputData,
            inputData.position(),
            inputData.remaining(),
            inputBuffer.timeUs,
            outputBuffer,
            outputData,
            outputBufferSize);
    return finishDecode(result, outputBuffer);
  }

  @Nullable
  @Override
  protected FfmpegDecoderException decodeEndOfStream(
      DecoderInputBuffer inputBuffer, SimpleDecoderOutputBuffer outputBuffer, boolean reset) {
    @Nullable FfmpegDecoderException resetError = maybeResetDecoder(reset);
    if (resetError != null) {
      return resetError;
    }
    ByteBuffer outputData = outputBuffer.init(inputBuffer.timeUs, outputBufferSize);
    int result = ffmpegDrain(nativeContext, outputBuffer, outputData, outputBufferSize);
    if (result == AUDIO_DECODER_END_OF_STREAM) {
      outputBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
      return null;
    }
    return finishDecode(result, outputBuffer);
  }

  @Nullable
  private FfmpegDecoderException maybeResetDecoder(boolean reset) {
    if (!reset) {
      return null;
    }
    nativeContext = ffmpegReset(nativeContext, extraData);
    return nativeContext == 0 ? new FfmpegDecoderException("Error resetting (see logcat).") : null;
  }

  @Nullable
  private FfmpegDecoderException finishDecode(int result, SimpleDecoderOutputBuffer outputBuffer) {
    if (result == AUDIO_DECODER_ERROR_OTHER) {
      return new FfmpegDecoderException("Error decoding (see logcat).");
    } else if (result == AUDIO_DECODER_ERROR_INVALID_DATA) {
      // Treat invalid data errors as non-fatal to match the behavior of MediaCodec. No output will
      // be produced for this buffer, so mark it as skipped to ensure that the audio sink's
      // position is reset when more audio is produced.
      outputBuffer.shouldBeSkipped = true;
      return null;
    } else if (result == 0) {
      // There's no need to output empty buffers.
      outputBuffer.shouldBeSkipped = true;
      return null;
    }
    long outputTimeUs = ffmpegGetLastOutputTimeUs(nativeContext);
    if (outputTimeUs != Long.MIN_VALUE) {
      outputBuffer.timeUs = outputTimeUs;
    }
    if (!hasOutputFormat) {
      channelCount = ffmpegGetChannelCount(nativeContext);
      sampleRate = ffmpegGetSampleRate(nativeContext);
      if (sampleRate == 0 && "alac".equals(codecName)) {
        checkNotNull(extraData);
        // ALAC decoder did not set the sample rate in earlier versions of FFmpeg. See
        // https://trac.ffmpeg.org/ticket/6096.
        ParsableByteArray parsableExtraData = new ParsableByteArray(extraData);
        parsableExtraData.setPosition(extraData.length - 4);
        sampleRate = parsableExtraData.readUnsignedIntToInt();
      }
      hasOutputFormat = true;
    }
    // Get a new reference to the output ByteBuffer in case the native decode method reallocated the
    // buffer to grow its size.
    ByteBuffer outputData = checkNotNull(outputBuffer.data);
    outputData.position(0);
    outputData.limit(result);
    return null;
  }

  // Called from native code
  @SuppressWarnings("unused")
  private ByteBuffer growOutputBuffer(
      SimpleDecoderOutputBuffer outputBuffer, int currentSize, int requiredSize) {
    ByteBuffer currentData = checkNotNull(outputBuffer.data);
    currentData.position(0);
    currentData.limit(currentSize);
    outputBufferSize = requiredSize;
    return outputBuffer.grow(requiredSize);
  }

  @Override
  public void release() {
    try {
      super.release();
    } finally {
      if (nativeContext != 0) {
        ffmpegRelease(nativeContext);
        nativeContext = 0;
      }
    }
  }

  /** Returns the channel count of output audio. */
  public int getChannelCount() {
    return channelCount;
  }

  /** Returns the sample rate of output audio. */
  public int getSampleRate() {
    return sampleRate;
  }

  /** Returns the encoding of output audio. */
  public @C.PcmEncoding int getEncoding() {
    return encoding;
  }

  private native long ffmpegInitialize(
      String codecName,
      @Nullable byte[] extraData,
      boolean outputFloat,
      int rawSampleRate,
      int rawChannelCount,
      int rawBlockAlign,
      int rawBitsPerCodedSample,
      int rawBitRate,
      int maxOutputChannelCount);

  private native int ffmpegDecode(
      long context,
      ByteBuffer inputData,
      int inputOffset,
      int inputSize,
      long inputTimeUs,
      SimpleDecoderOutputBuffer decoderOutputBuffer,
      ByteBuffer outputData,
      int outputSize);

  private native int ffmpegDrain(
      long context,
      SimpleDecoderOutputBuffer decoderOutputBuffer,
      ByteBuffer outputData,
      int outputSize);

  private native int ffmpegGetChannelCount(long context);

  private native int ffmpegGetSampleRate(long context);

  private native long ffmpegGetLastOutputTimeUs(long context);

  private native long ffmpegReset(long context, @Nullable byte[] extraData);

  private native void ffmpegRelease(long context);
}
