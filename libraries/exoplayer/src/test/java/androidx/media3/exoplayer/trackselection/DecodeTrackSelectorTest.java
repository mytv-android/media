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
package androidx.media3.exoplayer.trackselection;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.RendererCapabilities.AdaptiveSupport;
import androidx.media3.exoplayer.RendererCapabilities.Capabilities;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.test.utils.FakeTimeline;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link DecodeTrackSelector}. */
@RunWith(AndroidJUnit4.class)
public final class DecodeTrackSelectorTest {

  private static final Timeline TIMELINE = new FakeTimeline();
  private static final MediaPeriodId PERIOD_ID =
      new MediaPeriodId(TIMELINE.getUidOfPeriod(/* periodIndex= */ 0));
  private static final TrackGroupArray VIDEO_TRACK_GROUPS =
      new TrackGroupArray(
          new TrackGroup(new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()));

  private DecodeTrackSelector trackSelector;

  @Before
  public void setUp() {
    Context context = ApplicationProvider.getApplicationContext();
    trackSelector = new DecodeTrackSelector(context);
    trackSelector.init(/* listener= */ parameters -> {}, BandwidthMeter.NO_OP);
    trackSelector.setRendererDecodePreferences(C.DECODE_HARDWARE, C.DECODE_HARDWARE);
  }

  @After
  public void tearDown() {
    trackSelector.release();
  }

  @Test
  public void selectTracks_hardwareMode_selectsMediaCodec() throws Exception {
    RendererCapabilities mediaCodecRenderer =
        new FakeRendererCapabilities(
            "MediaCodecVideoRenderer", C.FORMAT_HANDLED);

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {mediaCodecRenderer},
            VIDEO_TRACK_GROUPS,
            PERIOD_ID,
            TIMELINE);

    assertThat(result.selections[0]).isNotNull();
  }

  @Test
  public void selectTracks_softwareMode_keepsVideoOnMediaCodec() throws Exception {
    RendererCapabilities mediaCodecRenderer =
        new FakeRendererCapabilities("MediaCodecVideoRenderer", C.FORMAT_HANDLED);
    trackSelector.setRendererDecodePreferences(C.DECODE_SOFTWARE, C.DECODE_SOFTWARE);

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {mediaCodecRenderer},
            VIDEO_TRACK_GROUPS,
            PERIOD_ID,
            TIMELINE);

    assertThat(result.selections[0]).isNotNull();
  }

  private static final class FakeRendererCapabilities implements RendererCapabilities {

    private final String name;
    private final @C.FormatSupport int formatSupport;

    private FakeRendererCapabilities(String name, @C.FormatSupport int formatSupport) {
      this.name = name;
      this.formatSupport = formatSupport;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public int getTrackType() {
      return C.TRACK_TYPE_VIDEO;
    }

    @Override
    public @Capabilities int supportsFormat(Format format) {
      return RendererCapabilities.create(formatSupport);
    }

    @Override
    public @AdaptiveSupport int supportsMixedMimeTypeAdaptation() {
      return ADAPTIVE_NOT_SUPPORTED;
    }
  }
}
