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
import android.content.Intent;
import android.content.ComponentName;
import android.content.Context;

import android.media.MediaMetadata;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.googlecode.android_scripting.facade.EventFacade;
import com.googlecode.android_scripting.facade.FacadeManager;
import com.googlecode.android_scripting.jsonrpc.RpcReceiver;
import com.googlecode.android_scripting.rpc.Rpc;
import com.googlecode.android_scripting.rpc.RpcParameter;
import com.googlecode.android_scripting.Log;

import java.util.List;

/**
 * SL4A Facade for dealing with Bluetooth Media related test cases
 * The APIs here can be used on a AVRCP CT and TG, depending on what is tested.
 */
public class BluetoothMediaFacade extends RpcReceiver {
    private static final String TAG = "BluetoothMediaFacade";
    private static final boolean VDBG = false;
    private final Service mService;
    private final Context mContext;
    private Handler mHandler;
    private MediaSessionManager mSessionManager;
    private MediaController mMediaController = null;
    private MediaController.Callback mMediaCtrlCallback = null;
    private MediaSessionManager.OnActiveSessionsChangedListener mSessionListener;
    private MediaBrowser mBrowser = null;

    private static EventFacade mEventFacade;
    // Events posted
    private static final String EVENT_PLAY_RECEIVED = "playReceived";
    private static final String EVENT_PAUSE_RECEIVED = "pauseReceived";
    private static final String EVENT_SKIP_PREV_RECEIVED = "skipPrevReceived";
    private static final String EVENT_SKIP_NEXT_RECEIVED = "skipNextReceived";

    // Commands received
    private static final String CMD_MEDIA_PLAY = "play";
    private static final String CMD_MEDIA_PAUSE = "pause";
    private static final String CMD_MEDIA_SKIP_NEXT = "skipNext";
    private static final String CMD_MEDIA_SKIP_PREV = "skipPrev";

    private static final String bluetoothPkgName = "com.android.bluetooth";
    private static final String browserServiceName =
            "com.android.bluetooth.a2dpsink.mbs.A2dpMediaBrowserService";

    public BluetoothMediaFacade(FacadeManager manager) {
        super(manager);
        mService = manager.getService();
        mEventFacade = manager.getReceiver(EventFacade.class);
        mHandler = new Handler(Looper.getMainLooper());
        mContext = mService.getApplicationContext();
        mSessionManager =
                (MediaSessionManager) mContext.getSystemService(mContext.MEDIA_SESSION_SERVICE);
        mSessionListener = new SessionChangeListener();
        // Listen on Active MediaSession changes, so we can get the active session's MediaController
        if (mSessionManager != null) {
            ComponentName compName =
                    new ComponentName(mContext.getPackageName(), this.getClass().getName());
            mSessionManager.addOnActiveSessionsChangedListener(mSessionListener, null,
                    mHandler);
            if (VDBG) {
                List<MediaController> mcl = mSessionManager.getActiveSessions(null);
                Log.d(TAG + " Num Sessions " + mcl.size());
                for (int i = 0; i < mcl.size(); i++) {
                    Log.d(TAG + "Active session : " + i + ((MediaController) (mcl.get(
                            i))).getPackageName() + ((MediaController) (mcl.get(i))).getTag());
                }
            }
        }
        mMediaCtrlCallback = new MediaControllerCallback();
    }

    /**
     * Class method called from {@link BluetoothAvrcpMediaBrowserService} to post an Event through
     * EventFacade back to the RPC client.
     *
     * @param playbackState PlaybackState change that is posted as an Event to the client.
     */
    public static void dispatchPlaybackStateChanged(int playbackState) {
        switch (playbackState) {
            case PlaybackState.STATE_PLAYING:
                mEventFacade.postEvent(EVENT_PLAY_RECEIVED, new Bundle());
                break;
            case PlaybackState.STATE_PAUSED:
                mEventFacade.postEvent(EVENT_PAUSE_RECEIVED, new Bundle());
                break;
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
                mEventFacade.postEvent(EVENT_SKIP_NEXT_RECEIVED, new Bundle());
                break;
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
                mEventFacade.postEvent(EVENT_SKIP_PREV_RECEIVED, new Bundle());
                break;
            default:
                break;
        }
    }

    /**
     * To register to MediaSession for callbacks on updates
     */
    private class MediaControllerCallback extends MediaController.Callback {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            Log.d(TAG + " onPlaybackStateChanged: " + state.getState());
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            Log.d(TAG + " onMetadataChanged ");
        }
    }

    /**
     * To get the MediaController for the currently active MediaSession
     */
    private class SessionChangeListener
            implements MediaSessionManager.OnActiveSessionsChangedListener {
        @Override
        public void onActiveSessionsChanged(List<MediaController> controllers) {
            if (VDBG) {
                Log.d(TAG + " onActiveSessionsChanged : " + controllers.size());
                for (int i = 0; i < controllers.size(); i++) {
                    Log.d(TAG + "Active session : " + i + ((MediaController) (controllers.get(
                            i))).getPackageName() + ((MediaController) (controllers.get(
                            i))).getTag());
                }
            }
            // Whenever the list of ActiveSessions change, iterate through the list of active
            // session and look for the one that belongs to the BluetoothAvrcpMediaBrowserService.
            // If found, update our MediaController, we don't care for the other Active Media
            // Sessions.
            if (controllers.size() > 0) {
                for (int i = 0; i < controllers.size(); i++) {
                    MediaController controller = (MediaController) controllers.get(i);
                    if (controller.getTag().contains(BluetoothAvrcpMediaBrowserService.getTag())) {
                        setCurrentMediaController(controller);
                        return;
                    }
                }
                setCurrentMediaController(null);
            } else {
                setCurrentMediaController(null);
            }
        }
    }

    /**
     * Callback on <code>MediaBrowser.connect()</code>
     */
    MediaBrowser.ConnectionCallback mBrowserConnectionCallback =
            new MediaBrowser.ConnectionCallback() {
                private static final String classTag = TAG + " BrowserConnectionCallback";

                @Override
                public void onConnected() {
                    Log.d(classTag + " onConnected: session token " + mBrowser.getSessionToken());
                    MediaController mediaController = new MediaController(mContext,
                            mBrowser.getSessionToken());
                    // Update the MediaController
                    setCurrentMediaController(mediaController);
                }

                @Override
                public void onConnectionFailed() {
                    Log.d(classTag + " onConnectionFailed");
                }
            };

    /**
     * Update the MediaController.
     * On the AVRCP CT side (Carkitt for ex), this MediaController
     * would be the one associated with the
     * com.android.bluetooth.a2dpsink.mbs.A2dpMediaBrowserService.
     * On the AVRCP TG side (Phone for ex), this MediaController would
     * be the one associated with the
     * com.googlecode.android_scripting.facade.bluetooth.BluetoothAvrcpMediaBrowserService
     */
    private void setCurrentMediaController(MediaController controller) {
        Handler mainHandler = new Handler(mContext.getMainLooper());
        if (mMediaController == null && controller != null) {
            Log.d(TAG + " Setting MediaController " + controller.getTag());
            mMediaController = controller;
            mMediaController.registerCallback(mMediaCtrlCallback);
        } else if (mMediaController != null && controller != null) {
            // We have a diff media controller
            if (controller.getSessionToken().equals(mMediaController.getSessionToken())
                    == false) {
                Log.d(TAG + " Changing MediaController " + controller.getTag());
                mMediaController.unregisterCallback(mMediaCtrlCallback);
                mMediaController = controller;
                mMediaController.registerCallback(mMediaCtrlCallback, mainHandler);
            }
        } else if (mMediaController != null && controller == null) {
            // The new media controller is null probably because it doesn't support Transport
            // Controls
            Log.d(TAG + " Clearing MediaController " + mMediaController.getTag());
            mMediaController.unregisterCallback(mMediaCtrlCallback);
            mMediaController = controller;
        }
    }

    // Sends the passthrough command through the currently active MediaController.
    // If there isn't one, look for the currently active sessions and just pick the first one,
    // just a fallback
    @Rpc(description = "Simulate a passthrough command")
    public void bluetoothMediaPassthrough(
            @RpcParameter(name = "passthruCmd", description = "play/pause/skipFwd/skipBack")
                    String passthruCmd) {
        Log.d(TAG + "Passthrough Cmd " + passthruCmd);
        if (mMediaController == null) {
            Log.i(TAG + " Media Controller not ready - Grabbing existing one");
            ComponentName name =
                    new ComponentName(mContext.getPackageName(),
                            mSessionListener.getClass().getName());
            List<MediaController> listMC = mSessionManager.getActiveSessions(null);
            if (listMC.size() > 0) {
                if (VDBG) {
                    Log.d(TAG + " Num Sessions " + listMC.size());
                    for (int i = 0; i < listMC.size(); i++) {
                        Log.d(TAG + "Active session : " + i + ((MediaController) (listMC.get(
                                i))).getPackageName() + ((MediaController) (listMC.get(
                                i))).getTag());
                    }
                }
                mMediaController = (MediaController) listMC.get(0);
            } else {
                Log.d(TAG + " No Active Media Session to grab");
                return;
            }
        }

        switch (passthruCmd) {
            case CMD_MEDIA_PLAY:
                mMediaController.getTransportControls().play();
                break;
            case CMD_MEDIA_PAUSE:
                mMediaController.getTransportControls().pause();
                break;
            case CMD_MEDIA_SKIP_NEXT:
                mMediaController.getTransportControls().skipToNext();
                break;
            case CMD_MEDIA_SKIP_PREV:
                mMediaController.getTransportControls().skipToPrevious();
                break;
            default:
                Log.d(TAG + " Unsupported Passthrough Cmd");
                break;
        }
    }

    // This is usually called from the AVRCP CT device (Carkitt) to connect to the
    // existing com.android.bluetooth.a2dpsink.mbs.A2dpMediaBrowserservice
    @Rpc(description = "Connect a MediaBrowser to the A2dpMediaBrowserservice in the Carkitt")
    public void bluetoothMediaConnectToA2dpMediaBrowserService() {
        ComponentName compName;
        // Create a MediaBrowser to connect to the A2dpMBS
        if (mBrowser == null) {
            compName = new ComponentName(bluetoothPkgName, browserServiceName);
            // Note - MediaBrowser connect needs to be done on the Main Thread's handler,
            // otherwise we never get the ServiceConnected callback.
            Runnable createAndConnectMediaBrowser = new Runnable() {
                @Override
                public void run() {
                    mBrowser = new MediaBrowser(mContext, compName, mBrowserConnectionCallback,
                            null);
                    if (mBrowser != null) {
                        Log.d(TAG + " Connecting to MBS");
                        mBrowser.connect();
                    } else {
                        Log.d(TAG + " Failed to create a MediaBrowser");
                    }
                }
            };

            Handler mainHandler = new Handler(mContext.getMainLooper());
            mainHandler.post(createAndConnectMediaBrowser);
        } //mBrowser
    }

    // This is usually called from the AVRCP TG device (Phone)
    @Rpc(description = "Start the BluetoothAvrcpMediaBrowserService.")
    public void bluetoothMediaAvrcpMediaBrowserServiceStart() {
        Log.d(TAG + "Staring BluetoothAvrcpMediaBrowserService");
        // Start the Avrcp Media Browser service.  Starting it sets it to active.
        Intent startIntent = new Intent(mContext, BluetoothAvrcpMediaBrowserService.class);
        mContext.startService(startIntent);
    }

    @Rpc(description = "Stop the BluetoothAvrcpMediaBrowserService.")
    public void bluetoothMediaAvrcpMediaBrowserServiceStop() {
        Log.d(TAG + "Stopping BluetoothAvrcpMediaBrowserService");
        // Stop the Avrcp Media Browser service.
        Intent stopIntent = new Intent(mContext, BluetoothAvrcpMediaBrowserService.class);
        mContext.stopService(stopIntent);
    }

    @Override
    public void shutdown() {
        setCurrentMediaController(null);
    }
}
