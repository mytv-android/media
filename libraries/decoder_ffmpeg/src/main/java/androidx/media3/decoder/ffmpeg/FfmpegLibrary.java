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

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.LibraryLoader;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** Configures and queries the underlying native library. */
@UnstableApi
public final class FfmpegLibrary {

  static {
    MediaLibraryInfo.registerModule("media3.decoder.ffmpeg");
  }

  private static final String TAG = "FfmpegLibrary";

  private static final LibraryLoader LOADER =
      new LibraryLoader("ffmpegJNI") {
        @Override
        protected void loadLibrary(String name) {
          System.loadLibrary(name);
        }
      };

  private static @MonotonicNonNull String version;
  private static int inputBufferPaddingSize = C.LENGTH_UNSET;

  private FfmpegLibrary() {}

  /**
   * Override the names of the FFmpeg native libraries. If an application wishes to call this
   * method, it must do so before calling any other method defined by this class, and before
   * instantiating a {@link FfmpegAudioRenderer} instance.
   *
   * @param libraries The names of the FFmpeg native libraries.
   */
  public static void setLibraries(String... libraries) {
    LOADER.setLibraries(libraries);
  }

  /** Returns whether the underlying library is available, loading it if necessary. */
  public static boolean isAvailable() {
    return LOADER.isAvailable();
  }

  /** Returns the version of the underlying library if available, or null otherwise. */
  @Nullable
  public static String getVersion() {
    if (!isAvailable()) {
      return null;
    }
    if (version == null) {
      version = ffmpegGetVersion();
    }
    return version;
  }

  /**
   * Returns the required amount of padding for input buffers in bytes, or {@link C#LENGTH_UNSET} if
   * the underlying library is not available.
   */
  public static int getInputBufferPaddingSize() {
    if (!isAvailable()) {
      return C.LENGTH_UNSET;
    }
    if (inputBufferPaddingSize == C.LENGTH_UNSET) {
      inputBufferPaddingSize = ffmpegGetInputBufferPaddingSize();
    }
    return inputBufferPaddingSize;
  }

  static int getPcmOutputSampleRate(Format format) {
    int sampleRate = format.sampleRate;
    if (!isDsdOrDstMimeType(format.sampleMimeType) || sampleRate <= 192000) {
      return sampleRate;
    }
    while (sampleRate > 192000) {
      sampleRate /= 2;
    }
    return sampleRate;
  }

  private static boolean isDsdOrDstMimeType(@Nullable String sampleMimeType) {
    if (sampleMimeType == null) {
      return false;
    }
    switch (sampleMimeType) {
      case MimeTypes.AUDIO_DSD:
      case MimeTypes.AUDIO_DSD_LSBF_PLANAR:
      case MimeTypes.AUDIO_DSD_MSBF_PLANAR:
      case MimeTypes.AUDIO_DST:
        return true;
      default:
        return false;
    }
  }

  /**
   * Returns whether the underlying library supports the specified MIME type.
   *
   * @param mimeType The MIME type to check.
   */
  public static boolean supportsFormat(String mimeType) {
    if (!isAvailable()) {
      return false;
    }
    @Nullable String codecName = getCodecName(mimeType);
    return supportsCodecName(codecName);
  }

  /** Returns whether the underlying library supports the specified format. */
  public static boolean supportsFormat(Format format) {
    if (!isAvailable()) {
      return false;
    }
    @Nullable String codecName = getCodecName(format);
    return supportsCodecName(codecName);
  }

  private static boolean supportsCodecName(@Nullable String codecName) {
    if (codecName == null) {
      return false;
    }
    if (!ffmpegHasDecoder(codecName)) {
      Log.w(TAG, "No " + codecName + " decoder available. Check the FFmpeg build configuration.");
      return false;
    }
    return true;
  }

  /**
   * Returns the name of the FFmpeg decoder that could be used to decode the format, or {@code null}
   * if it's unsupported.
   */
  @Nullable
  /* package */ static String getCodecName(Format format) {
    if (MimeTypes.AUDIO_RAW.equals(format.sampleMimeType)) {
      switch (format.pcmEncoding) {
        case C.ENCODING_PCM_8BIT:
          return "pcm_u8";
        case C.ENCODING_PCM_16BIT:
          return "pcm_s16le";
        case C.ENCODING_PCM_16BIT_BIG_ENDIAN:
          return "pcm_s16be";
        case C.ENCODING_PCM_24BIT:
          return "pcm_s24le";
        case C.ENCODING_PCM_24BIT_BIG_ENDIAN:
          return "pcm_s24be";
        case C.ENCODING_PCM_32BIT:
          return "pcm_s32le";
        case C.ENCODING_PCM_32BIT_BIG_ENDIAN:
          return "pcm_s32be";
        case C.ENCODING_PCM_FLOAT:
          return "pcm_f32le";
        case C.ENCODING_PCM_DOUBLE:
          return "pcm_f64le";
        default:
          return null;
      }
    }
    return getCodecName(format.sampleMimeType);
  }

  /**
   * Returns the name of the FFmpeg decoder that could be used to decode the MIME type, or {@code
   * null} if it's unsupported.
   */
  @Nullable
  /* package */ static String getCodecName(@Nullable String mimeType) {
    if (mimeType == null) {
      return null;
    }
    switch (mimeType) {
      case MimeTypes.AUDIO_AAC:
        return "aac";
      case MimeTypes.AUDIO_MPEG:
        return "mp3";
      case MimeTypes.AUDIO_MPEG_L1:
        return "mp1";
      case MimeTypes.AUDIO_MPEG_L2:
        return "mp2";
      case MimeTypes.AUDIO_MP4_ALS:
        return "als";
      case MimeTypes.AUDIO_AC3:
        return "ac3";
      case MimeTypes.AUDIO_E_AC3:
      case MimeTypes.AUDIO_E_AC3_JOC:
        return "eac3";
      case MimeTypes.AUDIO_TRUEHD:
        return "truehd";
      case MimeTypes.AUDIO_DTS:
      case MimeTypes.AUDIO_DTS_EXPRESS:
      case MimeTypes.AUDIO_DTS_HD:
      case MimeTypes.AUDIO_DTS_HD_MA:
      case MimeTypes.AUDIO_MEDIA3_DTS_HD_MA_CORELESS:
        return "dca";
      case MimeTypes.AUDIO_VORBIS:
        return "vorbis";
      case MimeTypes.AUDIO_OPUS:
        return "opus";
      case MimeTypes.AUDIO_AMR_NB:
        return "amrnb";
      case MimeTypes.AUDIO_AMR_WB:
        return "amrwb";
      case MimeTypes.AUDIO_FLAC:
        return "flac";
      case MimeTypes.AUDIO_ALAC:
        return "alac";
      case MimeTypes.AUDIO_AV3A:
        return "libarcdav3a";
      case MimeTypes.AUDIO_MLAW:
        return "pcm_mulaw";
      case MimeTypes.AUDIO_ALAW:
        return "pcm_alaw";
      case MimeTypes.AUDIO_DSD:
        return "dsd_msbf";
      case MimeTypes.AUDIO_DSD_LSBF_PLANAR:
        return "dsd_lsbf_planar";
      case MimeTypes.AUDIO_DSD_MSBF_PLANAR:
        return "dsd_msbf_planar";
      case MimeTypes.AUDIO_DST:
        return "dst";
      case MimeTypes.AUDIO_COOK:
        return "cook";
      case MimeTypes.AUDIO_ATRAC3:
        return "atrac3";
      case MimeTypes.AUDIO_ATRAC3P:
        return "atrac3plus";
      case MimeTypes.AUDIO_SIPR:
        return "sipr";
      case MimeTypes.AUDIO_RALF:
        return "ralf";
      case MimeTypes.AUDIO_WMA:
        return "wmav2";
      case MimeTypes.AUDIO_WMA1:
        return "wmav1";
      case MimeTypes.AUDIO_WMA2:
        return "wmav2";
      case MimeTypes.AUDIO_WMA_PRO:
        return "wmapro";
      case MimeTypes.AUDIO_WMA_LOSSLESS:
        return "wmalossless";
      case MimeTypes.AUDIO_WMA_VOICE:
        return "wmavoice";
      default:
        return null;
    }
  }

  private static native String ffmpegGetVersion();

  private static native int ffmpegGetInputBufferPaddingSize();

  private static native boolean ffmpegHasDecoder(String codecName);
}
