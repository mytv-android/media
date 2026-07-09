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
package androidx.media3.extractor;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.MimeTypes;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link DtsUtil}. */
@RunWith(AndroidJUnit4.class)
public final class DtsUtilTest {

  @Test
  public void getDtsXCodecs_withDtsXSyncWord_returnsDtsXMarker() {
    byte[] data = new byte[] {0x00, 0x02, 0x00, 0x08, 0x50, 0x00};

    assertThat(DtsUtil.getDtsXCodecs(data, /* offset= */ 1, /* length= */ 4))
        .isEqualTo(MimeTypes.CODEC_DTS_HD_MA_X);
  }

  @Test
  public void getDtsXCodecs_withImaxSyncWord_returnsImaxMarker() {
    assertThat(DtsUtil.getDtsXCodecs(0xF14000D0))
        .isEqualTo(MimeTypes.CODEC_DTS_HD_MA_X_IMAX);
  }
}
