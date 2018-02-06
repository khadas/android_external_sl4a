/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.googlecode.android_scripting.facade;

import android.app.Service;
import android.content.Context;
import android.net.IpSecAlgorithm;
import android.net.IpSecManager;
import android.net.IpSecManager.ResourceUnavailableException;
import android.net.IpSecManager.SecurityParameterIndex;
import android.net.IpSecManager.SpiUnavailableException;
import android.net.IpSecManager.UdpEncapsulationSocket;
import android.net.IpSecTransform;
import android.net.IpSecTransform.Builder;
import android.net.NetworkUtils;
import android.system.ErrnoException;
import android.system.Os;

import com.googlecode.android_scripting.Log;
import com.googlecode.android_scripting.jsonrpc.RpcReceiver;
import com.googlecode.android_scripting.rpc.Rpc;
import com.googlecode.android_scripting.rpc.RpcOptional;
import com.googlecode.android_scripting.rpc.RpcParameter;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.HashMap;

/*
 * Access IpSecManager functions.
 */
public class IpSecManagerFacade extends RpcReceiver {

    private final IpSecManager mIpSecManager;
    private final Service mService;
    private final Context mContext;
    private static HashMap<String, SecurityParameterIndex> sSpiHashMap =
            new HashMap<String, SecurityParameterIndex>();
    private static HashMap<String, IpSecTransform> sTransformHashMap =
            new HashMap<String, IpSecTransform>();
    private static HashMap<String, UdpEncapsulationSocket> sUdpEncapHashMap =
            new HashMap<String, UdpEncapsulationSocket>();

    public IpSecManagerFacade(FacadeManager manager) {
        super(manager);
        mService = manager.getService();
        mContext = mService.getBaseContext();
        mIpSecManager = (IpSecManager) mService.getSystemService(Context.IPSEC_SERVICE);
    }

    private IpSecTransform createTransportModeTransform(
            String encAlgo,
            byte[] cryptKey,
            String authAlgo,
            byte[] authKey,
            Integer truncBits,
            SecurityParameterIndex spi,
            InetAddress addr) {
        Builder builder = new Builder(mContext);
        builder = builder.setEncryption(new IpSecAlgorithm(encAlgo, cryptKey));
        builder =
                builder.setAuthentication(
                        new IpSecAlgorithm(authAlgo, authKey, truncBits.intValue()));
        try {
            return builder.buildTransportModeTransform(addr, spi);
        } catch (SpiUnavailableException | IOException | ResourceUnavailableException e) {
            Log.e("IpSec: Cannot create Transport mode transform" + e.toString());
        }
        return null;
    }

    private SecurityParameterIndex ipSecAllocateSpi(InetAddress inetAddr) {
        try {
            return mIpSecManager.allocateSecurityParameterIndex(inetAddr);
        } catch (ResourceUnavailableException e) {
            Log.e("IpSec: Reserve SPI failure " + e.toString());
        }
        return null;
    }

    private SecurityParameterIndex ipSecAllocateSpi(InetAddress inetAddr, int requestedSpi) {
        try {
            return mIpSecManager.allocateSecurityParameterIndex(inetAddr, requestedSpi);
        } catch (SpiUnavailableException | ResourceUnavailableException e) {
            Log.e("IpSec: Reserve SPI failure " + e.toString());
        }
        return null;
    }

    private UdpEncapsulationSocket ipSecOpenUdpEncapSocket() {
        UdpEncapsulationSocket udpEncapSocket = null;
        try {
            return mIpSecManager.openUdpEncapsulationSocket();
        } catch (ResourceUnavailableException | IOException e) {
            Log.e("IpSec: Failed to open udp encap socket " + e.toString());
        }
        return null;
    }

    private UdpEncapsulationSocket ipSecOpenUdpEncapSocket(int port) {
        try {
            return mIpSecManager.openUdpEncapsulationSocket(port);
        } catch (ResourceUnavailableException | IOException e) {
            Log.e("IpSec: Failed to open udp encap socket " + e.toString());
        }
        return null;
    }

    private String getSpiId(SecurityParameterIndex spi) {
        return "SPI:" + spi.hashCode();
    }

    private String getTransformId(IpSecTransform transform) {
        return "TRANSFORM:" + transform.hashCode();
    }

    private String getUdpEncapSockId(UdpEncapsulationSocket socket) {
        return "UDPENCAPSOCK:" + socket.hashCode();
    }

    @Rpc(description = "Apply Tranform to socket", returns = "True if transform is applied")
    public Boolean ipSecApplyTransformToSocket(Integer socketFd, Integer direction, String id) {
        FileDescriptor fd = new FileDescriptor();
        fd.setInt$(socketFd.intValue());
        IpSecTransform transform = sTransformHashMap.get(id);
        if (transform == null) {
            Log.e("IpSec: Transform does not exist for the requested id");
            return false;
        }
        try {
            mIpSecManager.applyTransportModeTransform(fd, direction.intValue(), transform);
        } catch (IOException e) {
            Log.e("IpSec: Cannot apply transform to socket " + e.toString());
            return false;
        }
        return true;
    }

    @Rpc(description = "Remove Tranform to socket", returns = "True if transform is removed")
    public Boolean ipSecRemoveTransformToSocket(Integer socketFd) {
        FileDescriptor fd = new FileDescriptor();
        fd.setInt$(socketFd.intValue());
        try {
            mIpSecManager.removeTransportModeTransforms(fd);
            return true;
        } catch (IOException e) {
            Log.e("IpSec: Failed to remove transform " + e.toString());
        }
        return false;
    }

    @Rpc(description = "Create a transform mode transform", returns = "Hash of transform object")
    public String ipSecCreateTransportModeTransform(
            String encAlgo,
            String cryptKeyString,
            String authAlgo,
            String authKeyString,
            Integer truncBits,
            String spiId,
            String addr) {
        IpSecTransform transform = null;
        InetAddress inetAddr = NetworkUtils.numericToInetAddress(addr);
        SecurityParameterIndex spi = sSpiHashMap.get(spiId);
        if (spi == null) {
            Log.e("IpSec: SPI does not exist for the requested spiId");
            return null;
        }
        byte[] cryptKey = cryptKeyString.getBytes();
        byte[] authKey = authKeyString.getBytes();
        transform =
                createTransportModeTransform(
                        encAlgo, cryptKey, authAlgo, authKey, truncBits, spi, inetAddr);
        if (transform == null) return null;
        String id = getTransformId(transform);
        sTransformHashMap.put(id, transform);
        return id;
    }

    @Rpc(description = "Get transform status", returns = "True if transform exists")
    public Boolean ipSecGetTransformStatus(String id) {
        IpSecTransform transform = sTransformHashMap.get(id);
        if (transform == null) {
            Log.e("IpSec: Transform does not exist for the requested id");
            return false;
        }
        return true;
    }

    @Rpc(description = "Destroy transport mode transform")
    public void ipSecDestroyTransportModeTransform(String id) {
        IpSecTransform transform = sTransformHashMap.get(id);
        if (transform == null) {
            Log.e("IpSec: Transform does not exist for the requested id");
            return;
        }
        transform.close();
        sTransformHashMap.remove(id);
    }

    @Rpc(description = "Open UDP encap socket", returns = "Hash of UDP encap socket object")
    public String ipSecOpenUdpEncapsulationSocket(
            @RpcParameter(name = "port") @RpcOptional Integer port) {
        UdpEncapsulationSocket udpEncapSocket = null;
        if (port == null) {
            udpEncapSocket = ipSecOpenUdpEncapSocket();
        } else {
            udpEncapSocket = ipSecOpenUdpEncapSocket(port.intValue());
        }
        if (udpEncapSocket == null) return null;
        String id = getUdpEncapSockId(udpEncapSocket);
        sUdpEncapHashMap.put(id, udpEncapSocket);
        return id;
    }

    @Rpc(description = "Close UDP encapsulation socket", returns = "True if socket is closed")
    public Boolean ipSecCloseUdpEncapsulationSocket(String id) {
        try {
            UdpEncapsulationSocket udpEncapSocket = sUdpEncapHashMap.get(id);
            udpEncapSocket.close();
            sUdpEncapHashMap.remove(id);
            return true;
        } catch (IOException e) {
            Log.e("IpSec: Failed to close udp encap socket " + e.toString());
        }
        return false;
    }

    @Rpc(description = "Allocate a Security Parameter Index", returns = "Hash of SPI object")
    public String ipSecAllocateSecurityParameterIndex(
            @RpcParameter(name = "addr") String addr,
            @RpcParameter(name = "requestedSpi") @RpcOptional Integer requestedSpi) {
        InetAddress inetAddr = NetworkUtils.numericToInetAddress(addr);
        SecurityParameterIndex spi = null;
        if (requestedSpi == null) {
            spi = ipSecAllocateSpi(inetAddr);
        } else {
            spi = ipSecAllocateSpi(inetAddr, requestedSpi.intValue());
        }
        if (spi == null) return null;
        String id = getSpiId(spi);
        sSpiHashMap.put(id, spi);
        return id;
    }

    @Rpc(description = "Get Security Parameter Index", returns = "Returns SPI value")
    public Integer ipSecGetSecurityParameterIndex(String id) {
        SecurityParameterIndex spi = sSpiHashMap.get(id);
        if (spi == null) {
            Log.d("IpSec: SPI does not exist for the requested id");
            return 0;
        }
        return spi.getSpi();
    }

    @Rpc(description = "Release a Security Parameter Index")
    public void ipSecReleaseSecurityParameterIndex(String id) {
        SecurityParameterIndex spi = sSpiHashMap.get(id);
        if (spi == null) {
            Log.d("IpSec: SPI does not exist for the requested id");
            return;
        }
        spi.close();
        sSpiHashMap.remove(id);
    }

    @Rpc(description = "Open socket", returns = "File descriptor of the socket")
    public Integer ipSecOpenSocket(Integer domain, Integer type, String addr, Integer port) {
        try {
            FileDescriptor fd = Os.socket(domain, type, 0);
            InetAddress localAddr = NetworkUtils.numericToInetAddress(addr);
            Os.bind(fd, localAddr, port.intValue());
            return fd.getInt$();
        } catch (SocketException | ErrnoException e) {
            Log.e("IpSec: Failed to open socket " + e.toString());
        }
        return -1;
    }

    @Rpc(description = "Close socket", returns = "True if socket is closed")
    public Boolean ipSecCloseSocket(Integer socketFd) {
        FileDescriptor fd = new FileDescriptor();
        fd.setInt$(socketFd.intValue());
        try {
            Os.close(fd);
            return true;
        } catch (ErrnoException e) {
            Log.e("IpSec: Failed to close socket " + e.toString());
        }
        return false;
    }

    @Rpc(description = "Send data to remote server", returns = "True if sending data successful")
    public Boolean sendDataOverSocket(
            String remoteAddr, Integer remotePort, String message, Integer socketFd) {
        byte[] data = null;
        FileDescriptor socket = new FileDescriptor();
        socket.setInt$(socketFd.intValue());

        InetAddress remote = NetworkUtils.numericToInetAddress(remoteAddr);
        try {
            data = new String(message).getBytes("UTF-8");
            int bytes = Os.sendto(socket, data, 0, data.length, 0, remote, remotePort.intValue());
            Log.d("IpSec: Sent " + String.valueOf(bytes) + " bytes");
            return true;
        } catch (UnsupportedEncodingException | ErrnoException | SocketException e) {
            Log.e("IpSec: Sending data over socket failed " + e.toString());
        }
        return false;
    }

    @Rpc(description = "Recv data from remote server", returns = "Received data on the socket")
    public String recvDataOverSocket(Integer socketFd) {
        byte[] data = new byte[2048];
        String returnData = null;
        FileDescriptor socket = new FileDescriptor();
        socket.setInt$(socketFd.intValue());

        try {
            Os.read(socket, data, 0, data.length);
            returnData = new String(data, "UTF-8");
        } catch (UnsupportedEncodingException | ErrnoException | InterruptedIOException e) {
            Log.e("IpSec: Receiving data over socket failed " + e.toString());
        }
        return returnData;
    }

    @Override
    public void shutdown() {}
}
