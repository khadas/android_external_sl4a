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
import android.net.wifi.nan.WifiNanEventCallback;
import android.net.wifi.nan.WifiNanManager;
import android.net.wifi.nan.WifiNanPublishSession;
import android.net.wifi.nan.WifiNanSession;
import android.net.wifi.nan.WifiNanSessionCallback;
import android.net.wifi.nan.WifiNanSubscribeSession;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.SparseArray;

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

    private WifiNanManager mMgr;

    private int mNextSessionId = 1;
    private SparseArray<WifiNanSession> mSessions = new SparseArray<>();

    private int getNextSessionId() {
        return mNextSessionId++;
    }

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
        if (j.has("EnableIdentityChangeCallback")) {
            builder.setEnableIdentityChangeCallback(j.getBoolean("EnableIdentityChangeCallback"));
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
            builder.setEnableTerminateNotification(j.getBoolean("EnableTerminateNotification"));
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
            builder.setServiceSpecificInfo(ssi);
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
            builder.setEnableTerminateNotification(j.getBoolean("EnableTerminateNotification"));
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
        mMgr.disconnect();
        mSessions.clear();
        mService.unregisterReceiver(mStateChangedReceiver);
    }

    @Rpc(description = "Enable NAN Usage.")
    public void wifiNanEnableUsage() throws RemoteException {
        mMgr.enableUsage();
    }

    @Rpc(description = "Disable NAN Usage.")
    public void wifiNanDisableUsage() throws RemoteException {
        mMgr.disableUsage();
    }

    @Rpc(description = "Is NAN Usage Enabled?")
    public Boolean wifiIsNanUsageEnabled() throws RemoteException {
        return mMgr.isUsageEnabled();
    }

    @Rpc(description = "Connect to NAN.")
    public void wifiNanConnect(@RpcParameter(name = "nanConfig") JSONObject nanConfig)
            throws RemoteException, JSONException {
        mMgr.connect(null, getConfigRequest(nanConfig), new NanEventCallbackPostsEvents());
    }

    @Rpc(description = "Disconnect from NAN.")
    public void wifiNanDisconnect() throws RemoteException, JSONException {
        mMgr.disconnect();
        mSessions.clear();
    }

    @Rpc(description = "Publish.")
    public Integer wifiNanPublish(@RpcParameter(name = "callbackId") Integer callbackId,
            @RpcParameter(name = "publishConfig") JSONObject publishConfig)
            throws RemoteException, JSONException {
        int sessionId = getNextSessionId();
        mMgr.publish(getPublishConfig(publishConfig),
                new NanSessionCallbackPostsEvents(callbackId, sessionId));
        return sessionId;
    }

    @Rpc(description = "Subscribe.")
    public Integer wifiNanSubscribe(@RpcParameter(name = "callbackId") Integer callbackId,
            @RpcParameter(name = "subscribeConfig") JSONObject subscribeConfig)
            throws RemoteException, JSONException {
        int sessionId = getNextSessionId();
        mMgr.subscribe(getSubscribeConfig(subscribeConfig),
                new NanSessionCallbackPostsEvents(callbackId, sessionId));
        return sessionId;
    }

    @Rpc(description = "Terminate Session.")
    public void wifiNanTerminateSession(
            @RpcParameter(name = "sessionId", description = "The session ID returned when session was created using publish or subscribe") Integer sessionId)
            throws RemoteException {
        WifiNanSession session = mSessions.get(sessionId);
        if (session == null) {
            throw new IllegalStateException(
                    "Calling wifiNanTerminateSession before session (session ID "
                    + sessionId + " is ready");
        }
        session.terminate();
        mSessions.remove(sessionId);
    }

    @Rpc(description = "Send peer-to-peer NAN message")
    public void wifiNanSendMessage(
            @RpcParameter(name = "sessionId", description = "The session ID returned when session"
                    + " was created using publish or subscribe") Integer sessionId,
            @RpcParameter(name = "peerId", description = "The ID of the peer being communicated "
                    + "with. Obtained from a previous message or match session.") Integer peerId,
            @RpcParameter(name = "message") String message,
            @RpcParameter(name = "messageId", description = "Arbitrary handle used for "
                    + "identification of the message in the message status callbacks")
            Integer messageId,
            @RpcParameter(name = "retryCount", description = "Number of retries (0 for none) if "
                    + "transmission fails due to no ACK reception") Integer retryCount)
                    throws RemoteException {
        WifiNanSession session = mSessions.get(sessionId);
        if (session == null) {
            throw new IllegalStateException("Calling wifiNanSendMessage before session (session ID "
                    + sessionId + " is ready");
        }
        byte[] bytes = message.getBytes();
        session.sendMessage(peerId, bytes, messageId, retryCount);
    }

    @Rpc(description = "Start peer-to-peer NAN ranging")
    public void wifiNanStartRanging(
            @RpcParameter(name = "callbackId") Integer callbackId,
            @RpcParameter(name = "sessionId", description = "The session ID returned when session was created using publish or subscribe") Integer sessionId,
            @RpcParameter(name = "rttParams", description = "RTT session parameters.") JSONArray rttParams) throws RemoteException, JSONException {
        WifiNanSession session = mSessions.get(sessionId);
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
        WifiNanSession session = mSessions.get(sessionId);
        if (session == null) {
            throw new IllegalStateException(
                    "Calling wifiNanStartRanging before session (session ID "
                            + sessionId + " is ready");
        }
        byte[] bytes = token.getBytes();
        return session.createNetworkSpecifier(role, peerId, bytes);
    }

    private class NanEventCallbackPostsEvents extends WifiNanEventCallback {
        @Override
        public void onConnectSuccess() {
            Bundle mResults = new Bundle();
            mEventFacade.postEvent("WifiNanOnConnectSuccess", mResults);
        }

        @Override
        public void onConnectFail(int reason) {
            Bundle mResults = new Bundle();
            mResults.putInt("reason", reason);
            mEventFacade.postEvent("WifiNanOnConnectFail", mResults);
        }

        @Override
        public void onIdentityChanged(byte[] mac) {
            Bundle mResults = new Bundle();
            mResults.putString("mac", String.valueOf(HexEncoding.encode(mac)));
            mEventFacade.postEvent("WifiNanOnIdentityChanged", mResults);
        }
    }

    private class NanSessionCallbackPostsEvents extends WifiNanSessionCallback {
        private int mCallbackId;
        private int mSessionId;

        public NanSessionCallbackPostsEvents(int callbackId, int sessionId) {
            mCallbackId = callbackId;
            mSessionId = sessionId;
        }

        @Override
        public void onPublishStarted(WifiNanPublishSession session) {
            mSessions.put(mSessionId, session);

            Bundle mResults = new Bundle();
            mResults.putInt("callbackId", mCallbackId);
            mResults.putInt("sessionId", mSessionId);
            mEventFacade.postEvent("WifiNanSessionOnPublishStarted", mResults);
        }

        @Override
        public void onSubscribeStarted(WifiNanSubscribeSession session) {
            mSessions.put(mSessionId, session);

            Bundle mResults = new Bundle();
            mResults.putInt("callbackId", mCallbackId);
            mResults.putInt("sessionId", mSessionId);
            mEventFacade.postEvent("WifiNanSessionOnSubscribeStarted", mResults);
        }

        @Override
        public void onSessionConfigSuccess() {
            Bundle mResults = new Bundle();
            mResults.putInt("callbackId", mCallbackId);
            mEventFacade.postEvent("WifiNanSessionOnSessionConfigSuccess", mResults);
        }

        @Override
        public void onSessionConfigFail(int reason) {
            Bundle mResults = new Bundle();
            mResults.putInt("callbackId", mCallbackId);
            mResults.putInt("reason", reason);
            mEventFacade.postEvent("WifiNanSessionOnSessionConfigFail", mResults);
        }

        @Override
        public void onSessionTerminated(int reason) {
            Bundle mResults = new Bundle();
            mResults.putInt("callbackId", mCallbackId);
            mResults.putInt("reason", reason);
            mEventFacade.postEvent("WifiNanSessionOnSessionTerminated", mResults);
        }

        @Override
        public void onMatch(int peerId, byte[] serviceSpecificInfo, byte[] matchFilter) {
            Bundle mResults = new Bundle();
            mResults.putInt("callbackId", mCallbackId);
            mResults.putInt("peerId", peerId);
            mResults.putByteArray("serviceSpecificInfo", serviceSpecificInfo); // TODO: base64
            mResults.putByteArray("matchFilter", matchFilter); // TODO: base64
            mEventFacade.postEvent("WifiNanSessionOnMatch", mResults);
        }

        @Override
        public void onMessageSendSuccess(int messageId) {
            Bundle mResults = new Bundle();
            mResults.putInt("callbackId", mCallbackId);
            mResults.putInt("messageId", messageId);
            mEventFacade.postEvent("WifiNanSessionOnMessageSendSuccess", mResults);
        }

        @Override
        public void onMessageSendFail(int messageId, int reason) {
            Bundle mResults = new Bundle();
            mResults.putInt("callbackId", mCallbackId);
            mResults.putInt("messageId", messageId);
            mResults.putInt("reason", reason);
            mEventFacade.postEvent("WifiNanSessionOnMessageSendFail", mResults);
        }

        @Override
        public void onMessageReceived(int peerId, byte[] message) {
            Bundle mResults = new Bundle();
            mResults.putInt("callbackId", mCallbackId);
            mResults.putInt("peerId", peerId);
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
            int status = intent.getIntExtra(WifiNanManager.EXTRA_WIFI_STATE,
                    WifiNanManager.WIFI_NAN_STATE_DISABLED);
            mEventFacade.postEvent(status == WifiNanManager.WIFI_NAN_STATE_ENABLED
                    ? "WifiNanEnabled" : "WifiNanDisabled", new Bundle());
        }
    }
}
