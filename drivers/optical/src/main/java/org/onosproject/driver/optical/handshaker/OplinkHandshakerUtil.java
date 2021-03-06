/*
 * Copyright 2016-present Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.driver.optical.handshaker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.onosproject.drivers.optical.OpticalAdjacencyLinkService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.Annotations;
import org.onosproject.net.DefaultAnnotations;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Link;
import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;
import org.onosproject.net.device.DefaultPortDescription;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.device.PortDescription;
import org.onosproject.net.link.DefaultLinkDescription;
import org.onosproject.net.link.LinkService;
import org.onosproject.net.optical.OpticalAnnotations;
import org.onosproject.openflow.controller.Dpid;
import org.onosproject.openflow.controller.driver.OpenFlowSwitchDriver;
import org.projectfloodlight.openflow.protocol.OFCircuitPortsRequest;
import org.projectfloodlight.openflow.protocol.OFExpExtAdId;
import org.projectfloodlight.openflow.protocol.OFExpPortAdidOtn;
import org.projectfloodlight.openflow.protocol.OFExpPortAdjacency;
import org.projectfloodlight.openflow.protocol.OFExpPortAdjacencyId;
import org.projectfloodlight.openflow.protocol.OFExpPortAdjacencyRequest;
import org.projectfloodlight.openflow.protocol.OFOplinkPortPower;
import org.projectfloodlight.openflow.protocol.OFOplinkPortPowerRequest;

/**
 * Oplink handshaker utility.
 */
public class OplinkHandshakerUtil {

    // Parent driver instance
    private OpenFlowSwitchDriver driver;
    // Total count of opspec in OFExpPortAdidOtn
    private static final int OPSPEC_BYTES = 32;
    // Bit count of id in opspec
    private static final int OPSPEC_ID_BITS = 4;
    // Start byte position of mac info
    private static final int OPSPEC_MAC_POS = 18;
    // Bit offset for mac
    private static final int OPSPEC_MAC_BIT_OFF = 16;
    // Start byte position of port info
    private static final int OPSPEC_PORT_POS = 24;
    // Right bit offset for mac
    private static final int OPSPEC_PORT_BIT_OFF = 32;

    /**
     * Create a new OplinkHandshakerUtil.
     * @param driver parent driver instance
     */
    public OplinkHandshakerUtil(OpenFlowSwitchDriver driver) {
        this.driver = driver;
    }

    /**
     * Creates an oplink port power request OF message.
     *
     * @return OF message of oplink port power request
     */
    public OFOplinkPortPowerRequest buildPortPowerRequest() {
        OFOplinkPortPowerRequest request = driver.factory().buildOplinkPortPowerRequest()
                .setXid(driver.getNextTransactionId())
                .build();
        return request;
    }

    /**
     * Creates port adjacency request OF message.
     *
     * @return OF message of oplink port adjacency request
     */
    public OFExpPortAdjacencyRequest buildPortAdjacencyRequest() {
        OFExpPortAdjacencyRequest request = driver.factory().buildExpPortAdjacencyRequest()
                .setXid(driver.getNextTransactionId())
                .build();
        return request;
    }

    /**
     * Creates an oplink port description request OF message.
     *
     * @return OF message of oplink port description request
     */
    public OFCircuitPortsRequest buildCircuitPortsRequest() {
        OFCircuitPortsRequest request = driver.factory().buildCircuitPortsRequest()
                .setXid(driver.getNextTransactionId())
                .build();
        return request;
    }

    /**
     * Creates port descriptions with current power.
     *
     * @param portPowers current power
     * @return port descriptions
     */
    public List<PortDescription> buildPortPowerDescriptions(List<OFOplinkPortPower> portPowers) {
        DeviceService deviceService = driver.handler().get(DeviceService.class);
        List<Port> ports = deviceService.getPorts(driver.data().deviceId());
        HashMap<Long, OFOplinkPortPower> powerMap = new HashMap<>(portPowers.size());
        // Get each port power value
        portPowers.forEach(power -> powerMap.put((long) power.getPort(), power));
        final List<PortDescription> portDescs = new ArrayList<>();
        for (Port port : ports) {
            DefaultAnnotations.Builder builder = DefaultAnnotations.builder();
            builder.putAll(port.annotations());
            OFOplinkPortPower power = powerMap.get(port.number().toLong());
            if (power != null) {
                // power value is actually signed-short value, down casting to recover sign bit.
                builder.set(OpticalAnnotations.CURRENT_POWER, Short.toString((short) power.getPowerValue()));
            }
            portDescs.add(new DefaultPortDescription(port.number(), port.isEnabled(),
                    port.type(), port.portSpeed(), builder.build()));
        }
        return portDescs;
    }

    /**
     * Creates port descriptions with adjacency.
     *
     * @param portAds adjacency information
     * @return port descriptions
     */
    public List<PortDescription> buildPortAdjacencyDescriptions(List<OFExpPortAdjacency> portAds) {
        DeviceService deviceService = driver.handler().get(DeviceService.class);
        List<Port> ports = deviceService.getPorts(driver.data().deviceId());
        // Map port's number with port's adjacency
        HashMap<Long, OFExpPortAdjacency> adMap = new HashMap<>(portAds.size());
        portAds.forEach(ad -> adMap.put((long) ad.getPortNo().getPortNumber(), ad));
        List<PortDescription> portDescs = new ArrayList<>();
        for (Port port : ports) {
            DefaultAnnotations.Builder builder = DefaultAnnotations.builder();
            Annotations oldAnnotations = port.annotations();
            builder.putAll(oldAnnotations);
            OFExpPortAdjacency ad = adMap.get(port.number().toLong());
            OplinkPortAdjacency neighbor = getNeighbor(ad);
            if (neighbor == null) {
                // no neighbors found
                builder.remove(OpticalAnnotations.NEIGHBOR_ID);
                builder.remove(OpticalAnnotations.NEIGHBOR_PORT);
                removeLink(port.number());
            } else {
                // neighbor discovered, add to port descriptions
                String newId = neighbor.getDeviceId().toString();
                String newPort = neighbor.getPort().toString();
                // Check if annotation already exists
                if (!newId.equals(oldAnnotations.value(OpticalAnnotations.NEIGHBOR_ID)) ||
                    !newPort.equals(oldAnnotations.value(OpticalAnnotations.NEIGHBOR_PORT))) {
                    builder.set(OpticalAnnotations.NEIGHBOR_ID, newId);
                    builder.set(OpticalAnnotations.NEIGHBOR_PORT, newPort);
                }
                addLink(port.number(), neighbor);
            }
            portDescs.add(new DefaultPortDescription(port.number(), port.isEnabled(),
                    port.type(), port.portSpeed(), builder.build()));
        }
        return portDescs;
    }

    private OplinkPortAdjacency getNeighbor(OFExpPortAdjacency ad) {
        // Check input parameter
        if (ad == null) {
            return null;
        }
        // Get adjacency properties
        for (OFExpPortAdjacencyId adid : ad.getProperties()) {
            List<OFExpExtAdId> otns = adid.getAdId();
            if (otns != null && otns.size() > 0) {
                OFExpPortAdidOtn otn = (OFExpPortAdidOtn) otns.get(0);
                // ITU-T G.7714 ETH MAC Format (in second 16 bytes of the following)
                // |---------------------------------------------------------------------------|
                // | Other format (16 bytes)                                                   |
                // |---------------------------------------------------------------------------|
                // | Header (2 bytes) | ID (4 BITS) | MAC (6 bytes) | Port (4 bytes) | Unused  |
                // |---------------------------------------------------------------------------|
                ChannelBuffer buffer = ChannelBuffers.buffer(OPSPEC_BYTES);
                otn.getOpspec().write32Bytes(buffer);
                long mac = buffer.getLong(OPSPEC_MAC_POS) << OPSPEC_ID_BITS >>> OPSPEC_MAC_BIT_OFF;
                int port = (int) (buffer.getLong(OPSPEC_PORT_POS) << OPSPEC_ID_BITS >>> OPSPEC_PORT_BIT_OFF);
                // Oplink does not use the 4 most significant bytes of Dpid so Dpid can be
                // constructed from MAC address
                return new OplinkPortAdjacency(DeviceId.deviceId(Dpid.uri(new Dpid(mac))),
                        PortNumber.portNumber(port));
            }
        }
        // Returns null if no properties found
        return null;
    }

    // Add incoming link with port
    private void addLink(PortNumber portNumber, OplinkPortAdjacency neighbor) {
        ConnectPoint dst = new ConnectPoint(driver.handler().data().deviceId(), portNumber);
        ConnectPoint src = new ConnectPoint(neighbor.getDeviceId(), neighbor.getPort());
        OpticalAdjacencyLinkService adService = driver.handler().get(OpticalAdjacencyLinkService.class);
        adService.linkDetected(new DefaultLinkDescription(src, dst, Link.Type.OPTICAL));
    }

    // Remove incoming link with port if there are any.
    private void removeLink(PortNumber portNumber) {
        ConnectPoint dst = new ConnectPoint(driver.handler().data().deviceId(), portNumber);
        // Check so only incoming links are removed
        Set<Link> links = driver.handler().get(LinkService.class).getIngressLinks(dst);
        if (links.isEmpty()) {
            return;
        }
        // If link exists, remove it
        OpticalAdjacencyLinkService adService = driver.handler().get(OpticalAdjacencyLinkService.class);
        adService.linksVanished(dst);
    }

    private class OplinkPortAdjacency {
        private DeviceId deviceId;
        private PortNumber portNumber;

        public OplinkPortAdjacency(DeviceId deviceId, PortNumber portNumber) {
            this.deviceId = deviceId;
            this.portNumber = portNumber;
        }

        public DeviceId getDeviceId() {
            return deviceId;
        }

        public PortNumber getPort() {
            return portNumber;
        }
    }
}
