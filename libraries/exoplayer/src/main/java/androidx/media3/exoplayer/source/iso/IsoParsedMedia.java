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

import static com.google.common.base.Preconditions.checkNotNull;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaChapter;
import androidx.media3.common.MediaEdition;
import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.IsoDataReader;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.extractor.iso.bdmv.BdmvStructure;
import androidx.media3.extractor.iso.dvd.DvdStructure;
import androidx.media3.extractor.iso.sacd.SacdStructure;
import androidx.media3.extractor.iso.udf.UdfFileSystem;
import com.google.common.collect.ImmutableList;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

final class IsoParsedMedia implements Closeable {

  private final MediaItem mediaItem;
  private final DataSource.Factory dataSourceFactory;
  private final Uri isoUri;
  private final IsoDataReader isoReader;
  private final ImmutableList<MediaEdition> editions;
  private final int defaultEditionIndex;

  @Nullable
  private final UdfFileSystem udf;
  @Nullable
  private final BdmvStructure bdmv;
  @Nullable
  private final DvdStructure dvd;
  @Nullable
  private final SacdStructure sacd;

  private IsoParsedMedia(MediaItem mediaItem, DataSource.Factory dataSourceFactory, Uri isoUri, IsoDataReader isoReader, ImmutableList<MediaEdition> editions, int defaultEditionIndex, @Nullable UdfFileSystem udf, @Nullable BdmvStructure bdmv, @Nullable DvdStructure dvd, @Nullable SacdStructure sacd) {
    this.mediaItem = mediaItem;
    this.dataSourceFactory = dataSourceFactory;
    this.isoUri = isoUri;
    this.isoReader = isoReader;
    this.editions = editions;
    this.defaultEditionIndex = defaultEditionIndex;
    this.udf = udf;
    this.bdmv = bdmv;
    this.dvd = dvd;
    this.sacd = sacd;
  }

  @Nullable
  public static IsoParsedMedia parse(MediaItem mediaItem, DataSource.Factory dataSourceFactory, Uri isoUri, AtomicBoolean canceled) throws IOException {
    IsoDataReader isoReader = new IsoDataReader(dataSourceFactory, isoUri);
    boolean success = false;
    try {
      if (IsoUtil.isSacd(isoReader)) {
        SacdStructure sacd = SacdSourceHelper.parseStructure(isoReader);
        if (canceled.get()) {
          return null;
        }
        ImmutableList<MediaEdition> editions = SacdSourceHelper.buildEditions(sacd);
        success = true;
        return new IsoParsedMedia(mediaItem, dataSourceFactory, isoUri, isoReader, editions, defaultEditionIndex(editions), null, null, null, sacd);
      }
      UdfFileSystem udf = new UdfFileSystem();
      udf.open(isoReader);
      if (canceled.get()) {
        return null;
      }
      if (IsoUtil.isBluray(udf)) {
        BdmvStructure bdmv = BdmvSourceHelper.parseBdmv(isoReader, udf);
        if (canceled.get()) {
          return null;
        }
        ImmutableList<MediaEdition> editions = IsoEditionScanner.buildBlurayEditionsFromStructure(bdmv);
        success = true;
        return new IsoParsedMedia(mediaItem, dataSourceFactory, isoUri, isoReader, editions, defaultEditionIndex(editions), udf, bdmv, null, null);
      }
      DvdStructure dvd = DvdSourceHelper.parseStructure(isoReader, udf);
      if (canceled.get()) {
        return null;
      }
      ImmutableList<MediaEdition> editions = IsoEditionScanner.buildDvdEditionsFromStructure(dvd);
      success = true;
      return new IsoParsedMedia(mediaItem, dataSourceFactory, isoUri, isoReader, editions, defaultEditionIndex(editions), udf, null, dvd, null);
    } finally {
      if (!success) {
        isoReader.close();
      }
    }
  }

  private static int defaultEditionIndex(ImmutableList<MediaEdition> editions) {
    for (MediaEdition edition : editions) {
      if (edition.selected) {
        return edition.index;
      }
    }
    return editions.isEmpty() ? C.INDEX_UNSET : editions.get(0).index;
  }

  private static ImmutableList<MediaEdition> withSelected(ImmutableList<MediaEdition> editions, int selectedIndex) {
    if (selectedIndex == C.INDEX_UNSET) {
      return editions;
    }
    boolean matched = false;
    ImmutableList.Builder<MediaEdition> result = ImmutableList.builderWithExpectedSize(editions.size());
    for (MediaEdition edition : editions) {
      boolean selected = edition.index == selectedIndex;
      matched |= selected;
      result.add(edition.selected == selected ? edition : edition.withSelected(selected));
    }
    return matched ? result.build() : editions;
  }

  public ImmutableList<MediaEdition> getEditions(int selectedEditionIndex) {
    if (editions.size() < 2) {
      return ImmutableList.of();
    }
    return withSelected(editions, selectedEditionIndex);
  }

  public ImmutableList<MediaChapter> getChapters(int selectedEditionIndex) {
    if (bdmv != null) {
      return IsoChapterScanner.buildBlurayPlaylistChapters(bdmv, selectedEditionIndex);
    } else if (dvd != null) {
      return IsoChapterScanner.buildDvdTitleChapters(dvd, selectedEditionIndex);
    }
    return ImmutableList.of();
  }

  public int resolveEditionIndex(int requestedEditionIndex) {
    if (canSelectEdition(requestedEditionIndex)) {
      return requestedEditionIndex;
    }
    return defaultEditionIndex;
  }

  public boolean canSelectEdition(int editionIndex) {
    if (editionIndex == C.INDEX_UNSET) {
      return false;
    }
    if (sacd != null) {
      return (editionIndex == 0 && sacd.stereoArea != null) || (editionIndex == 1 && sacd.multiArea != null);
    } else if (bdmv != null) {
      return editionIndex >= 0 && editionIndex < bdmv.allPlaylists.size();
    } else if (dvd != null) {
      return editionIndex >= 0 && editionIndex < dvd.titles.size();
    }
    return false;
  }

  public MediaSource buildSource(int editionIndex) throws IOException {
    int resolvedEditionIndex = resolveEditionIndex(editionIndex);
    if (sacd != null) {
      return SacdSourceHelper.buildSource(mediaItem, dataSourceFactory, isoUri, resolvedEditionIndex, sacd);
    } else if (bdmv != null) {
      return BdmvSourceHelper.buildSourceFromStructure(mediaItem, dataSourceFactory, isoUri, checkNotNull(udf), isoReader, resolvedEditionIndex, bdmv);
    } else if (dvd != null) {
      return DvdSourceHelper.buildSource(mediaItem, dataSourceFactory, isoUri, resolvedEditionIndex, dvd);
    }
    throw new IOException("ISO: unsupported parsed media");
  }

  @Override
  public void close() {
    isoReader.close();
  }
}
