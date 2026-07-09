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

import androidx.annotation.Nullable;
import androidx.media3.container.DolbyVisionConfig;
import androidx.media3.extractor.ts.DolbyVisionDescriptor;

final class BdmvStreamContext {

  private static final int DOLBY_VISION_VALUE_UNSET = -1;

  final int pid;
  final int streamType;
  final String language;
  final int dynamicRangeType;

  int dvVersionMajor;
  int dvVersionMinor;
  int dvProfile;
  int dvLevel;
  boolean rpuPresentFlag;
  boolean elPresentFlag;
  boolean blPresentFlag;
  int dependencyPid;
  int blSignalCompatibilityId;
  int mdCompression;
  @Nullable DolbyVisionConfig dolbyVisionConfig;
  boolean hasDolbyVisionDescriptor;
  boolean isDvEnhancementLayer;

  BdmvStreamContext(int pid, int streamType, String language, int dynamicRangeType) {
    this.pid = pid;
    this.streamType = streamType;
    this.language = language;
    this.dynamicRangeType = dynamicRangeType;
    dvProfile = DOLBY_VISION_VALUE_UNSET;
    dvLevel = DOLBY_VISION_VALUE_UNSET;
    dependencyPid = DOLBY_VISION_VALUE_UNSET;
    blSignalCompatibilityId = DOLBY_VISION_VALUE_UNSET;
    mdCompression = DOLBY_VISION_VALUE_UNSET;
  }

  boolean hasLanguage() {
    return !language.isEmpty();
  }

  boolean hasDolbyVision() {
    return dolbyVisionConfig != null || dvProfile > 0;
  }

  boolean isDolbyVisionEnhancementLayer() {
    return isDvEnhancementLayer;
  }

  void setInferredDolbyVisionProfile(int dvProfile) {
    if (this.dvProfile <= 0 && dvProfile > 0) {
      this.dvProfile = dvProfile;
      updateDolbyVisionEnhancementLayerFlag();
    }
  }

  void setDolbyVisionDescriptor(DolbyVisionDescriptor dolbyVisionDescriptor) {
    dvVersionMajor = dolbyVisionDescriptor.dvVersionMajor;
    dvVersionMinor = dolbyVisionDescriptor.dvVersionMinor;
    if (dolbyVisionDescriptor.profile > 0) {
      dvProfile = dolbyVisionDescriptor.profile;
    }
    if (dolbyVisionDescriptor.level > 0) {
      dvLevel = dolbyVisionDescriptor.level;
    }
    rpuPresentFlag = dolbyVisionDescriptor.rpuPresentFlag;
    elPresentFlag = dolbyVisionDescriptor.elPresentFlag;
    blPresentFlag = dolbyVisionDescriptor.blPresentFlag;
    dependencyPid = dolbyVisionDescriptor.dependencyPid;
    blSignalCompatibilityId = dolbyVisionDescriptor.blSignalCompatibilityId;
    mdCompression = dolbyVisionDescriptor.mdCompression;
    dolbyVisionConfig = dolbyVisionDescriptor.dolbyVisionConfig;
    hasDolbyVisionDescriptor = true;
    updateDolbyVisionEnhancementLayerFlag();
  }

  boolean handlesDolbyVisionFormat() {
    return dynamicRangeType == BdmvConstants.DYNAMIC_RANGE_DOLBY_VISION && hasDolbyVision();
  }

  private void updateDolbyVisionEnhancementLayerFlag() {
    if (dynamicRangeType != BdmvConstants.DYNAMIC_RANGE_DOLBY_VISION) {
      isDvEnhancementLayer = false;
      return;
    }
    if (hasDolbyVisionDescriptor) {
      isDvEnhancementLayer = dependencyPid >= 0 || (elPresentFlag && !blPresentFlag);
      return;
    }
    isDvEnhancementLayer = dvProfile == 4 || dvProfile == 7;
  }
}
