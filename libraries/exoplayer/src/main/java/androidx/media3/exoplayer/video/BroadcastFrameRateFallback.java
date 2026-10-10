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

import androidx.media3.common.util.UnstableApi;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 按设备所在国家的广播电视制式给出兜底帧率。
 *
 * <p>流没有给出帧率时，解码器配置里就不会带 {@code MediaFormat.KEY_FRAME_RATE}，部分盒子的 Codec2 会按
 * 30fps 默认。1080i 去隔行要靠这个帧率合场，30 对不上 25 帧的场序就会错帧卡顿，逐行内容不受影响。
 *
 * <p>制式与帧率的对应（依据中文维基「彩色電視廣播標準列表」）：NTSC 系（含巴西 PAL-M）场频 60Hz，帧率
 * 29.97；PAL、SECAM 及阿根廷/巴拉圭/乌拉圭的 PAL-N 场频 50Hz，帧率 25。表中未列出或标注不详的国家按
 * 50Hz 处理，与设备默认国家为空时一致。
 */
@UnstableApi
public final class BroadcastFrameRateFallback {

  /** NTSC 系（60Hz 场频）的帧率。 */
  public static final float NTSC_FPS = 29.97f;

  /** PAL / SECAM 系（50Hz 场频）的帧率。 */
  public static final float PAL_FPS = 25f;

  /**
   * 采用 NTSC 系 60Hz 场频的国家与地区，ISO 3166-1 alpha-2。其余国家按 PAL / SECAM 的 50Hz 处理。
   */
  private static final Set<String> NTSC_COUNTRIES =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  // 东亚（日本、韩国、菲律宾、中国台湾为 NTSC；越南、也门 PAL 与 NTSC 并存，按 PAL 处理）
                  "JP", "KR", "PH", "TW",
                  // 北美
                  "US", "CA", "MX",
                  // 中美
                  "BZ", "CR", "SV", "GT", "HN", "NI", "PA",
                  // 加勒比
                  "AG", "BS", "BB", "CU", "DM", "DO", "GD", "HT", "JM", "KN", "LC", "VC", "TT",
                  // 南美（巴西 PAL-M 同为 60Hz；阿根廷、巴拉圭、乌拉圭为 PAL-N，50Hz，不在此列）
                  "BR", "CL", "PE", "BO", "EC", "VE", "CO", "SR", "GY",
                  // 大洋洲
                  "MH", "NR", "PW")));

  private BroadcastFrameRateFallback() {}

  /** 设备国家采用的广播电视帧率；国家未知时按 PAL 的 25fps。 */
  public static float frameRateFor(String countryCode) {
    if (countryCode != null && NTSC_COUNTRIES.contains(countryCode.toUpperCase(Locale.ROOT))) {
      return NTSC_FPS;
    }
    return PAL_FPS;
  }
}
