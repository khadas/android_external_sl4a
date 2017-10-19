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

package com.googlecode.android_scripting.facade.bluetooth;

import java.util.List;

import android.app.Service;
import android.bluetooth.BluetoothInputDevice;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.os.ParcelUuid;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.googlecode.android_scripting.Log;
import com.googlecode.android_scripting.facade.EventFacade;
import com.googlecode.android_scripting.facade.FacadeManager;
import com.googlecode.android_scripting.jsonrpc.RpcReceiver;
import com.googlecode.android_scripting.rpc.Rpc;
import com.googlecode.android_scripting.rpc.RpcDefault;
import com.googlecode.android_scripting.rpc.RpcParameter;

public class BluetoothHidFacade extends RpcReceiver {
  public final static ParcelUuid[] UUIDS = { BluetoothUuid.Hid };

  private final Service mService;
  private final BluetoothAdapter mBluetoothAdapter;

  private static boolean sIsHidReady = false;
  private static BluetoothInputDevice sHidProfile = null;

  private final EventFacade mEventFacade;

  public BluetoothHidFacade(FacadeManager manager) {
    super(manager);
    mService = manager.getService();
    mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    mBluetoothAdapter.getProfileProxy(mService, new HidServiceListener(),
        BluetoothProfile.INPUT_DEVICE);
    IntentFilter pkgFilter = new IntentFilter();
    pkgFilter.addAction(BluetoothInputDevice.ACTION_CONNECTION_STATE_CHANGED);
    pkgFilter.addAction(BluetoothInputDevice.ACTION_PROTOCOL_MODE_CHANGED);
    pkgFilter.addAction(BluetoothInputDevice.ACTION_HANDSHAKE);
    pkgFilter.addAction(BluetoothInputDevice.ACTION_REPORT);
    pkgFilter.addAction(BluetoothInputDevice.ACTION_VIRTUAL_UNPLUG_STATUS);
    pkgFilter.addAction(BluetoothInputDevice.ACTION_IDLE_TIME_CHANGED);
    mService.registerReceiver(mHidServiceBroadcastReceiver, pkgFilter);
    Log.d(HidServiceBroadcastReceiver.TAG + " registered");
    mEventFacade = manager.getReceiver(EventFacade.class);
  }

  class HidServiceListener implements BluetoothProfile.ServiceListener {
    @Override
    public void onServiceConnected(int profile, BluetoothProfile proxy) {
      sHidProfile = (BluetoothInputDevice) proxy;
      sIsHidReady = true;
    }

    @Override
    public void onServiceDisconnected(int profile) {
      sIsHidReady = false;
    }
  }

  class HidServiceBroadcastReceiver extends BroadcastReceiver {
      private static final String TAG = "HidServiceBroadcastReceiver";

      @Override
      public void onReceive(Context context, Intent intent) {
          String action = intent.getAction();
          Log.d(TAG + " action=" + action);

          switch (action) {
              case BluetoothInputDevice.ACTION_CONNECTION_STATE_CHANGED: {
                  int previousState = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1);
                  int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                  Log.d("Connection state changed: " + previousState + " -> " + state);
              }
              break;
              case BluetoothInputDevice.ACTION_PROTOCOL_MODE_CHANGED: {
                  int status = intent.getIntExtra(BluetoothInputDevice.EXTRA_STATUS, -1);
                  Log.d("Protocol mode changed: " + status);
              }
              break;
              case BluetoothInputDevice.ACTION_HANDSHAKE: {
                  int status = intent.getIntExtra(BluetoothInputDevice.EXTRA_STATUS, -1);
                  Log.d("Handshake received: " + status);
              }
              break;
              case BluetoothInputDevice.ACTION_REPORT: {
                  char[] report = intent.getCharArrayExtra(BluetoothInputDevice.EXTRA_REPORT);
                  Log.d("Received report: " + String.valueOf(report));
              }
              break;
              case BluetoothInputDevice.ACTION_VIRTUAL_UNPLUG_STATUS: {
                  int status = intent.getIntExtra(
                          BluetoothInputDevice.EXTRA_VIRTUAL_UNPLUG_STATUS, -1);
                  Log.d("Virtual unplug status: " + status);
              }
              break;
              case BluetoothInputDevice.ACTION_IDLE_TIME_CHANGED: {
                  int idleTime = intent.getIntExtra(BluetoothInputDevice.EXTRA_IDLE_TIME, -1);
                  Log.d("Idle time changed: " + idleTime);
              }
              break;
              default:
                  break;
          }
      }
  }

  private final BroadcastReceiver mHidServiceBroadcastReceiver = new HidServiceBroadcastReceiver();


  public Boolean hidConnect(BluetoothDevice device) {
    if (sHidProfile == null) return false;
    return sHidProfile.connect(device);
  }

  public Boolean hidDisconnect(BluetoothDevice device) {
    if (sHidProfile == null) return false;
    return sHidProfile.disconnect(device);
  }

  @Rpc(description = "Is Hid profile ready.")
  public Boolean bluetoothHidIsReady() {
    return sIsHidReady;
  }

  @Rpc(description = "Connect to an HID device.")
  public Boolean bluetoothHidConnect(
      @RpcParameter(name = "device", description = "Name or MAC address of a bluetooth device.")
      String device)
      throws Exception {
    if (sHidProfile == null)
      return false;
    BluetoothDevice mDevice = BluetoothFacade.getDevice(BluetoothFacade.DiscoveredDevices, device);
    Log.d("Connecting to device " + mDevice.getAliasName());
    return hidConnect(mDevice);
  }

  @Rpc(description = "Disconnect an HID device.")
  public Boolean bluetoothHidDisconnect(
      @RpcParameter(name = "device", description = "Name or MAC address of a device.")
      String device)
      throws Exception {
    if (sHidProfile == null)
      return false;
    Log.d("Connected devices: " + sHidProfile.getConnectedDevices());
    BluetoothDevice mDevice = BluetoothFacade.getDevice(sHidProfile.getConnectedDevices(),
                                                        device);
    return hidDisconnect(mDevice);
  }

  @Rpc(description = "Get all the devices connected through HID.")
  public List<BluetoothDevice> bluetoothHidGetConnectedDevices() {
    while (!sIsHidReady);
    return sHidProfile.getConnectedDevices();
  }

  @Rpc(description = "Get the connection status of a device.")
  public Integer bluetoothHidGetConnectionStatus(
          @RpcParameter(name = "deviceID",
                        description = "Name or MAC address of a bluetooth device.")
          String deviceID) {
      if (sHidProfile == null) {
          return BluetoothProfile.STATE_DISCONNECTED;
      }
      List<BluetoothDevice> deviceList = sHidProfile.getConnectedDevices();
      BluetoothDevice device;
      try {
          device = BluetoothFacade.getDevice(deviceList, deviceID);
      } catch (Exception e) {
          return BluetoothProfile.STATE_DISCONNECTED;
      }
      return sHidProfile.getConnectionState(device);
  }

  @Rpc(description = "Send Set_Report command to the connected HID input device.")
  public Boolean bluetoothHidSetReport(
          @RpcParameter(name = "deviceID",
          description = "Name or MAC address of a bluetooth device.")
          String deviceID,
          @RpcParameter(name = "type")
          @RpcDefault(value = "1")
          Integer type,
          @RpcParameter(name = "report")
          String report) throws Exception {
      BluetoothDevice device = BluetoothFacade.getDevice(sHidProfile.getConnectedDevices(),
              deviceID);
      Log.d("type=" + type);
      return sHidProfile.setReport(device, (byte) (int) type, report);
  }

    /**
     * Sends the Get_Report command to the given connected HID input device.
     * @param deviceID name or MAC address or the HID input device
     * @param type Bluetooth HID report type
     * @param reportId ID for the requesting report
     * @param buffSize advised buffer size on the Bluetooth HID host
     * @return True if successfully sent the command; otherwise false
     * @throws Exception error from Bluetooth HidService
     */
  @Rpc(description = "Send Get_Report command to the connected HID input device.")
  public Boolean bluetoothHidGetReport(
          @RpcParameter(name = "deviceID",
          description = "Name or MAC address of a bluetooth device.")
          String deviceID,
          @RpcParameter(name = "type")
          @RpcDefault(value = "1")
          Integer type,
          @RpcParameter(name = "reportId")
          Integer reportId,
          @RpcParameter(name = "buffSize")
          Integer buffSize) throws Exception {
      BluetoothDevice device = BluetoothFacade.getDevice(sHidProfile.getConnectedDevices(),
              deviceID);
      Log.d("type=" + type + " reportId=" + reportId);
      return sHidProfile.getReport(device, (byte) (int) type, (byte) (int) reportId, buffSize);
  }
  /**
   * Sends a data report to the given connected HID input device.
   * @param deviceID name or MAC address or the HID input device
   * @param report the report payload
   * @return True if successfully sent the command; otherwise false
   * @throws Exception error from Bluetooth HidService
   */
  @Rpc(description = "Send data to a connected HID device.")
  public Boolean bluetoothHidSendData(
          @RpcParameter(name = "deviceID",
          description = "Name or MAC address of a bluetooth device.")
          String deviceID,
          @RpcParameter(name = "report")
          String report) throws Exception {
      BluetoothDevice device = BluetoothFacade.getDevice(sHidProfile.getConnectedDevices(),
              deviceID);
      return sHidProfile.sendData(device, report);
  }


  /**
   * Sends the virtual cable unplug command to the given connected HID input device.
   * @param deviceID name or MAC address or the HID input device
   * @return True if successfully sent the command; otherwise false
   * @throws Exception error from Bluetooth HidService
   */
  @Rpc(description = "Send virtual unplug to a connected HID device.")
  public Boolean bluetoothHidVirtualUnplug(
          @RpcParameter(name = "deviceID",
          description = "Name or MAC address of a bluetooth device.")
          String deviceID) throws Exception {
      BluetoothDevice device = BluetoothFacade.getDevice(sHidProfile.getConnectedDevices(),
              deviceID);
      return sHidProfile.virtualUnplug(device);
  }

  /**
   * Sends the Set_Priority command to the given connected HID input device.
   * @param deviceID name or MAC address or the HID input device
   * @param priority priority level
   * @return True if successfully sent the command; otherwise false
   * @throws Exception error from Bluetooth HidService
   */
  @Rpc(description = "Set priority of the profile")

  public Boolean bluetoothHidSetPriority(
          @RpcParameter(name = "deviceID",
                  description = "Name or MAC address of a bluetooth device.")
                  String deviceID,
          @RpcParameter(name = "priority")
                  Integer priority) throws Exception {
      BluetoothDevice device = BluetoothFacade.getDevice(sHidProfile.getConnectedDevices(),
              deviceID);
      return sHidProfile.setPriority(device, priority);
  }

  /**
   * Sends the Get_Priority command to the given connected HID input device.
   * @param deviceID name or MAC address or the HID input device
   * @return The value of the HID input device priority
   * @throws Exception error from Bluetooth HidService
   */
  @Rpc(description = "Get priority of the profile")
  public Integer bluetoothHidGetPriority(
          @RpcParameter(name = "deviceID",
                  description = "Name or MAC address of a bluetooth device.")
                  String deviceID) throws Exception {
      BluetoothDevice device = BluetoothFacade.getDevice(sHidProfile.getConnectedDevices(),
              deviceID);
      return sHidProfile.getPriority(device);
  }

  /**
   * Sends the Set_Protocol_Mode command to the given connected HID input device.
   * @param deviceID name or MAC address or the HID input device
   * @param protocolMode protocol mode
   * @return True if successfully sent the command; otherwise false
   * @throws Exception error from Bluetooth HidService
   */
  @Rpc(description = "Send Set_Protocol_Mode command to the connected HID input device.")
  public Boolean bluetoothHidSetProtocolMode(
          @RpcParameter(name = "deviceID",
                  description = "Name or MAC address of a bluetooth device.")
                  String deviceID,
          @RpcParameter(name = "protocolMode")
                  Integer protocolMode) throws Exception {
      BluetoothDevice device = BluetoothFacade.getDevice(sHidProfile.getConnectedDevices(),
              deviceID);
      return sHidProfile.setProtocolMode(device, protocolMode);
  }

  /**
   * Sends the Get_Protocol_Mode command to the given connected HID input device.
   * @param deviceID name or MAC address or the HID input device
   * @return True if successfully sent the command; otherwise false
   * @throws Exception error from Bluetooth HidService
   */
  @Rpc(description = "Send Get_Protocol_Mode command to the connected HID input device.")
  public Boolean bluetoothHidGetProtocolMode(
          @RpcParameter(name = "deviceID",
                  description = "Name or MAC address of a bluetooth device.")
                  String deviceID) throws Exception {
      BluetoothDevice device = BluetoothFacade.getDevice(sHidProfile.getConnectedDevices(),
              deviceID);
      return sHidProfile.getProtocolMode(device);
  }

  /**
   * Sends the Set_Idle_Time command to the given connected HID input device.
   * @param deviceID name or MAC address or the HID input device
   * @param idleTime idle time
   * @return True if successfully sent the command; otherwise false
   * @throws Exception error from Bluetooth HidService
   */
  @Rpc(description = "Send Set_Idle_Time command to the connected HID input device.")
  public Boolean bluetoothHidSetIdleTime(
          @RpcParameter(name = "deviceID",
                  description = "Name or MAC address of a bluetooth device.")
                  String deviceID,
          @RpcParameter(name = "idleTime")
                  Integer idleTime) throws Exception {
      BluetoothDevice device = BluetoothFacade.getDevice(sHidProfile.getConnectedDevices(),
              deviceID);
      return sHidProfile.setIdleTime(device, (byte) (int) idleTime);
  }

  /**
   * Sends the Get_Idle_Time command to the given connected HID input device.
   * @param deviceID name or MAC address or the HID input device
   * @return True if successfully sent the command; otherwise false
   * @throws Exception error from Bluetooth HidService
   */
  @Rpc(description = "Send Get_Idle_Time command to the connected HID input device.")
  public Boolean bluetoothHidGetIdleTime(
          @RpcParameter(name = "deviceID",
                  description = "Name or MAC address of a bluetooth device.")
                  String deviceID) throws Exception {
      BluetoothDevice device = BluetoothFacade.getDevice(sHidProfile.getConnectedDevices(),
              deviceID);
      return sHidProfile.getIdleTime(device);
  }

  @Rpc(description = "Test byte transfer.")
  public byte[] testByte() {
      byte[] bts = {0b01,0b10,0b11,0b100};
      return bts;
  }

  @Override
  public void shutdown() {
  }
}

