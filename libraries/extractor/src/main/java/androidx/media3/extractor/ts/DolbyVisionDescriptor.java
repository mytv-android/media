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

import androidx.annotation.Nullable;
import androidx.media3.common.util.CodecSpecificDataUtil;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.container.DolbyVisionConfig;

/** Dolby Vision video descriptor data from an MPEG-TS PMT ES loop. */
@UnstableApi
public final class DolbyVisionDescriptor {

  /** Parses a Dolby Vision video descriptor payload. */
  @Nullable
  public static DolbyVisionDescriptor parse(ParsableByteArray data, int descriptorEnd) {
    if (descriptorEnd > data.limit() || descriptorEnd - data.getPosition() < 4) {
      return null;
    }
    int dvVersionMajor = data.readUnsignedByte();
    int dvVersionMinor = data.readUnsignedByte();
    int profileLevelAndFlags = data.readUnsignedShort();
    int profile = (profileLevelAndFlags >> 9) & 0x7F;
    int level = (profileLevelAndFlags >> 3) & 0x3F;
    boolean rpuPresentFlag = ((profileLevelAndFlags >> 2) & 0x01) != 0;
    boolean elPresentFlag = ((profileLevelAndFlags >> 1) & 0x01) != 0;
    boolean blPresentFlag = (profileLevelAndFlags & 0x01) != 0;
    int dependencyPid = -1;
    if (!blPresentFlag && descriptorEnd - data.getPosition() >= 2) {
      dependencyPid = data.readUnsignedShort() >> 3;
    }
    int blSignalCompatibilityId = 0;
    int mdCompression = 0;
    if (descriptorEnd - data.getPosition() >= 1) {
      int flagsByte = data.readUnsignedByte();
      blSignalCompatibilityId = (flagsByte >> 4) & 0xF;
      mdCompression = (flagsByte >> 2) & 0x3;
    }
    byte[] dolbyVisionConfigBytes =
        new byte[] {
          (byte) dvVersionMajor,
          (byte) dvVersionMinor,
          (byte) (profileLevelAndFlags >> 8),
          (byte) profileLevelAndFlags
        };
    @Nullable
    DolbyVisionConfig dolbyVisionConfig =
        DolbyVisionConfig.parse(new ParsableByteArray(dolbyVisionConfigBytes));
    return new DolbyVisionDescriptor(
        dvVersionMajor,
        dvVersionMinor,
        profile,
        level,
        rpuPresentFlag,
        elPresentFlag,
        blPresentFlag,
        dependencyPid,
        blSignalCompatibilityId,
        mdCompression,
        dolbyVisionConfig);
  }

  /** Parses a Dolby Vision video descriptor from PMT descriptor bytes, if present. */
  @Nullable
  public static DolbyVisionDescriptor parseFromDescriptorBytes(byte[] descriptorBytes) {
    ParsableByteArray data = new ParsableByteArray(descriptorBytes);
    while (data.bytesLeft() >= 2) {
      int tag = data.readUnsignedByte();
      int length = data.readUnsignedByte();
      if (length > data.bytesLeft()) {
        return null;
      }
      int descriptorEnd = data.getPosition() + length;
      if (tag == 0xB0) {
        return parse(data, descriptorEnd);
      }
      data.setPosition(descriptorEnd);
    }
    return null;
  }

  /** Builds a dvcC/dvvC-compatible initialization data record from complete descriptor fields. */
  public static byte[] buildInitializationData(
      int dvVersionMajor,
      int dvVersionMinor,
      int profile,
      int level,
      boolean rpuPresentFlag,
      boolean elPresentFlag,
      boolean blPresentFlag,
      int blSignalCompatibilityId,
      int mdCompression) {
    byte[] dolbyVisionCsd =
        CodecSpecificDataUtil.buildDolbyVisionInitializationData(
            profile, level, blSignalCompatibilityId, mdCompression);
    dolbyVisionCsd[0] = (byte) dvVersionMajor;
    dolbyVisionCsd[1] = (byte) dvVersionMinor;
    dolbyVisionCsd[2] = (byte) (((profile & 0x7F) << 1) | ((level >> 5) & 0x1));
    dolbyVisionCsd[3] =
        (byte)
            (((level & 0x1F) << 3)
                | ((rpuPresentFlag ? 1 : 0) << 2)
                | ((elPresentFlag ? 1 : 0) << 1)
                | (blPresentFlag ? 1 : 0));
    return dolbyVisionCsd;
  }

  /** Builds a dvcC/dvvC-compatible initialization data record from this descriptor. */
  public byte[] buildInitializationData() {
    return buildInitializationData(
        dvVersionMajor,
        dvVersionMinor,
        profile,
        level,
        rpuPresentFlag,
        elPresentFlag,
        blPresentFlag,
        blSignalCompatibilityId,
        mdCompression);
  }

  /** Builds a Dolby Vision configuration record, if configuration is present. */
  @Nullable
  public static byte[] buildInitializationData(
      @Nullable DolbyVisionConfig dolbyVisionConfig,
      @Nullable DolbyVisionDescriptor dolbyVisionDescriptor) {
    if (dolbyVisionConfig == null) {
      return null;
    }
    return dolbyVisionDescriptor != null
        ? dolbyVisionDescriptor.buildInitializationData()
        : CodecSpecificDataUtil.buildDolbyVisionInitializationData(
            dolbyVisionConfig.profile, dolbyVisionConfig.level);
  }

  public final int dvVersionMajor;
  public final int dvVersionMinor;
  public final int profile;
  public final int level;
  public final boolean rpuPresentFlag;
  public final boolean elPresentFlag;
  public final boolean blPresentFlag;
  public final int dependencyPid;
  public final int blSignalCompatibilityId;
  public final int mdCompression;
  @Nullable public final DolbyVisionConfig dolbyVisionConfig;

  private DolbyVisionDescriptor(
      int dvVersionMajor,
      int dvVersionMinor,
      int profile,
      int level,
      boolean rpuPresentFlag,
      boolean elPresentFlag,
      boolean blPresentFlag,
      int dependencyPid,
      int blSignalCompatibilityId,
      int mdCompression,
      @Nullable DolbyVisionConfig dolbyVisionConfig) {
    this.dvVersionMajor = dvVersionMajor;
    this.dvVersionMinor = dvVersionMinor;
    this.profile = profile;
    this.level = level;
    this.rpuPresentFlag = rpuPresentFlag;
    this.elPresentFlag = elPresentFlag;
    this.blPresentFlag = blPresentFlag;
    this.dependencyPid = dependencyPid;
    this.blSignalCompatibilityId = blSignalCompatibilityId;
    this.mdCompression = mdCompression;
    this.dolbyVisionConfig = dolbyVisionConfig;
  }
}
