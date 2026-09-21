/*
 * Copyright (C) 2026 The Android Open Source Project
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
import androidx.media3.common.MimeTypes;
import java.nio.ByteBuffer;
import java.util.List;

/** FFmpeg-compatible codec-specific initialization data. */
/* package */ final class FfmpegInitializationData {

  private static final byte[] FLAC_STREAM_MARKER = {'f', 'L', 'a', 'C'};
  private static final int FLAC_METADATA_TYPE_STREAM_INFO = 0;
  private static final int FLAC_METADATA_BLOCK_HEADER_SIZE = 4;
  private static final int FLAC_STREAM_INFO_DATA_SIZE = 34;

  @Nullable final byte[] extraData;
  final int blockAlign;
  final int bitsPerCodedSample;

  private FfmpegInitializationData(
      @Nullable byte[] extraData,
      int blockAlign,
      int bitsPerCodedSample) {
    this.extraData = extraData;
    this.blockAlign = blockAlign;
    this.bitsPerCodedSample = bitsPerCodedSample;
  }

  static FfmpegInitializationData forAudio(Format format) {
    @Nullable String mimeType = format.sampleMimeType;
    return new FfmpegInitializationData(
        getAudioExtraData(mimeType, format.initializationData),
        getBlockAlign(mimeType, format.initializationData),
        getPcmBitsPerSample(format.pcmEncoding));
  }

  @Nullable
  private static byte[] getAudioExtraData(
      @Nullable String mimeType, List<byte[]> initializationData) {
    if (mimeType == null || initializationData.isEmpty()) {
      return null;
    }
    switch (mimeType) {
      case MimeTypes.AUDIO_ALAC:
        return getAlacExtraData(initializationData);
      case MimeTypes.AUDIO_VORBIS:
        return initializationData.size() >= 2 ? getVorbisExtraData(initializationData) : null;
      case MimeTypes.AUDIO_FLAC:
        return getFlacExtraData(initializationData);
      case MimeTypes.AUDIO_COOK:
      case MimeTypes.AUDIO_ATRAC3:
      case MimeTypes.AUDIO_ATRAC3P:
      case MimeTypes.AUDIO_SIPR:
      case MimeTypes.AUDIO_WMA1:
      case MimeTypes.AUDIO_WMA2:
      case MimeTypes.AUDIO_WMA_PRO:
      case MimeTypes.AUDIO_WMA_LOSSLESS:
      case MimeTypes.AUDIO_WMA_VOICE:
        return firstEntryOrNull(initializationData);
      default:
        return firstNonEmpty(initializationData);
    }
  }


  private static int getBlockAlign(@Nullable String mimeType, List<byte[]> initializationData) {
    if (mimeType == null || initializationData.size() < 2) {
      return 0;
    }
    switch (mimeType) {
      case MimeTypes.AUDIO_COOK:
      case MimeTypes.AUDIO_ATRAC3:
      case MimeTypes.AUDIO_ATRAC3P:
      case MimeTypes.AUDIO_SIPR:
      case MimeTypes.AUDIO_WMA1:
      case MimeTypes.AUDIO_WMA2:
      case MimeTypes.AUDIO_WMA_PRO:
      case MimeTypes.AUDIO_WMA_LOSSLESS:
      case MimeTypes.AUDIO_WMA_VOICE:
        byte[] data = initializationData.get(1);
        if (data.length >= 4) {
          return ((data[0] & 0xFF) << 24)
              | ((data[1] & 0xFF) << 16)
              | ((data[2] & 0xFF) << 8)
              | (data[3] & 0xFF);
        }
        return 0;
      default:
        return 0;
    }
  }

  private static int getPcmBitsPerSample(@C.PcmEncoding int pcmEncoding) {
    switch (pcmEncoding) {
      case C.ENCODING_PCM_8BIT:
        return 8;
      case C.ENCODING_PCM_16BIT:
      case C.ENCODING_PCM_16BIT_BIG_ENDIAN:
        return 16;
      case C.ENCODING_PCM_24BIT:
      case C.ENCODING_PCM_24BIT_BIG_ENDIAN:
        return 24;
      case C.ENCODING_PCM_32BIT:
      case C.ENCODING_PCM_32BIT_BIG_ENDIAN:
      case C.ENCODING_PCM_FLOAT:
        return 32;
      case C.ENCODING_PCM_DOUBLE:
        return 64;
      default:
        return 0;
    }
  }

  @Nullable
  private static byte[] getAlacExtraData(List<byte[]> initializationData) {
    byte[] magicCookie = initializationData.get(0);
    if (magicCookie.length > Integer.MAX_VALUE - 12) {
      return null;
    }
    int alacAtomLength = 12 + magicCookie.length;
    ByteBuffer alacAtom = ByteBuffer.allocate(alacAtomLength);
    alacAtom.putInt(alacAtomLength);
    alacAtom.putInt(0x616c6163);
    alacAtom.putInt(0);
    alacAtom.put(magicCookie, 0, magicCookie.length);
    return alacAtom.array();
  }

  @Nullable
  private static byte[] getVorbisExtraData(List<byte[]> initializationData) {
    byte[] header0 = initializationData.get(0);
    byte[] header1 = initializationData.get(1);
    if (header0.length > 0xFFFF
        || header1.length > 0xFFFF
        || header0.length > Integer.MAX_VALUE - header1.length - 6) {
      return null;
    }
    byte[] extraData = new byte[header0.length + header1.length + 6];
    extraData[0] = (byte) (header0.length >> 8);
    extraData[1] = (byte) (header0.length & 0xFF);
    System.arraycopy(header0, 0, extraData, 2, header0.length);
    extraData[header0.length + 2] = 0;
    extraData[header0.length + 3] = 0;
    extraData[header0.length + 4] = (byte) (header1.length >> 8);
    extraData[header0.length + 5] = (byte) (header1.length & 0xFF);
    System.arraycopy(header1, 0, extraData, header0.length + 6, header1.length);
    return extraData;
  }

  @Nullable
  private static byte[] getFlacExtraData(List<byte[]> initializationData) {
    for (int i = 0; i < initializationData.size(); i++) {
      byte[] out = extractFlacStreamInfo(initializationData.get(i));
      if (out != null) {
        return out;
      }
    }
    return null;
  }

  @Nullable
  private static byte[] extractFlacStreamInfo(byte[] data) {
    int offset = 0;
    if (arrayStartsWith(data, FLAC_STREAM_MARKER)) {
      offset = FLAC_STREAM_MARKER.length;
    }
    if (data.length - offset == FLAC_STREAM_INFO_DATA_SIZE) {
      byte[] streamInfo = new byte[FLAC_STREAM_INFO_DATA_SIZE];
      System.arraycopy(data, offset, streamInfo, 0, FLAC_STREAM_INFO_DATA_SIZE);
      return streamInfo;
    }
    if (data.length >= offset + FLAC_METADATA_BLOCK_HEADER_SIZE) {
      int type = data[offset] & 0x7F;
      int length =
          ((data[offset + 1] & 0xFF) << 16)
              | ((data[offset + 2] & 0xFF) << 8)
              | (data[offset + 3] & 0xFF);
      if (type == FLAC_METADATA_TYPE_STREAM_INFO
          && length == FLAC_STREAM_INFO_DATA_SIZE
          && data.length >= offset + FLAC_METADATA_BLOCK_HEADER_SIZE + FLAC_STREAM_INFO_DATA_SIZE) {
        byte[] streamInfo = new byte[FLAC_STREAM_INFO_DATA_SIZE];
        System.arraycopy(
            data,
            offset + FLAC_METADATA_BLOCK_HEADER_SIZE,
            streamInfo,
            0,
            FLAC_STREAM_INFO_DATA_SIZE);
        return streamInfo;
      }
    }
    return null;
  }

  @Nullable
  private static byte[] firstNonEmpty(List<byte[]> initializationData) {
    for (int i = 0; i < initializationData.size(); i++) {
      byte[] data = initializationData.get(i);
      if (data.length > 0) {
        return data;
      }
    }
    return null;
  }

  @Nullable
  private static byte[] firstEntryOrNull(List<byte[]> initializationData) {
    if (initializationData.isEmpty()) {
      return null;
    }
    byte[] data = initializationData.get(0);
    return data.length > 0 ? data : null;
  }

  private static boolean arrayStartsWith(byte[] data, byte[] prefix) {
    if (data.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (data[i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }
}
