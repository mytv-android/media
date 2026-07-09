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

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.upstream.Loader;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

final class IsoParseLoadable implements Loader.Loadable {

  private final Uri isoUri;
  private final MediaItem mediaItem;
  private final DataSource.Factory dataSourceFactory;
  private final AtomicBoolean canceled = new AtomicBoolean();

  @Nullable IsoParsedMedia result;

  IsoParseLoadable(MediaItem mediaItem, DataSource.Factory dataSourceFactory, Uri isoUri) {
    this.dataSourceFactory = dataSourceFactory;
    this.mediaItem = mediaItem;
    this.isoUri = isoUri;
  }

  @Override
  public void cancelLoad() {
    canceled.set(true);
  }

  @Override
  public void load() throws IOException {
    result = IsoParsedMedia.parse(mediaItem, dataSourceFactory, isoUri, canceled);
  }
}
