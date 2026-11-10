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
package androidx.media3.extractor.ts;

import static com.google.common.base.Preconditions.checkNotNull;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.container.NalUnitUtil;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;
import java.util.Collections;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/** Parses a continuous AVS3-P2 byte stream and extracts individual frames. */
@UnstableApi
public final class Avs3Reader implements ElementaryStreamReader {

  private static final int START_CODE_SEQ_HEADER = 0xB0;
  private static final int START_CODE_INTRA_PIC = 0xB3;
  private static final int START_CODE_INTER_PIC = 0xB6;

  private static final int[] FRAME_RATE_NUMERATOR = {
    0, 24000, 24, 25, 30000, 30, 50, 60000, 60, 100, 120, 200, 240, 300, 0, 0
  };
  private static final int[] FRAME_RATE_DENOMINATOR = {
    0, 1001, 1, 1, 1001, 1, 1, 1001, 1, 1, 1, 1, 1, 1, 1, 1
  };

  private final String containerMimeType;

  private @MonotonicNonNull String formatId;
  private @MonotonicNonNull TrackOutput output;
  private @MonotonicNonNull SampleReader sampleReader;

  // State that should not be reset on seek.
  private boolean hasOutputFormat;

  // State that should be reset on seek.
  private final boolean[] prefixFlags;
  private final NalUnitTargetBuffer sequenceHeader;
  private long totalBytesWritten;

  // Per packet state that gets reset at the start of each packet.
  private long pesTimeUs;

  /**
   * @param containerMimeType The MIME type of the container holding the stream.
   */
  public Avs3Reader(String containerMimeType) {
    this.containerMimeType = containerMimeType;
    prefixFlags = new boolean[3];
    sequenceHeader = new NalUnitTargetBuffer(START_CODE_SEQ_HEADER, 128);
    pesTimeUs = C.TIME_UNSET;
  }

  @Override
  public void seek() {
    totalBytesWritten = 0;
    pesTimeUs = C.TIME_UNSET;
    NalUnitUtil.clearPrefixFlags(prefixFlags);
    sequenceHeader.reset();
    if (sampleReader != null) {
      sampleReader.reset();
    }
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    formatId = idGenerator.getFormatId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_VIDEO);
    sampleReader = new SampleReader(output);
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    this.pesTimeUs = pesTimeUs;
  }

  @Override
  public void consume(ParsableByteArray data) {
    assertTracksCreated();

    while (data.bytesLeft() > 0) {
      int offset = data.getPosition();
      int limit = data.limit();
      byte[] dataArray = data.getData();

      totalBytesWritten += data.bytesLeft();
      output.sampleData(data, data.bytesLeft());

      while (offset < limit) {
        int nalUnitOffset = NalUnitUtil.findNalUnit(dataArray, offset, limit, prefixFlags);

        if (nalUnitOffset == limit) {
          nalUnitData(dataArray, offset, limit);
          return;
        }

        // Case of a 4 byte start code prefix 0x00000001, recoil the NAL unit offset by one byte
        // to avoid the previous byte being assigned to the previous access unit.
        int prefixSize = 3;
        if (nalUnitOffset > 0 && dataArray[nalUnitOffset - 1] == 0x00) {
          nalUnitOffset--;
          prefixSize = 4;
        }

        int lengthToNalUnit = nalUnitOffset - offset;
        if (lengthToNalUnit > 0) {
          nalUnitData(dataArray, offset, nalUnitOffset);
        }

        int bytesWrittenPastPosition = limit - nalUnitOffset;
        long absolutePosition = totalBytesWritten - bytesWrittenPastPosition;
        int startCodeOffset = nalUnitOffset + prefixSize;
        // A start code split across the end of this buffer is left to the next call; the byte is
        // only used to classify the unit, so an unknown value is harmless for sample extraction.
        int startCode = startCodeOffset < limit ? dataArray[startCodeOffset] & 0xFF : -1;

        endNalUnit(lengthToNalUnit < 0 ? -lengthToNalUnit : 0);
        startNalUnit(absolutePosition, bytesWrittenPastPosition, startCode, pesTimeUs);
        // Keep the start code value in the data appended to the current unit: NalUnitTargetBuffer
        // only synthesizes the 3 byte start code prefix.
        offset = startCodeOffset;
      }
    }
  }

  @Override
  public void endOfInputReached() {
    assertTracksCreated();
    endNalUnit(0);
    sampleReader.endSample(totalBytesWritten);
  }

  @RequiresNonNull("sampleReader")
  private void startNalUnit(long position, int offset, int startCode, long pesTimeUs) {
    sampleReader.startNalUnit(position, offset, startCode, pesTimeUs, hasOutputFormat);
    if (!hasOutputFormat) {
      sequenceHeader.startNalUnit(startCode);
    }
  }

  private void nalUnitData(byte[] dataArray, int offset, int limit) {
    if (!hasOutputFormat) {
      sequenceHeader.appendToNalUnit(dataArray, offset, limit);
    }
  }

  @RequiresNonNull("output")
  private void endNalUnit(int discardPadding) {
    if (!hasOutputFormat && sequenceHeader.endNalUnit(discardPadding)) {
      @Nullable Format format = parseMediaFormat(sequenceHeader.nalData, sequenceHeader.nalLength);
      if (format != null) {
        output.format(format);
        hasOutputFormat = true;
      }
    }
  }

  @Nullable
  private Format parseMediaFormat(byte[] csdData, int csdLength) {
    // The parsed fields (up to frame_rate_code) need 13 bytes including the start code.
    if (csdLength < 13) {
      return null;
    }
    ParsableBitArray bits = new ParsableBitArray(csdData, csdLength);
    bits.skipBits(32);
    int profile = bits.readBits(8);
    int level = bits.readBits(8);
    bits.skipBits(1); // progressive_sequence
    bits.skipBits(1); // field_coded_sequence
    bits.skipBits(1); // library_stream_flag
    bits.skipBits(1); // library_picture_enable_flag
    bits.skipBits(1); // marker_bit
    int width = bits.readBits(14);
    bits.skipBits(1); // marker_bit
    int height = bits.readBits(14);
    bits.skipBits(2); // chroma_format
    int samplePrecision = bits.readBits(3);
    if (profile == 0x22) {
      bits.skipBits(3); // encoding_precision
    }
    bits.skipBits(1); // marker_bit
    bits.skipBits(4); // aspect_ratio
    int frameRateCode = bits.readBits(4);
    if (width <= 0 || height <= 0) {
      return null;
    }
    int bitDepth = samplePrecision == 2 ? 10 : 8;
    float frameRate = Format.NO_VALUE;
    if (frameRateCode > 0
        && frameRateCode < FRAME_RATE_NUMERATOR.length
        && FRAME_RATE_NUMERATOR[frameRateCode] > 0) {
      frameRate =
          (float) FRAME_RATE_NUMERATOR[frameRateCode] / FRAME_RATE_DENOMINATOR[frameRateCode];
    }
    byte[] initializationData = new byte[csdLength];
    System.arraycopy(csdData, 0, initializationData, 0, csdLength);

    return new Format.Builder()
        .setId(formatId)
        .setContainerMimeType(containerMimeType)
        .setSampleMimeType(MimeTypes.VIDEO_AVS3)
        .setCodecs(Util.formatInvariant("avs3.%02X.%02X", profile, level))
        .setWidth(width)
        .setHeight(height)
        .setFrameRate(frameRate)
        .setColorInfo(
            new ColorInfo.Builder()
                .setLumaBitdepth(bitDepth)
                .setChromaBitdepth(bitDepth)
                .build())
        .setInitializationData(Collections.singletonList(initializationData))
        .build();
  }

  @EnsuresNonNull({"output", "sampleReader"})
  private void assertTracksCreated() {
    checkNotNull(output);
    Util.castNonNull(sampleReader);
  }

  private static final class SampleReader {

    private final TrackOutput output;

    private long nalUnitPosition;

    private boolean readingSample;
    private long samplePosition;
    private long sampleTimeUs;
    private boolean sampleIsKeyframe;

    public SampleReader(TrackOutput output) {
      this.output = output;
    }

    public void reset() {
      readingSample = false;
    }

    public void startNalUnit(
        long position, int offset, int startCode, long pesTimeUs, boolean hasOutputFormat) {
      nalUnitPosition = position;

      boolean isPictureStart =
          startCode == START_CODE_INTRA_PIC || startCode == START_CODE_INTER_PIC;
      if (!isPictureStart && startCode != START_CODE_SEQ_HEADER) {
        return;
      }
      if (readingSample && hasOutputFormat) {
        outputSample(offset);
      }
      readingSample = false;
      if (isPictureStart) {
        samplePosition = position;
        sampleTimeUs = pesTimeUs;
        sampleIsKeyframe = startCode == START_CODE_INTRA_PIC;
        readingSample = true;
      }
    }

    public void endSample(long position) {
      if (!readingSample) {
        return;
      }
      nalUnitPosition = position;
      outputSample(0);
      readingSample = false;
    }

    private void outputSample(int offset) {
      if (sampleTimeUs == C.TIME_UNSET || nalUnitPosition == samplePosition) {
        return;
      }
      @C.BufferFlags int flags = sampleIsKeyframe ? C.BUFFER_FLAG_KEY_FRAME : 0;
      int size = (int) (nalUnitPosition - samplePosition);
      output.sampleMetadata(sampleTimeUs, flags, size, offset, null);
    }
  }
}
