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
package androidx.media3.exoplayer.source.iso;

import androidx.media3.common.C;
import androidx.media3.common.MediaChapter;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.iso.bdmv.BdmvConstants;
import androidx.media3.extractor.iso.bdmv.BdmvStructure;
import androidx.media3.extractor.iso.bdmv.ChapterMark;
import androidx.media3.extractor.iso.bdmv.PlayItem;
import androidx.media3.extractor.iso.bdmv.Playlist;
import androidx.media3.extractor.iso.dvd.DvdStructure;
import androidx.media3.extractor.iso.dvd.DvdTitle;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;

final class IsoChapterScanner {

  static ImmutableList<MediaChapter> buildDvdTitleChapters(DvdStructure dvd, int titleIndex) {
    if (titleIndex < 0 || titleIndex >= dvd.titles.size()) {
      return ImmutableList.of();
    }
    DvdTitle title = dvd.titles.get(titleIndex);
    long durationUs = title.getTotalDurationUs();
    if (durationUs <= 0) {
      return ImmutableList.of();
    }
    return buildTimeChapters(title.chapterTimesUs, durationUs);
  }

  static ImmutableList<MediaChapter> buildBlurayPlaylistChapters(BdmvStructure bdmv, int playlistIndex) {
    if (playlistIndex < 0 || playlistIndex >= bdmv.allPlaylists.size()) {
      return ImmutableList.of();
    }
    Playlist playlist = bdmv.allPlaylists.get(playlistIndex);
    long durationUs = getPlaylistDurationUs(playlist);
    ArrayList<MediaChapter> result = new ArrayList<>(playlist.chapterMarks.size());
    int chapterIndex = 0;
    for (ChapterMark mark : playlist.chapterMarks) {
      if (mark.markType != 1 || mark.playItemRef < 0 || mark.playItemRef >= playlist.playItems.size()) {
        continue;
      }
      long timeUs = getPlaylistChapterTimeUs(playlist.playItems, mark);
      if (timeUs == C.TIME_UNSET || timeUs >= durationUs || containsChapterAtTime(result, timeUs)) {
        continue;
      }
      result.add(MediaChapter.chapter(chapterIndex, timeUs, "Chapter " + (chapterIndex + 1), false));
      chapterIndex++;
    }
    return ImmutableList.copyOf(result);
  }

  private static long getPlaylistDurationUs(Playlist playlist) {
    long durationUs = 0;
    for (PlayItem item : playlist.playItems) {
      durationUs += Math.max(0, item.getDurationUs());
    }
    return durationUs;
  }

  private static ImmutableList<MediaChapter> buildTimeChapters(long[] timesUs, long durationUs) {
    ArrayList<MediaChapter> result = new ArrayList<>(timesUs.length);
    int chapterIndex = 0;
    for (long timeUs : timesUs) {
      if (timeUs < 0 || timeUs >= durationUs || containsChapterAtTime(result, timeUs)) {
        continue;
      }
      result.add(MediaChapter.chapter(chapterIndex, timeUs, "Chapter " + (chapterIndex + 1), false));
      chapterIndex++;
    }
    return ImmutableList.copyOf(result);
  }

  private static long getPlaylistChapterTimeUs(List<PlayItem> playItems, ChapterMark mark) {
    long timeUs = 0;
    for (int i = 0; i < mark.playItemRef; i++) {
      timeUs += Math.max(0, playItems.get(i).getDurationUs());
    }
    PlayItem item = playItems.get(mark.playItemRef);
    long itemOffsetTicks = mark.timestamp - item.inTimeTicks;
    if (itemOffsetTicks < 0) {
      itemOffsetTicks = mark.timestamp;
    }
    long itemOffsetUs = Util.scaleLargeTimestamp(itemOffsetTicks, C.MICROS_PER_SECOND, BdmvConstants.BDMV_TICKS_PER_SECOND);
    if (itemOffsetUs < 0) {
      return C.TIME_UNSET;
    }
    return timeUs + itemOffsetUs;
  }

  private static boolean containsChapterAtTime(List<MediaChapter> chapters, long timeUs) {
    for (MediaChapter chapter : chapters) {
      if (chapter.timeUs == timeUs) {
        return true;
      }
    }
    return false;
  }
}
