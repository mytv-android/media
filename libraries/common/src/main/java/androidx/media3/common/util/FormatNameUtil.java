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
package androidx.media3.common.util;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;

/** Utility methods for building user-readable names from {@link Format} fields. */
@UnstableApi
public final class FormatNameUtil {

  /** Returns a user-readable display name for {@link Format#sampleMimeType}. */
  public static String getSampleMimeTypeDisplayName(Format format) {
    String mimeType = format.sampleMimeType;
    if (mimeType == null || mimeType.isEmpty()) {
      return "";
    }
    switch (mimeType) {
      case MimeTypes.AUDIO_AAC:
        return getAacDisplayName(format);
      case MimeTypes.AUDIO_AC3:
        return "AC-3";
      case MimeTypes.AUDIO_AC4:
        return "AC-4";
      case MimeTypes.AUDIO_ALAC:
        return "ALAC";
      case MimeTypes.AUDIO_ALAW:
        return "ALAW";
      case MimeTypes.AUDIO_AMR:
        return "AMR";
      case MimeTypes.AUDIO_AMR_NB:
        return "AMR-NB";
      case MimeTypes.AUDIO_AMR_WB:
        return "AMR-WB";
      case MimeTypes.AUDIO_ATRAC3:
        return "ATRAC3";
      case MimeTypes.AUDIO_ATRAC3P:
        return "ATRAC3+";
      case MimeTypes.AUDIO_AV3A:
        return "AV3A";
      case MimeTypes.AUDIO_COOK:
        return "COOK";
      case MimeTypes.AUDIO_DSD:
      case MimeTypes.AUDIO_DSD_LSBF_PLANAR:
      case MimeTypes.AUDIO_DSD_MSBF_PLANAR:
        return "DSD";
      case MimeTypes.AUDIO_DST:
        return "DST";
      case MimeTypes.AUDIO_DTS:
        return "DTS";
      case MimeTypes.AUDIO_DTS_HD:
        return "DTS-HD";
      case MimeTypes.AUDIO_DTS_HD_MA:
      case MimeTypes.AUDIO_MEDIA3_DTS_HD_MA_CORELESS:
        return getDtsHdMaDisplayName(format);
      case MimeTypes.AUDIO_DTS_EXPRESS:
        return "DTS-Express";
      case MimeTypes.AUDIO_DTS_UHD_P2:
        return "DTS-UHD";
      case MimeTypes.AUDIO_E_AC3:
        return "E-AC-3";
      case MimeTypes.AUDIO_E_AC3_JOC:
        return "E-AC-3 JOC";
      case MimeTypes.AUDIO_FLAC:
        return "FLAC";
      case MimeTypes.AUDIO_IAMF:
        return "IAMF";
      case MimeTypes.AUDIO_MIDI:
        return "MIDI";
      case MimeTypes.AUDIO_MLAW:
        return "MLAW";
      case MimeTypes.AUDIO_MPEG_L1:
        return "MP1";
      case MimeTypes.AUDIO_MPEG_L2:
        return "MP2";
      case MimeTypes.AUDIO_MPEG:
        return "MP3";
      case MimeTypes.AUDIO_MPEGH_MHA1:
      case MimeTypes.AUDIO_MPEGH_MHM1:
        return "MPEG-H";
      case MimeTypes.AUDIO_MSGSM:
        return "GSM";
      case MimeTypes.AUDIO_OGG:
        return "OGG";
      case MimeTypes.AUDIO_OPUS:
        return "Opus";
      case MimeTypes.AUDIO_RAW:
        return getPcmDisplayName(format);
      case MimeTypes.AUDIO_RALF:
        return "RALF";
      case MimeTypes.AUDIO_SIPR:
        return "SIPR";
      case MimeTypes.AUDIO_TRUEHD:
        return getTrueHdDisplayName(format);
      case MimeTypes.AUDIO_VORBIS:
        return "Vorbis";
      case MimeTypes.AUDIO_WAV:
        return "WAV";
      case MimeTypes.AUDIO_WMA:
        return "WMA";
      case MimeTypes.AUDIO_WMA1:
        return "WMA1";
      case MimeTypes.AUDIO_WMA2:
        return "WMA2";
      case MimeTypes.AUDIO_WMA_LOSSLESS:
        return "WMA Lossless";
      case MimeTypes.AUDIO_WMA_PRO:
        return "WMA Pro";
      case MimeTypes.AUDIO_WMA_VOICE:
        return "WMA Voice";
      case MimeTypes.VIDEO_APV:
        return "APV";
      case MimeTypes.VIDEO_AV1:
        return joinWithSeparator("AV1", getHdrDisplayName(format));
      case MimeTypes.VIDEO_AVI:
        return "AVI";
      case MimeTypes.VIDEO_DIVX:
        return "DIVX";
      case MimeTypes.VIDEO_DOLBY_VISION:
        return getDolbyVisionDisplayName(format);
      case MimeTypes.VIDEO_FLV:
        return "FLV";
      case MimeTypes.VIDEO_H263:
        return "H.263";
      case MimeTypes.VIDEO_H264:
        return joinWithSeparator("H.264", getHdrDisplayName(format));
      case MimeTypes.VIDEO_H265:
        return joinWithSeparator("H.265", getHdrDisplayName(format));
      case MimeTypes.VIDEO_H266:
        return "H.266";
      case MimeTypes.VIDEO_MJPEG:
        return "MJPEG";
      case MimeTypes.VIDEO_MP4:
        return "MP4";
      case MimeTypes.VIDEO_MP4V:
        return "MPEG-4";
      case MimeTypes.VIDEO_MP42:
        return "MP42";
      case MimeTypes.VIDEO_MP43:
        return "MP43";
      case MimeTypes.VIDEO_MPEG:
        return "MPEG";
      case MimeTypes.VIDEO_MPEG2:
        return "MPEG-2";
      case MimeTypes.VIDEO_PS:
        return "MPEG-2 PS";
      case MimeTypes.VIDEO_RV10:
        return "RV10";
      case MimeTypes.VIDEO_RV20:
        return "RV20";
      case MimeTypes.VIDEO_RV30:
        return "RV30";
      case MimeTypes.VIDEO_RV40:
        return "RV40";
      case MimeTypes.VIDEO_VC1:
        return "VC-1";
      case MimeTypes.VIDEO_VP8:
        return "VP8";
      case MimeTypes.VIDEO_VP9:
        return joinWithSeparator("VP9", getHdrDisplayName(format));
      case MimeTypes.VIDEO_WMV:
        return "WMV";
      case MimeTypes.VIDEO_WMV1:
        return "WMV1";
      case MimeTypes.VIDEO_WMV2:
        return "WMV2";
      case MimeTypes.VIDEO_QUICK_TIME:
        return "MOV";
      case MimeTypes.TEXT_SSA:
        return "SSA";
      case MimeTypes.TEXT_VTT:
        return "VTT";
      case MimeTypes.APPLICATION_DVBSUBS:
        return "DVB";
      case MimeTypes.APPLICATION_CEA608:
      case MimeTypes.APPLICATION_MP4CEA608:
        return "CEA-608";
      case MimeTypes.APPLICATION_CEA708:
        return "CEA-708";
      case MimeTypes.APPLICATION_MP4VTT:
        return "VTT";
      case MimeTypes.APPLICATION_PGS:
        return "PGS";
      case MimeTypes.APPLICATION_SUBRIP:
        return "SRT";
      case MimeTypes.APPLICATION_TTML:
        return "TTML";
      case MimeTypes.APPLICATION_TX3G:
        return "TX3G";
      case MimeTypes.APPLICATION_VOBSUB:
        return "VobSub";
      default:
        return mimeType;
    }
  }

  private static String getAacDisplayName(Format format) {
    @Nullable String aacCodecs = MimeTypes.getCodecsCorrespondingToMimeType(format.codecs, MimeTypes.AUDIO_AAC);
    if (aacCodecs != null) {
      String[] parts = Util.splitCodecs(aacCodecs)[0].split("\\.");
      if (parts.length >= 3) {
        try {
          switch (Integer.parseInt(parts[2])) {
            case 2:
              return "AAC-LC";
            case 5:
              return "HE-AAC";
            case 29:
              return "HE-AAC v2";
            case 42:
              return "xHE-AAC";
            default:
              break;
          }
        } catch (NumberFormatException e) {
          // Fall through.
        }
      }
    }
    return "AAC";
  }

  private static String getPcmDisplayName(Format format) {
    switch (format.pcmEncoding) {
      case C.ENCODING_PCM_8BIT:
        return "LPCM 8-bit";
      case C.ENCODING_PCM_16BIT:
      case C.ENCODING_PCM_16BIT_BIG_ENDIAN:
        return "LPCM 16-bit";
      case C.ENCODING_PCM_24BIT:
      case C.ENCODING_PCM_24BIT_BIG_ENDIAN:
        return "LPCM 24-bit";
      case C.ENCODING_PCM_32BIT:
      case C.ENCODING_PCM_32BIT_BIG_ENDIAN:
        return "LPCM 32-bit";
      case C.ENCODING_PCM_FLOAT:
        return "PCM Float";
      case C.ENCODING_PCM_DOUBLE:
        return "PCM Double";
      default:
        return "PCM";
    }
  }

  private static String getTrueHdDisplayName(Format format) {
    return "atmos".equals(format.codecs) ? "TrueHD + Atmos" : "TrueHD";
  }

  private static String getDtsHdMaDisplayName(Format format) {
    if (MimeTypes.CODEC_DTS_HD_MA_X_IMAX.equals(format.codecs)) {
      return "DTS-HD MA + DTS:X IMAX";
    }
    return MimeTypes.CODEC_DTS_HD_MA_X.equals(format.codecs)
        ? "DTS-HD MA + DTS:X"
        : "DTS-HD MA";
  }

  private static String getDolbyVisionDisplayName(Format format) {
    String codecs = format.codecs;
    if (codecs != null) {
      String[] parts = codecs.split("\\.");
      if (parts.length >= 2) {
        try {
          int profile = Integer.parseInt(parts[1]);
          return "Dolby Vision Profile " + profile;
        } catch (NumberFormatException e) {
          // Fall through.
        }
      }
    }
    return "Dolby Vision";
  }

  private static String getHdrDisplayName(Format format) {
    @Nullable ColorInfo colorInfo = format.colorInfo;
    if (colorInfo == null) {
      return "";
    }
    if (colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG) {
      return "HLG";
    }
    if (colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084) {
      return "HDR10";
    }
    return "";
  }

  private static String joinWithSeparator(String... items) {
    String itemList = "";
    for (String item : items) {
      if (item.isEmpty()) {
        continue;
      }
      itemList = itemList.isEmpty() ? item : itemList + ", " + item;
    }
    return itemList;
  }
}
