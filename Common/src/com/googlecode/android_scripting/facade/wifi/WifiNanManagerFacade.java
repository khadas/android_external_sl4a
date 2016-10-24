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

package com.googlecode.android_scripting.facade.wifi;

import com.googlecode.android_scripting.facade.EventFacade;
import com.googlecode.android_scripting.facade.FacadeManager;
import com.googlecode.android_scripting.jsonrpc.RpcReceiver;
import com.googlecode.android_scripting.rpc.Rpc;
import com.googlecode.android_scripting.rpc.RpcOptional;
import com.googlecode.android_scripting.rpc.RpcParameter;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.RttManager;
import android.net.wifi.RttManager.RttResult;
import android.net.wifi.nan.ConfigRequest;
import android.net.wifi.nan.PublishConfig;
import android.net.wifi.nan.SubscribeConfig;
import android.net.wifi.nan.TlvBufferUtils;
import android.net.wifi.nan.WifiNanAttachCallback;
import android.net.wifi.nan.WifiNanDiscoveryBaseSession;
import android.net.wifi.nan.WifiNanDiscoverySessionCallback;
import android.net.wifi.nan.WifiNanIdentityChangedListener;
import android.net.wifi.nan.WifiNanManager;
import android.net.wifi.nan.WifiNanPublishDiscoverySession;
import android.net.wifi.nan.WifiNanSession;
import android.net.wifi.nan.WifiNanSubscribeDiscoverySession;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import libcore.util.HexEncoding;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * WifiNanManager functions.
 */
public class WifiNanManagerFacade extends RpcReceiver {
    private final Service mService;
    private final EventFacade mEventFacade;
    private final WifiNanStateChangedReceiver mStateChangedReceiver;

    private final Object mLock = new Object(); // lock access to the following vars

    @GuardedBy("mLock")
    private WifiNanManager mMgr;

    @GuardedBy("mLock")
    private int mNextDiscoverySessionId = 1;
    @GuardedBy("mLock")
    private SparseArray<WifiNanDiscoveryBaseSession> mDiscoverySessions = new SparseArray<>();
    private int getNextDiscoverySessionId() {
        synchronized (mLock) {
            return mNextDiscoverySessionId++;
        }
    }

    @GuardedBy("mLock")
    private int mNextSessionId = 1;
    @GuardedBy("mLock")
    private SparseArray<WifiNanSession> mSessions = new SparseArray<>();
    private int getNextSessionId() {
        synchronized (mLock) {
            return mNextSessionId++;
        }
    }

    @GuardedBy("mLock")
    private SparseArray<Long> mMessageStartTime = new SparseArray<>();

    private static TlvBufferUtils.TlvConstructor getFilterData(JSONObject j) throws JSONException {
        if (j == null) {
            return null;
        }

        TlvBufferUtils.TlvConstructor constructor = new TlvBufferUtils.TlvConstructor(0, 1);
        constructor.allocate(255);

        if (j.has("int0")) {
            constructor.putShort(0, (short) j.getInt("int0"));
        }

        if (j.has("int1")) {
            constructor.putShort(0, (short) j.getInt("int1"));
        }

        if (j.has("data0")) {
            constructor.putString(0, j.getString("data0"));
        }

        if (j.has("data1")) {
            constructor.putString(0, j.getString("data1"));
        }

        return constructor;
    }

    private static ConfigRequest getConfigRequest(JSONObject j) throws JSONException {
        if (j == null) {
            return null;
        }

        ConfigRequest.Builder builder = new ConfigRequest.Builder();

        if (j.has("Support5gBand")) {
            builder.setSupport5gBand(j.getBoolean("Support5gBand"));
        }
        if (j.has("MasterPreference")) {
            builder.setMasterPreference(j.getInt("MasterPreference"));
        }
        if (j.has("ClusterLow")) {
            builder.setClusterLow(j.getInt("ClusterLow"));
        }
        if (j.has("ClusterHigh")) {
            builder.setClusterHigh(j.getInt("ClusterHigh"));
        }

        return builder.build();
    }

    private static PublishConfig getPublishConfig(JSONObject j) throws JSONException {
        if (j == null) {
            return null;
        }

        PublishConfig.Builder builder = new PublishConfig.Builder();

        if (j.has("ServiceName")) {
            builder.setServiceName(j.getString("ServiceName"));
        }

        if (j.has("ServiceSpecificInfo")) {
            String ssi = j.getString("ServiceSpecificInfo");
            byte[] bytes = ssi.getBytes();
            builder.setServiceSpecificInfo(bytes);
        }

        if (j.has("MatchFilter")) {
            TlvBufferUtils.TlvConstructor constructor = getFilterData(
                    j.getJSONObject("MatchFilter"));
            builder.setMatchFilter(constructor.getArray());
        }

        if (j.has("PublishType")) {
            builder.setPublishType(j.getInt("PublishType"));
        }
        if (j.has("PublishCount")) {
            builder.setPublishCount(j.getInt("PublishCount"));
        }
        if (j.has("TtlSec")) {
            builder.setTtlSec(j.getInt("TtlSec"));
        }
        if (j.has("EnableTerminateNotification")) {
            builder.setTerminateNotificationEnabled(j.getBoolean("TerminateNotificationEnabled"));
        }

        return builder.build();
    }

    private static SubscribeConfig getSubscribeConfig(JSONObject j) throws JSONException {
        if (j == null) {
            return null;
        }

        SubscribeConfig.Builder builder = new SubscribeConfig.Builder();

        if (j.has("ServiceName")) {
            builder.setServiceName(j.getString("ServiceName"));
        }

        if (j.has("ServiceSpecificInfo")) {
            String ssi = j.getString("ServiceSpecificInfo");
            builder.setServiceSpecificInfo(ssi.getBytes());
        }

        if (j.has("MatchFilter")) {
            TlvBufferUtils.TlvConstructor constructor = getFilterData(
                    j.getJSONObject("MatchFilter"));
            builder.setMatchFilter(constructor.getArray());
        }

        if (j.has("SubscribeType")) {
            builder.setSubscribeType(j.getInt("SubscribeType"));
        }
        if (j.has("SubscribeCount")) {
            builder.setSubscribeCount(j.getInt("SubscribeCount"));
        }
        if (j.has("TtlSec")) {
            builder.setTtlSec(j.getInt("TtlSec"));
        }
        if (j.has("MatchStyle")) {
            builder.setMatchStyle(j.getInt("MatchStyle"));
        }
        if (j.has("EnableTerminateNotification")) {
            builder.setTerminateNotificationEnabled(j.getBoolean("TerminateNotificationEnabled"));
        }

        return builder.build();
    }

    public WifiNanManagerFacade(FacadeManager manager) {
        super(manager);
        mService = manager.getService();

        mMgr = (WifiNanManager) mService.getSystemService(Context.WIFI_NAN_SERVICE);

        mEventFacade = manager.getReceiver(EventFacade.class);

        mStateChangedReceiver = new WifiNanStateChangedReceiver();
        IntentFilter filter = new IntentFilter(WifiNanManager.ACTION_WIFI_NAN_STATE_CHANGED);
        mService.registerReceiver(mStateChangedReceiver, filter);
    }

    @Override
    public void shutdown() {
        wifiNanDestroyAll();
        mService.unregisterReceiver(mStateChangedReceiver);
    }

    @Rpc(description = "Enable NAN Usage.")
    public void wifiNanEnableUsage() throws RemoteException {
        synchronized (mLock) {
            mMgr.enableUsage();
        }
    }

    @Rpc(description = "Disable NAN Usage.")
    public void wifiNanDisableUsage() throws RemoteException {
        synchronized (mLock) {
            mMgr.disableUsage();
        }
    }

    @Rpc(description = "Is NAN Usage Enabled?")
    public Boolean wifiIsNanAvailable() throws RemoteException {
        synchronized (mLock) {
            return mMgr.isAvailable();
        }
    }

    @Rpc(description = "Destroy all NAN sessions and discovery sessions")
    public void wifiNanDestroyAll() {
        synchronized (mLock) {
            for (int i = 0; i < mSessions.size(); ++i) {
                mSessions.valueAt(i).destroy();
            }
            mSessions.clear();

            /* discovery sessions automatically destroyed when containing NAN sessions destroyed */
            mDiscoverySessions.clear();

            mMessageStartTime.clear();
        }
    }

    @Rpc(description = "Attach to NAN.")
    public Integer wifiNanAttach(
            @RpcParameter(name = "nanConfig") @RpcOptional JSONObject nanConfig)
            throws RemoteException, JSONException {
        synchronized (mLock) {
            int sessionId = getNextSessionId();
            mMgr.attach(null, getConfigRequest(nanConfig),
                    new NanAttachCallbackPostsEvents(sessionId),
                    new NanIdentityChangeListenerPostsEvents(sessionId));
            return sessionId;
        }
    }

    @Rpc(description = "Destroy a NAN session.")
    public void wifiNanDestroy(
            @RpcParameter(name = "clientId", description = "The client ID returned when a connection was created") Integer clientId)
            throws RemoteException, JSONException {
        WifiNanSession session;
        synchronized (mLock) {
            session = mSessions.get(clientId);
        }
        if (session == null) {
            throw new IllegalStateException(
                    "Calling wifiNanDisconnect before session (client ID " + clientId
                            + ") is ready/or already disconnected");
        }
        session.destroy();
    }

    @Rpc(description = "Publish.")
    public Integer wifiNanPublish(
            @RpcParameter(name = "clientId", description = "The client ID returned when a connection was created") Integer clientId,
            @RpcParameter(name = "publishConfig") JSONObject publishConfig)
            throws RemoteException, JSONException {
        synchronized (mLock) {
            WifiNanSession session = mSessions.get(clientId);
            if (session == null) {
                throw new IllegalStateException(
                        "Calling wifiNanPublish before session (client ID " + clientId
                                + ") is ready/or already disconnected");
            }

            int discoverySessionId = getNextDiscoverySessionId();
            session.publish(null, getPublishConfig(publishConfig),
                    new NanDiscoverySessionCallbackPostsEvents(discoverySessionId));
            return discoverySessionId;
        }
    }

    @Rpc(description = "Subscribe.")
    public Integer wifiNanSubscribe(
            @RpcParameter(name = "clientId", description = "The client ID returned when a connection was created") Integer clientId,
            @RpcParameter(name = "subscribeConfig") JSONObject subscribeConfig)
            throws RemoteException, JSONException {
        synchronized (mLock) {
            WifiNanSession session = mSessions.get(clientId);
            if (session == null) {
                throw new IllegalStateException(
                        "Calling wifiNanSubscribe before session (client ID " + clientId
                                + ") is ready/or already disconnected");
            }

            int discoverySessionId = getNextDiscoverySessionId();
            session.subscribe(null, getSubscribeConfig(subscribeConfig),
                    new NanDiscoverySessionCallbackPostsEvents(discoverySessionId));
            return discoverySessionId;
        }
    }

    @Rpc(description = "Destroy a discovery Session.")
    public void wifiNanDestroyDiscoverySession(
            @RpcParameter(name = "sessionId", description = "The discovery session ID returned when session was created using publish or subscribe") Integer sessionId)
            throws RemoteException {
        synchronized (mLock) {
            WifiNanDiscoveryBaseSession session = mDiscoverySessions.get(sessionId);
            if (session == null) {
                throw new IllegalStateException(
                        "Calling wifiNanTerminateSession before session (session ID "
                                + sessionId + ") is ready");
            }
            session.destroy();
            mDiscoverySessions.remove(sessionId);
        }
    }

    @Rpc(description = "Send peer-to-peer NAN message")
    public void wifiNanSendMessage(
            @RpcParameter(name = "sessionId", description = "The session ID returned when session"
                    + " was created using publish or subscribe") Integer sessionId,
            @RpcParameter(name = "peerId", description = "The ID of the peer being communicated "
                    + "with. Obtained from a previous message or match session.") Integer peerId,
            @RpcParameter(name = "messageId", description = "Arbitrary handle used for "
                    + "identification of the message in the message status callbacks")
                    Integer messageId,
            @RpcParameter(name = "message") String message,
            @RpcParameter(name = "retryCount", description = "Number of retries (0 for none) if "
                    + "transmission fails due to no ACK reception") Integer retryCount)
                    throws RemoteException {
        WifiNanDiscoveryBaseSession session;
        synchronized (mLock) {
            session = mDiscoverySessions.get(sessionId);
        }
        if (session == null) {
            throw new IllegalStateException("Calling wifiNanSendMessage before session (session ID "
                    + sessionId + " is ready");
        }
        byte[] bytes = null;
        if (message != null) {
            bytes = message.getBytes();
        }

        synchronized (mLock) {
            mMessageStartTime.put(messageId, System.currentTimeMillis());
        }
        session.sendMessage(new WifiNanManager.OpaquePeerHandle(peerId), messageId, bytes,
                retryCount);
    }

    @Rpc(description = "Start peer-to-peer NAN ranging")
    public void wifiNanStartRanging(
            @RpcParameter(name = "callbackId") Integer callbackId,
            @RpcParameter(name = "sessionId", description = "The session ID returned when session was created using publish or subscribe") Integer sessionId,
            @RpcParameter(name = "rttParams", description = "RTT session parameters.") JSONArray rttParams) throws RemoteException, JSONException {
        WifiNanDiscoveryBaseSession session;
        synchronized (mLock) {
            session = mDiscoverySessions.get(sessionId);
        }
        if (session == null) {
            throw new IllegalStateException(
                    "Calling wifiNanStartRanging before session (session ID "
                            + sessionId + " is ready");
        }
        RttManager.RttParams[] rParams = new RttManager.RttParams[rttParams.length()];
        for (int i = 0; i < rttParams.length(); i++) {
            rParams[i] = WifiRttManagerFacade.parseRttParam(rttParams.getJSONObject(i));
        }
        session.startRanging(rParams, new WifiNanRangingListener(callbackId, sessionId));
    }

    @Rpc(description = "Create a network specifier to be used when specifying a NAN network request")
    public String wifiNanCreateNetworkSpecifier(
            @RpcParameter(name = "role", description = "The role of the device: Initiator (0) or Responder (1)")
                    Integer role,
            @RpcParameter(name = "sessionId", description = "The session ID returned when session was created using publish or subscribe")
                    Integer sessionId,
            @RpcParameter(name = "peerId", description = "The ID of the peer (obtained through OnMatch or OnMessageReceived")
                    Integer peerId,
            @RpcParameter(name = "token", description = "Arbitrary token message to be sent to peer as part of data-path creation process")
                    String token) {
        WifiNanDiscoveryBaseSession session;
        synchronized (mLock) {
            session = mDiscoverySessions.get(sessionId);
        }
        if (session == null) {
            throw new IllegalStateException(
                    "Calling wifiNanStartRanging before session (session ID "
                            + sessionId + " is ready");
        }
        byte[] bytes = token.getBytes();
        return session.createNetworkSpecifier(role, new WifiNanManager.OpaquePeerHandle(peerId),
                bytes);
    }

    private class NanAttachCallbackPostsEvents extends WifiNanAttachCallback {
        private int mSessionId;
        private long mCreateTimestampMs;

        public NanAttachCallbackPostsEvents(int sessionId) {
            mSessionId = sessionId;
            mCreateTimestampMs = System.currentTimeMillis();
        }

        @Override
        public void onAttached(WifiNanSession session) {
            synchronized (mLock) {
                mSessions.put(mSessionId, session);
            }

            Bundle mResults = new Bundle();
            mResults.putInt("sessionId", mSessionId);
            mResults.putLong("latencyMs", System.currentTimeMillis() - mCreateTimestampMs);
            mResults.putLong("timestampMs", System.currentTimeMillis());
            mEventFacade.postEvent("WifiNanOnAttached", mResults);
        }

        @Override
        public void onAttachFailed() {
            Bundle mResults = new Bundle();
            mResults.putInt("sessionId", mSessionId);
            mResults.putLong("latencyMs", System.currentTimeMillis() - mCreateTimestampMs);
            mEventFacade.postEvent("WifiNanOnAttachFailed", mResults);
        }
    }

    private class NanIdentityChangeListenerPostsEvents extends WifiNanIdentityChangedListener {
        private int mSessionId;

        public NanIdentityChangeListenerPostsEvents(int sessionId) {
            mSessionId = sessionId;
        }

        @Override
        public void onIdentityChanged(byte[] mac) {
            Bundle mResults = new Bundle();
            mResults.putInt("sessionId", mSessionId);
            mResults.putString("mac", String.valueOf(HexEncoding.encode(mac)));
            mResults.putLong("timestampMs", System.currentTimeMillis());
            mEventFacade.postEvent("WifiNanOnIdentityChanged", mResults);
        }
    }

    private class NanDiscoverySessionCallbackPostsEvents extends WifiNanDiscoverySessionCallback {
        private int mDiscoverySessionId;
        private long mCreateTimestampMs;

        public NanDiscoverySessionCallbackPostsEvents(int discoverySessionId) {
            mDiscoverySessionId = discoverySessionId;
            mCreateTimestampMs = System.currentTimeMillis();
        }

        @Override
        public void onPublishStarted(WifiNanPublishDiscoverySession discoverySession) {
            synchronized (mLock) {
                mDiscoverySessions.put(mDiscoverySessionId, discoverySession);
            }

            Bundle mResults = new Bundle();
            mResults.putInt("discoverySessionId", mDiscoverySessionId);
            mResults.putLong("latencyMs", System.currentTimeMillis() - mCreateTimestampMs);
            mResults.putLong("timestampMs", System.currentTimeMillis());
            mEventFacade.postEvent("WifiNanSessionOnPublishStarted", mResults);
        }

        @Override
        public void onSubscribeStarted(WifiNanSubscribeDiscoverySession discoverySession) {
            synchronized (mLock) {
                mDiscoverySessions.put(mDiscoverySessionId, discoverySession);
            }

            Bundle mResults = new Bundle();
            mResults.putInt("discoverySessionId", mDiscoverySessionId);
            mResults.putLong("latencyMs", System.currentTimeMillis() - mCreateTimestampMs);
            mResults.putLong("timestampMs", System.currentTimeMillis());
            mEventFacade.postEvent("WifiNanSessionOnSubscribeStarted", mResults);
        }

        @Override
        public void onSessionConfigUpdated() {
            Bundle mResults = new Bundle();
            mResults.putInt("discoverySessionId", mDiscoverySessionId);
            mEventFacade.postEvent("WifiNanSessionOnSessionConfigUpdated", mResults);
        }

        @Override
        public void onSessionConfigFailed() {
            Bundle mResults = new Bundle();
            mResults.putInt("discoverySessionId", mDiscoverySessionId);
            mEventFacade.postEvent("WifiNanSessionOnSessionConfigFailed", mResults);
        }

        @Override
        public void onSessionTerminated(int reason) {
            Bundle mResults = new Bundle();
            mResults.putInt("discoverySessionId", mDiscoverySessionId);
            mResults.putInt("reason", reason);
            mEventFacade.postEvent("WifiNanSessionOnSessionTerminated", mResults);
        }

        @Override
        public void onServiceDiscovered(Object peerHandle, byte[] serviceSpecificInfo, byte[] matchFilter) {
            Bundle mResults = new Bundle();
            mResults.putInt("discoverySessionId", mDiscoverySessionId);
            mResults.putInt("peerId", ((WifiNanManager.OpaquePeerHandle) peerHandle).peerId);
            mResults.putByteArray("serviceSpecificInfo", serviceSpecificInfo); // TODO: base64
            mResults.putByteArray("matchFilter", matchFilter); // TODO: base64
            mResults.putLong("timestampMs", System.currentTimeMillis());
            mEventFacade.postEvent("WifiNanSessionOnServiceDiscovered", mResults);
        }

        @Override
        public void onMessageSent(int messageId) {
            Bundle mResults = new Bundle();
            mResults.putInt("discoverySessionId", mDiscoverySessionId);
            mResults.putInt("messageId", messageId);
            synchronized (mLock) {
                Long startTime = mMessageStartTime.get(messageId);
                if (startTime != null) {
                    mResults.putLong("latencyMs",
                            System.currentTimeMillis() - startTime.longValue());
                    mMessageStartTime.remove(messageId);
                }
            }
            mEventFacade.postEvent("WifiNanSessionOnMessageSent", mResults);
        }

        @Override
        public void onMessageSendFailed(int messageId) {
            Bundle mResults = new Bundle();
            mResults.putInt("discoverySessionId", mDiscoverySessionId);
            mResults.putInt("messageId", messageId);
            synchronized (mLock) {
                Long startTime = mMessageStartTime.get(messageId);
                if (startTime != null) {
                    mResults.putLong("latencyMs",
                            System.currentTimeMillis() - startTime.longValue());
                    mMessageStartTime.remove(messageId);
                }
            }
            mEventFacade.postEvent("WifiNanSessionOnMessageSendFailed", mResults);
        }

        @Override
        public void onMessageReceived(Object peerHandle, byte[] message) {
            Bundle mResults = new Bundle();
            mResults.putInt("discoverySessionId", mDiscoverySessionId);
            mResults.putInt("peerId", ((WifiNanManager.OpaquePeerHandle) peerHandle).peerId);
            mResults.putByteArray("message", message); // TODO: base64
            mResults.putString("messageAsString", new String(message));
            mEventFacade.postEvent("WifiNanSessionOnMessageReceived", mResults);
        }
    }

    class WifiNanRangingListener implements RttManager.RttListener {
        private int mCallbackId;
        private int mSessionId;

        public WifiNanRangingListener(int callbackId, int sessionId) {
            mCallbackId = callbackId;
            mSessionId = sessionId;
        }

        @Override
        public void onSuccess(RttResult[] results) {
            Bundle bundle = new Bundle();
            bundle.putInt("callbackId", mCallbackId);
            bundle.putInt("sessionId", mSessionId);

            Parcelable[] resultBundles = new Parcelable[results.length];
            for (int i = 0; i < results.length; i++) {
                resultBundles[i] = WifiRttManagerFacade.RangingListener.packRttResult(results[i]);
            }
            bundle.putParcelableArray("Results", resultBundles);

            mEventFacade.postEvent("WifiNanRangingListenerOnSuccess", bundle);
        }

        @Override
        public void onFailure(int reason, String description) {
            Bundle bundle = new Bundle();
            bundle.putInt("callbackId", mCallbackId);
            bundle.putInt("sessionId", mSessionId);
            bundle.putInt("reason", reason);
            bundle.putString("description", description);
            mEventFacade.postEvent("WifiNanRangingListenerOnFailure", bundle);
        }

        @Override
        public void onAborted() {
            Bundle bundle = new Bundle();
            bundle.putInt("callbackId", mCallbackId);
            bundle.putInt("sessionId", mSessionId);
            mEventFacade.postEvent("WifiNanRangingListenerOnAborted", bundle);
        }

    }

    class WifiNanStateChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            boolean isAvailable = mMgr.isAvailable();
            if (!isAvailable) {
                wifiNanDestroyAll();
            }
            mEventFacade.postEvent(isAvailable ? "WifiNanAvailable" : "WifiNanNotAvailable",
                    new Bundle());
        }
    }
}
