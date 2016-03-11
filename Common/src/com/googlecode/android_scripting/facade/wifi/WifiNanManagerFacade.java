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

import android.app.Service;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.wifi.nan.ConfigRequest;
import android.net.wifi.nan.PublishConfig;
import android.net.wifi.nan.SubscribeConfig;
import android.net.wifi.nan.TlvBufferUtils;
import android.net.wifi.nan.WifiNanEventCallback;
import android.net.wifi.nan.WifiNanManager;
import android.net.wifi.nan.WifiNanSession;
import android.net.wifi.nan.WifiNanSessionCallback;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.RemoteException;

import com.googlecode.android_scripting.facade.EventFacade;
import com.googlecode.android_scripting.facade.FacadeManager;
import com.googlecode.android_scripting.jsonrpc.RpcReceiver;
import com.googlecode.android_scripting.rpc.Rpc;
import com.googlecode.android_scripting.rpc.RpcParameter;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * WifiNanManager functions.
 */
public class WifiNanManagerFacade extends RpcReceiver {
    private final Service mService;
    private final EventFacade mEventFacade;

    private WifiNanManager mMgr;
    private WifiNanSession mSession;
    private HandlerThread mNanFacadeThread;
    private ConnectivityManager mConnMgr;

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
            builder.setServiceSpecificInfo(ssi.getBytes(), ssi.length());
        }

        if (j.has("TxFilter")) {
            TlvBufferUtils.TlvConstructor constructor = getFilterData(j.getJSONObject("TxFilter"));
            builder.setTxFilter(constructor.getArray(), constructor.getActualLength());
        }

        if (j.has("RxFilter")) {
            TlvBufferUtils.TlvConstructor constructor = getFilterData(j.getJSONObject("RxFilter"));
            builder.setRxFilter(constructor.getArray(), constructor.getActualLength());
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

        if (j.has("TxFilter")) {
            TlvBufferUtils.TlvConstructor constructor = getFilterData(j.getJSONObject("TxFilter"));
            builder.setTxFilter(constructor.getArray(), constructor.getActualLength());
        }

        if (j.has("RxFilter")) {
            TlvBufferUtils.TlvConstructor constructor = getFilterData(j.getJSONObject("RxFilter"));
            builder.setRxFilter(constructor.getArray(), constructor.getActualLength());
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

        mNanFacadeThread = new HandlerThread("nanFacadeThread");
        mNanFacadeThread.start();

        mMgr = (WifiNanManager) mService.getSystemService(Context.WIFI_NAN_SERVICE);
        mMgr.connect(new NanEventCallbackPostsEvents(mNanFacadeThread.getLooper()));

        mConnMgr = (ConnectivityManager) mService.getSystemService(Context.CONNECTIVITY_SERVICE);

        mEventFacade = manager.getReceiver(EventFacade.class);
    }

    @Override
    public void shutdown() {
    }

    @Rpc(description = "Start NAN.")
    public void wifiNanEnable(@RpcParameter(name = "nanConfig") JSONObject nanConfig)
            throws RemoteException, JSONException {
        mMgr.requestConfig(getConfigRequest(nanConfig));
    }

    @Rpc(description = "Stop NAN.")
    public void wifiNanDisable() throws RemoteException, JSONException {
        mMgr.disconnect();
    }

    @Rpc(description = "Publish.")
    public void wifiNanPublish(@RpcParameter(name = "publishConfig") JSONObject publishConfig,
            @RpcParameter(name = "callbackId") Integer callbackId)
                    throws RemoteException, JSONException {
        mSession = mMgr.publish(getPublishConfig(publishConfig),
                new NanSessionCallbackPostsEvents(mNanFacadeThread.getLooper(), callbackId));
    }

    @Rpc(description = "Subscribe.")
    public void wifiNanSubscribe(@RpcParameter(name = "subscribeConfig") JSONObject subscribeConfig,
            @RpcParameter(name = "callbackId") Integer callbackId)
                    throws RemoteException, JSONException {

        mSession = mMgr.subscribe(getSubscribeConfig(subscribeConfig),
                new NanSessionCallbackPostsEvents(mNanFacadeThread.getLooper(), callbackId));
    }

    @Rpc(description = "Send peer-to-peer NAN message")
    public void wifiNanSendMessage(
            @RpcParameter(name = "peerId", description = "The ID of the peer being communicated "
                    + "with. Obtained from a previous message or match session.") Integer peerId,
            @RpcParameter(name = "message") String message,
            @RpcParameter(name = "messageId", description = "Arbitrary handle used for "
                    + "identification of the message in the message status callbacks")
            Integer messageId)
                    throws RemoteException {
        mSession.sendMessage(peerId, message.getBytes(), message.length(), messageId);
    }

    private class NanEventCallbackPostsEvents extends WifiNanEventCallback {
        public NanEventCallbackPostsEvents(Looper looper) {
            super(looper);
        }

        @Override
        public void onConfigCompleted(ConfigRequest configRequest) {
            Bundle mResults = new Bundle();
            mResults.putParcelable("configRequest", configRequest);
            mEventFacade.postEvent("WifiNanOnConfigCompleted", mResults);
        }

        @Override
        public void onConfigFailed(ConfigRequest failedConfig, int reason) {
            Bundle mResults = new Bundle();
            mResults.putParcelable("failedConfig", failedConfig);
            mResults.putInt("reason", reason);
            mEventFacade.postEvent("WifiNanOnConfigFailed", mResults);
        }

        @Override
        public void onNanDown(int reason) {
            Bundle mResults = new Bundle();
            mResults.putInt("reason", reason);
            mEventFacade.postEvent("WifiNanOnNanDown", mResults);
        }

        @Override
        public void onIdentityChanged() {
            Bundle mResults = new Bundle();
            mEventFacade.postEvent("WifiNanOnIdentityChanged", mResults);
        }
    }

    private class NanSessionCallbackPostsEvents extends WifiNanSessionCallback {
        private int mCallbackId;

        public NanSessionCallbackPostsEvents(Looper looper, int callbackId) {
            super(looper);
            mCallbackId = callbackId;
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
        public void onMatch(int peerId, byte[] serviceSpecificInfo,
                int serviceSpecificInfoLength, byte[] matchFilter, int matchFilterLength) {
            Bundle mResults = new Bundle();
            mResults.putInt("callbackId", mCallbackId);
            mResults.putInt("peerId", peerId);
            mResults.putInt("serviceSpecificInfoLength", serviceSpecificInfoLength);
            mResults.putByteArray("serviceSpecificInfo", serviceSpecificInfo); // TODO: base64
            mResults.putInt("matchFilterLength", matchFilterLength);
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
        public void onMessageReceived(int peerId, byte[] message, int messageLength) {
            Bundle mResults = new Bundle();
            mResults.putInt("callbackId", mCallbackId);
            mResults.putInt("peerId", peerId);
            mResults.putInt("messageLength", messageLength);
            mResults.putByteArray("message", message); // TODO: base64
            mResults.putString("messageAsString", new String(message, 0, messageLength));
            mEventFacade.postEvent("WifiNanSessionOnMessageReceived", mResults);
        }
    }
}
