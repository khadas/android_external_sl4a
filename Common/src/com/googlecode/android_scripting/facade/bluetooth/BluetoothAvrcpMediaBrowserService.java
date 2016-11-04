/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.googlecode.android_scripting.facade.bluetooth;

import android.app.Service;
import android.media.browse.MediaBrowser.MediaItem;
import android.media.session.*;
import android.os.Bundle;
import android.service.media.MediaBrowserService;

import com.googlecode.android_scripting.facade.bluetooth.BluetoothMediaFacade;
import com.googlecode.android_scripting.Log;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link MediaBrowserService} implemented in the SL4A App to intercept Media keys and
 * commands.
 * This would be running on the AVRCP TG device and whenever the device receives a media
 * command from a AVRCP CT, this MediaBrowserService's MediaSession would intercept it.
 * Helps to verify the commands received by the AVRCP TG are the same as what was sent from
 * an AVRCP CT.
 */
public class BluetoothAvrcpMediaBrowserService extends MediaBrowserService {
    private static final String TAG = "BluetoothAvrcpMBS";
    private static final String MEDIA_ROOT_ID = "__ROOT__";

    private MediaSession mMediaSession = null;
    private MediaSession.Token mSessionToken = null;
    private MediaController mMediaController = null;

    /**
     * MediaSession callback dispatching the corresponding <code>PlaybackState</code> to
     * {@link BluetoothMediaFacade}
     */
    private MediaSession.Callback mMediaSessionCallback =
            new MediaSession.Callback() {
                @Override
                public void onPlay() {
                    Log.d(TAG + " onPlay");
                    BluetoothMediaFacade.dispatchPlaybackStateChanged(PlaybackState.STATE_PLAYING);
                }

                @Override
                public void onPause() {
                    Log.d(TAG + " onPause");
                    BluetoothMediaFacade.dispatchPlaybackStateChanged(PlaybackState.STATE_PAUSED);
                }

                @Override
                public void onRewind() {
                    Log.d(TAG + " onRewind");
                    BluetoothMediaFacade.dispatchPlaybackStateChanged(PlaybackState.STATE_REWINDING);
                }

                @Override
                public void onFastForward() {
                    Log.d(TAG + " onFastForward");
                    BluetoothMediaFacade.dispatchPlaybackStateChanged(PlaybackState.STATE_FAST_FORWARDING);
                }

                @Override
                public void onSkipToNext() {
                    Log.d(TAG + " onSkipToNext");
                    BluetoothMediaFacade.dispatchPlaybackStateChanged(PlaybackState.STATE_SKIPPING_TO_NEXT);
                }

                @Override
                public void onSkipToPrevious() {
                    Log.d(TAG + " onSkipToPrevious");
                    BluetoothMediaFacade.dispatchPlaybackStateChanged(
                            PlaybackState.STATE_SKIPPING_TO_PREVIOUS);
                }
            };

    /**
     * We do the following on the AvrcpMediaBrowserService onCreate():
     * 1. Create a new MediaSession
     * 2. Register a callback with the created MediaSession
     * 3. Set its playback state and set the session to active.
     */
    @Override
    public void onCreate() {
        Log.d(TAG + " onCreate");
        super.onCreate();
        mMediaSession = new MediaSession(this, TAG);
        mSessionToken = mMediaSession.getSessionToken();
        setSessionToken(mSessionToken);
        mMediaSession.setCallback(mMediaSessionCallback);
        mMediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        // Note - MediaButton Intent is not received until the session has a PlaybackState
        // whose state is set to something other than STATE_STOPPED or STATE_NONE
        PlaybackState state = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_REWIND | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1)
                .build();
        mMediaSession.setPlaybackState(state);
        mMediaSession.setActive(true);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG + " onDestroy");
        mMediaSession.release();
        mMediaSession = null;
        mSessionToken = null;
        super.onDestroy();
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        BrowserRoot mediaRoot = new BrowserRoot(MEDIA_ROOT_ID, null);
        return mediaRoot;
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaItem>> result) {
        List<MediaItem> mediaList = new ArrayList<MediaItem>();
        result.sendResult(mediaList);
    }

    /**
     * Returns the TAG string
     * @return  <code>BluetoothAvrcpMediaBrowserService</code>'s tag
     */
    public static String getTag() {
        return TAG;
    }
}


