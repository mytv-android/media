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
package androidx.media3.ui;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.Player;
import androidx.media3.common.util.Log;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

final class PlayerDebugView {

  private static final String TAG = "PlayerDebugView";
  // LINT.IfChange
  private static final String EXO_PLAYER_CLASS = "androidx.media3.exoplayer.ExoPlayer";
  private static final String EXO_DEBUG_HELPER_CLASS =
      "androidx.media3.exoplayer.util.DebugTextViewHelper";
  // LINT.ThenChange(../../../../../../proguard-rules.txt)

  @Nullable private static final ExoDebugBridge EXO_DEBUG_BRIDGE = ExoDebugBridge.create();

  private final PlayerView playerView;
  @Nullable private DebugTextView exoTextView;
  @Nullable private Object exoHelper;
  @Nullable private Player player;
  private boolean visible;

  PlayerDebugView(PlayerView playerView) {
    this.playerView = playerView;
  }

  boolean isVisible() {
    return visible;
  }

  void setPlayer(@Nullable Player player) {
    if (this.player == player) {
      return;
    }
    boolean shouldRestoreDebugOutput = visible;
    clearDebugOutput();
    this.player = player;
    visible = shouldRestoreDebugOutput && player != null;
    if (!visible) {
      return;
    }
    playerView.post(() -> restoreDebugOutput(player));
  }

  void toggle(@Nullable Player player) {
    if (visible) {
      hide();
    } else {
      show(player);
    }
  }

  private void show(@Nullable Player player) {
    if (visible && this.player == player) {
      return;
    }
    hide();
    this.player = player;
    visible = showForPlayer(player);
  }

  void hide() {
    visible = false;
    clearDebugOutput();
  }

  private boolean showForPlayer(@Nullable Player player) {
    if (player == null) {
      return false;
    }
    ExoDebugBridge bridge = EXO_DEBUG_BRIDGE;
    if (bridge != null && bridge.isPlayer(player)) {
      return showExo(bridge, player);
    }
    return false;
  }

  private void restoreDebugOutput(Player player) {
    if (visible && this.player == player) {
      visible = showForPlayer(player);
    }
  }

  private boolean showExo(ExoDebugBridge bridge, Player player) {
    DebugTextView textView = ensureExoTextView();
    if (textView == null) {
      return false;
    }
    try {
      Object helper = bridge.createHelper(player, textView);
      bridge.start(helper);
      exoHelper = helper;
      textView.setVisibility(View.VISIBLE);
      return true;
    } catch (ReflectiveOperationException e) {
      Log.w(TAG, "Unable to show ExoPlayer debug view", e);
      hideExoTextView();
      return false;
    }
  }

  private void clearDebugOutput() {
    stopExoHelper();
    hideExoTextView();
  }

  private void stopExoHelper() {
    if (exoHelper == null) {
      return;
    }
    try {
      ExoDebugBridge bridge = EXO_DEBUG_BRIDGE;
      if (bridge != null) {
        bridge.stop(exoHelper);
      }
    } catch (ReflectiveOperationException e) {
      Log.w(TAG, "Unable to stop ExoPlayer debug view", e);
    } finally {
      exoHelper = null;
    }
  }

  private void hideExoTextView() {
    if (exoTextView != null) {
      exoTextView.setVisibility(View.GONE);
    }
  }

  @Nullable
  private DebugTextView ensureExoTextView() {
    if (exoTextView != null) {
      return exoTextView;
    }
    FrameLayout host = getDebugHost();
    if (host == null) {
      return null;
    }
    exoTextView = createExoTextView(host);
    return exoTextView;
  }

  private DebugTextView createExoTextView(FrameLayout host) {
    DebugTextView textView = new DebugTextView(playerView.getContext());
    host.addView(
        textView,
        new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.START | Gravity.TOP));
    return textView;
  }

  @Nullable
  @VisibleForTesting
  /* package */ FrameLayout getDebugHost() {
    View contentFrame = playerView.findViewById(R.id.exo_content_frame);
    if (contentFrame instanceof FrameLayout) {
      return (FrameLayout) contentFrame;
    }
    return null;
  }

  private static final class ExoDebugBridge {

    private final Class<?> exoPlayerClass;
    private final Constructor<?> helperConstructor;
    private final Method startMethod;
    private final Method stopMethod;

    private ExoDebugBridge(
        Class<?> exoPlayerClass,
        Constructor<?> helperConstructor,
        Method startMethod,
        Method stopMethod) {
      this.exoPlayerClass = exoPlayerClass;
      this.helperConstructor = helperConstructor;
      this.startMethod = startMethod;
      this.stopMethod = stopMethod;
    }

    @Nullable
    private static ExoDebugBridge create() {
      try {
        Class<?> exoPlayerClass = Class.forName(EXO_PLAYER_CLASS);
        Class<?> helperClass = Class.forName(EXO_DEBUG_HELPER_CLASS);
        return new ExoDebugBridge(
            exoPlayerClass,
            helperClass.getConstructor(exoPlayerClass, TextView.class),
            helperClass.getMethod("start"),
            helperClass.getMethod("stop"));
      } catch (ReflectiveOperationException e) {
        return null;
      }
    }

    private boolean isPlayer(Player player) {
      return exoPlayerClass.isInstance(player);
    }

    private Object createHelper(Player player, TextView textView)
        throws ReflectiveOperationException {
      return helperConstructor.newInstance(player, textView);
    }

    private void start(Object helper) throws ReflectiveOperationException {
      startMethod.invoke(helper);
    }

    private void stop(Object helper) throws ReflectiveOperationException {
      stopMethod.invoke(helper);
    }
  }

}
