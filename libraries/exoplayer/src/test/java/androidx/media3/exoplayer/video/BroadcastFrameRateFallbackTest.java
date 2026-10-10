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
package androidx.media3.exoplayer.video;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** 广播电视制式兜底帧率的国家对应关系。 */
@RunWith(AndroidJUnit4.class)
public final class BroadcastFrameRateFallbackTest {

  @Test
  public void palCountriesFallBackTo25() {
    for (String country :
        new String[] {"CN", "HK", "MO", "VN", "GB", "FR", "DE", "RU", "AU", "IN", "AR", "PY", "UY"}) {
      assertThat(BroadcastFrameRateFallback.frameRateFor(country))
          .isEqualTo(BroadcastFrameRateFallback.PAL_FPS);
    }
  }

  @Test
  public void ntscCountriesFallBackTo2997() {
    for (String country :
        new String[] {"US", "CA", "MX", "JP", "KR", "TW", "PH", "BR", "CL", "CO", "CU", "JM"}) {
      assertThat(BroadcastFrameRateFallback.frameRateFor(country))
          .isEqualTo(BroadcastFrameRateFallback.NTSC_FPS);
    }
  }

  @Test
  public void unknownCountryFallsBackToPal() {
    assertThat(BroadcastFrameRateFallback.frameRateFor(null))
        .isEqualTo(BroadcastFrameRateFallback.PAL_FPS);
    assertThat(BroadcastFrameRateFallback.frameRateFor(""))
        .isEqualTo(BroadcastFrameRateFallback.PAL_FPS);
  }

  @Test
  public void countryCodeIsCaseInsensitive() {
    assertThat(BroadcastFrameRateFallback.frameRateFor("us"))
        .isEqualTo(BroadcastFrameRateFallback.NTSC_FPS);
    assertThat(BroadcastFrameRateFallback.frameRateFor("cn"))
        .isEqualTo(BroadcastFrameRateFallback.PAL_FPS);
  }
}
