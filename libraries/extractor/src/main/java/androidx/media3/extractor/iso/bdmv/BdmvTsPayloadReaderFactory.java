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
package androidx.media3.extractor.iso.bdmv;

import android.util.SparseArray;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.extractor.ts.DolbyVisionDescriptor;
import androidx.media3.extractor.ts.TsExtractor;
import androidx.media3.extractor.ts.TsPayloadReader;
import com.google.common.collect.ImmutableList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

public final class BdmvTsPayloadReaderFactory implements TsPayloadReader.Factory {

  private final DefaultTsPayloadReaderFactory delegate;
  private final Map<Integer, BdmvStreamContext> streamContextsByPid;
  private final Map<Integer, Queue<BdmvStreamContext>> streamContextQueuesByType;

  public BdmvTsPayloadReaderFactory(@Nullable ClpiInfo clpi) {
    delegate = new DefaultTsPayloadReaderFactory(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS | DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM, ImmutableList.of());
    streamContextsByPid = new HashMap<>();
    streamContextQueuesByType = new HashMap<>();
    buildStreamContexts(clpi);
    int inferredDvProfile = inferDvProfileFromClpi(clpi);
    if (inferredDvProfile > 0) {
      for (BdmvStreamContext context : streamContextsByPid.values()) {
        if (context.dynamicRangeType == BdmvConstants.DYNAMIC_RANGE_DOLBY_VISION) {
          context.setInferredDolbyVisionProfile(inferredDvProfile);
        }
      }
    }
  }

  private void buildStreamContexts(@Nullable ClpiInfo clpi) {
    if (clpi != null) {
      for (StreamInfo stream : clpi.streams) {
        BdmvStreamContext context = new BdmvStreamContext(stream.pid, stream.streamType, stream.languageCode, stream.dynamicRangeType);
        streamContextsByPid.put(stream.pid, context);
        int streamType = remapHdmvStreamType(stream.streamType);
        Queue<BdmvStreamContext> queue = streamContextQueuesByType.get(streamType);
        if (queue == null) {
          streamContextQueuesByType.put(streamType, queue = new ArrayDeque<>());
        }
        queue.add(context);
      }
    }
  }

  private static int inferDvProfileFromClpi(@Nullable ClpiInfo clpi) {
    if (clpi == null) {
      return -1;
    }
    boolean hasDvStream = false;
    int blStreamType = 0;
    for (StreamInfo stream : clpi.streams) {
      if (BdmvConstants.isVideoStreamType(stream.streamType)) {
        if (stream.dynamicRangeType == BdmvConstants.DYNAMIC_RANGE_DOLBY_VISION) {
          hasDvStream = true;
        } else if (blStreamType == 0) {
          blStreamType = stream.streamType;
        }
      }
    }
    if (hasDvStream && blStreamType != 0) {
      return (blStreamType == BdmvConstants.STREAM_TYPE_AVC) ? 4 : 7;
    }
    return -1;
  }

  private static int remapHdmvStreamType(int streamType) {
    if (streamType == TsExtractor.TS_STREAM_TYPE_DC2_H262) {
      return TsExtractor.TS_STREAM_TYPE_HDMV_LPCM;
    } else if (streamType == TsExtractor.TS_STREAM_TYPE_HDMV_DTS) {
      return TsExtractor.TS_STREAM_TYPE_HDMV_DTS_AUTO;
    } else if (streamType == TsExtractor.TS_STREAM_TYPE_SPLICE_INFO) {
      return TsExtractor.TS_STREAM_TYPE_HDMV_DTS_HD_MASTER;
    }
    return streamType;
  }

  @Nullable
  BdmvStreamContext getStreamContext(int pid) {
    return streamContextsByPid.get(pid);
  }

  @NonNull
  @Override
  public SparseArray<TsPayloadReader> createInitialPayloadReaders() {
    return new SparseArray<>();
  }

  @Override
  @Nullable
  public TsPayloadReader createPayloadReader(int streamType, @NonNull TsPayloadReader.EsInfo esInfo) {
    return createPayloadReader(streamType, /* elementaryPid= */ -1, esInfo);
  }

  @Override
  @Nullable
  public TsPayloadReader createPayloadReader(int streamType, int elementaryPid, @NonNull TsPayloadReader.EsInfo esInfo) {
    @Nullable BdmvStreamContext context = resolveStreamContext(streamType, elementaryPid);
    if (context != null && (streamType == BdmvConstants.STREAM_TYPE_HEVC || streamType == BdmvConstants.STREAM_TYPE_AVC)) {
      @Nullable
      DolbyVisionDescriptor dolbyVisionDescriptor = DolbyVisionDescriptor.parseFromDescriptorBytes(esInfo.descriptorBytes);
      if (dolbyVisionDescriptor != null) {
        context.setDolbyVisionDescriptor(dolbyVisionDescriptor);
      }
    }
    String lang = resolveLanguage(context, esInfo);
    boolean stripGenericDvConfig = context != null && context.handlesDolbyVisionFormat() && esInfo.dolbyVisionConfig != null;
    if (lang != null || stripGenericDvConfig) {
      esInfo =
          new TsPayloadReader.EsInfo(
              esInfo.streamType,
              lang != null ? lang : esInfo.language,
              esInfo.audioType,
              esInfo.dvbSubtitleInfos.isEmpty() ? null : esInfo.dvbSubtitleInfos,
              esInfo.descriptorBytes,
              stripGenericDvConfig ? null : esInfo.dolbyVisionConfig);
    }
    return delegate.createPayloadReader(streamType, esInfo);
  }

  @Nullable
  private BdmvStreamContext resolveStreamContext(int streamType, int elementaryPid) {
    if (elementaryPid >= 0) {
      @Nullable BdmvStreamContext context = streamContextsByPid.get(elementaryPid);
      if (context != null) {
        return context;
      }
    }
    Queue<BdmvStreamContext> queue = streamContextQueuesByType.get(streamType);
    return queue != null ? queue.poll() : null;
  }

  @Nullable
  private static String resolveLanguage(@Nullable BdmvStreamContext context, TsPayloadReader.EsInfo esInfo) {
    if (esInfo.language != null && !esInfo.language.isEmpty()) {
      return null;
    }
    return context != null && context.hasLanguage() ? context.language : null;
  }
}
