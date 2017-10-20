/*
 * Copyright (C) 2017 The Android Open Source Project
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
import android.content.pm.PackageManager;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.RangingResultCallback;
import android.net.wifi.rtt.WifiRttManager;
import android.os.Bundle;
import android.os.Parcelable;

import com.android.internal.annotations.GuardedBy;

import libcore.util.HexEncoding;

import com.googlecode.android_scripting.facade.EventFacade;
import com.googlecode.android_scripting.facade.FacadeManager;
import com.googlecode.android_scripting.jsonrpc.RpcReceiver;
import com.googlecode.android_scripting.rpc.Rpc;
import com.googlecode.android_scripting.rpc.RpcParameter;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.List;

/**
 * Facade for RTTv2 manager.
 */
public class WifiRtt2ManagerFacade extends RpcReceiver {
    private final Service mService;
    private final EventFacade mEventFacade;

    private final Object mLock = new Object(); // lock access to the following vars

    @GuardedBy("mLock")
    private WifiRttManager mMgr;

    @GuardedBy("mLock")
    private int mNextRangingResultCallbackId = 1;

    public WifiRtt2ManagerFacade(FacadeManager manager) {
        super(manager);
        mService = manager.getService();
        mEventFacade = manager.getReceiver(EventFacade.class);

        mMgr = (WifiRttManager) mService.getSystemService(Context.WIFI_RTT2_SERVICE);
    }

    @Override
    public void shutdown() {
        // empty
    }

    @Rpc(description = "Does the device support the Wi-Fi RTT feature?")
    public Boolean doesDeviceSupportWifiRttFeature() {
        return mService.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_RTT);
    }

    /**
     * Converts an array of 6 bytes to a HexEncoded String with format: "XX:XX:XX:XX:XX:XX", where X
     * is any hexadecimal digit.
     *
     * @param macArray byte array of mac values, must have length 6
     */
    public static String macAddressFromByteArray(byte[] macArray) {
        if (macArray == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(macArray.length * 3 - 1);
        for (int i = 0; i < macArray.length; i++) {
            if (i != 0) sb.append(":");
            sb.append(new String(HexEncoding.encode(macArray, i, 1)));
        }
        return sb.toString().toLowerCase();
    }

    /**
     * Start Wi-Fi RTT ranging to an Acccess Point using the given scan results. Returns the id
     * associated with the listener used for ranging. The ranging result event will be decorated
     * with the listener id.
     */
    @Rpc(description = "Start ranging to an Access Points.", returns = "Id of the listener "
            + "associated with the started ranging.")
    public Integer wifiRttStartRangingToAccessPoints(
            @RpcParameter(name = "scanResults") JSONArray scanResults) throws JSONException {

        synchronized (mLock) {
            int id = mNextRangingResultCallbackId++;
            RangingResultCallback callback = new RangingResultCallbackFacade(id);
            mMgr.startRanging(new RangingRequest.Builder().addAccessPoints(
                    WifiJsonParser.getScanResults(scanResults)).build(), callback, null);
            return id;
        }
    }

    @Rpc(description = "Start ranging to an Aware peer.", returns = "Id of the listener "
            + "associated with the started ranging.")
    public Integer wifiRttStartRangingToAwarePeerMac(
            @RpcParameter(name = "peerMac") String peerMac) {
        synchronized (mLock) {
            int id = mNextRangingResultCallbackId++;
            RangingResultCallback callback = new RangingResultCallbackFacade(id);
            mMgr.startRanging(new RangingRequest.Builder().addWifiAwarePeer(
                    HexEncoding.decode(peerMac.toCharArray(), false)).build(), callback, null);
            return id;
        }
    }

    @Rpc(description = "Start ranging to an Aware peer.", returns = "Id of the listener "
            + "associated with the started ranging.")
    public Integer wifiRttStartRangingToAwarePeerId(
            @RpcParameter(name = "peer ID") Integer peerId) {
        synchronized (mLock) {
            int id = mNextRangingResultCallbackId++;
            RangingResultCallback callback = new RangingResultCallbackFacade(id);
            mMgr.startRanging(
                    new RangingRequest.Builder().addWifiAwarePeer(new PeerHandle(peerId)).build(),
                    callback, null);
            return id;
        }
    }

    private class RangingResultCallbackFacade extends RangingResultCallback {
        private int mCallbackId;

        RangingResultCallbackFacade(int callbackId) {
            mCallbackId = callbackId;
        }

        @Override
        public void onRangingFailure(int status) {
            Bundle msg = new Bundle();
            msg.putInt("status", status);
            mEventFacade.postEvent("WifiRttRangingFailure_" + mCallbackId, msg);
        }

        @Override
        public void onRangingResults(List<RangingResult> results) {
            Bundle msg = new Bundle();
            Parcelable[] resultBundles = new Parcelable[results.size()];
            for (int i = 0; i < results.size(); i++) {
                resultBundles[i] = packRttResult(results.get(i));
            }
            msg.putParcelableArray("Results", resultBundles);
            mEventFacade.postEvent("WifiRttRangingResults_" + mCallbackId, msg);
        }
    }

    // conversion utilities
    private static Bundle packRttResult(RangingResult result) {
        Bundle bundle = new Bundle();
        bundle.putInt("status", result.getStatus());
        if (result.getStatus() == RangingResult.STATUS_SUCCESS) { // only valid on SUCCESS
            bundle.putInt("distanceMm", result.getDistanceMm());
            bundle.putInt("distanceStdDevMm", result.getDistanceStdDevMm());
            bundle.putInt("rssi", result.getRssi());
            bundle.putLong("timestamp", result.getRangingTimestampUs());
        }
        if (result.getPeerHandle() != null) {
            bundle.putInt("peerId", result.getPeerHandle().peerId);
        }
        if (result.getMacAddress() != null) {
            bundle.putByteArray("mac", result.getMacAddress());
            bundle.putString("macAsStringBSSID", macAddressFromByteArray(result.getMacAddress()));
            bundle.putString("macAsString", new String(HexEncoding.encode(result.getMacAddress())));
        }
        return bundle;
    }
}
