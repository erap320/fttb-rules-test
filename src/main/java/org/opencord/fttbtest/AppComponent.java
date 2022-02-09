/*
 * Copyright 2022-present Open Networking Foundation
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
package org.opencord.fttbtest;

import org.onlab.packet.MacAddress;
import org.onlab.packet.TpPort;
import org.onlab.packet.VlanId;
import org.onosproject.app.ApplicationService;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.*;
import org.onosproject.net.meter.*;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true,
           service = {SomeInterface.class})
public class AppComponent implements SomeInterface {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ApplicationService applicationService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected MeterService meterService;

    private static final int NONE_TP_ID = -1;
    private static final int DEFAULT_TP_ID_DEFAULT = 64;
    private Long createMetadata(VlanId innerVlan, int techProfileId, PortNumber egressPort) {
        if (techProfileId == NONE_TP_ID) {
            techProfileId = DEFAULT_TP_ID_DEFAULT;
        }

        return ((long) (innerVlan.id()) << 48 | (long) techProfileId << 32) | egressPort.toLong();
    }

    protected Long createTechProfValueForWriteMetadata(VlanId cVlan, int techProfileId, MeterId upstreamOltMeterId) {
        Long writeMetadata;

        if (cVlan == null || VlanId.NONE.equals(cVlan)) {
            writeMetadata = (long) techProfileId << 32;
        } else {
            writeMetadata = ((long) (cVlan.id()) << 48 | (long) techProfileId << 32);
        }
        if (upstreamOltMeterId == null) {
            return writeMetadata;
        } else {
            return writeMetadata | upstreamOltMeterId.id();
        }
    }

    private static final PortNumber UNI = PortNumber.portNumber(256);
    private static final PortNumber NNI = PortNumber.portNumber(16777216);
    private static final int TECH_PROFILE = 64;

    private Meter meter;
    private FlowRule[] rules;

    private byte[] dpuMac = {0x2e, 0xa, 0x0, 0x1, 0x0, 0x0};

    private FlowRule other;

    private CompletableFuture<Object> createMeter(){
        CompletableFuture<Object> meterFuture = new CompletableFuture<>();

        List<Band> bands = new ArrayList<>();

        bands.add(DefaultBand.builder()
                .withRate(100000) //already Kbps
                .burstSize(5000) // already Kbits
                .ofType(Band.Type.DROP) // no matter
                .build());

        bands.add(DefaultBand.builder()
                .withRate(300000) //already Kbps
                .burstSize(10000) // already Kbits
                .ofType(Band.Type.DROP) // no matter
                .build());

        bands.add(DefaultBand.builder()
                .withRate(100000) //already Kbps
                .burstSize(0) // already Kbits
                .ofType(Band.Type.DROP) // no matter
                .build());

        MeterRequest meterRequest = DefaultMeterRequest.builder()
                .withBands(bands)
                .withUnit(Meter.Unit.KB_PER_SEC)
                .withContext(new MeterContext() {
                    @Override
                    public void onSuccess(MeterRequest op) {
                        meterFuture.complete(null);
                    }

                    @Override
                    public void onError(MeterRequest op, MeterFailReason reason) {
                        meterFuture.complete(reason);
                    }
                })
                .forDevice(DeviceId.deviceId("of:00000a0a0a0a0a0a"))
                .fromApp(applicationService.getId("org.opencord.olt"))
                .burst()
                .add();

        // creating the meter
        meter = meterService.submit(meterRequest);

        return meterFuture;
    }

    private void createRulesWithMeter(MeterId meterId)
    {
        rules = new FlowRule[10];

        ///// DHCP trap rule #1
        {
            TrafficSelector selector = DefaultTrafficSelector.builder()
                    .matchInPort(UNI)
                    .matchVlanId(VlanId.vlanId((short) 6))
                    .matchEthType((short) 0x0800)
                    .matchIPProtocol((byte) 17)
                    .matchUdpSrc(TpPort.tpPort(68))
                    .build();

            TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                    .immediate()
                    .setOutput(PortNumber.CONTROLLER)
                    .writeMetadata(createTechProfValueForWriteMetadata(VlanId.vlanId((short) 60), TECH_PROFILE, meterId), 0)
                    .meter(meterId)
                    .setVlanId(VlanId.vlanId((short) 60))
                    .build();

            rules[0] = DefaultFlowRule.builder()
                    .makePermanent()
                    .withPriority(40000)
                    .forTable(0)
                    .forDevice(DeviceId.deviceId("of:00000a0a0a0a0a0a"))
                    .fromApp(applicationService.getId("org.opencord.olt"))
                    .withSelector(selector)
                    .withTreatment(treatment)
                    .build();
        }

        ///// DHCP trap rule #2
        {
            TrafficSelector selector = DefaultTrafficSelector.builder()
                    .matchInPort(NNI)
                    .matchVlanId(VlanId.vlanId((short)60))
                    .matchEthType((short)0x0800)
                    .matchIPProtocol((byte)17)
                    .matchUdpSrc(TpPort.tpPort(67))
                    .build();

            TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                    .immediate()
                    .setOutput(PortNumber.CONTROLLER)
                    .build();

            rules[1] = DefaultFlowRule.builder()
                    .makePermanent()
                    .withPriority(40000)
                    .forTable(0)
                    .forDevice(DeviceId.deviceId("of:00000a0a0a0a0a0a"))
                    .fromApp(applicationService.getId("org.opencord.olt"))
                    .withSelector(selector)
                    .withTreatment(treatment)
                    .build();
        }

        ///// DPU-MGMT and ANCP switching Upstream #1
        {
            TrafficSelector selector = DefaultTrafficSelector.builder()
                    .matchInPort(UNI)
                    .matchVlanId(VlanId.vlanId((short)6))
                    .matchVlanPcp((byte)3)
                    .build();

            TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                    .immediate()
                    .setVlanId(VlanId.vlanId((short)60))
                    .setVlanPcp((byte)7)
                    .writeMetadata(createMetadata(VlanId.vlanId((short)0), TECH_PROFILE, NNI), 0)
                    .meter(meterId)
                    .transition(1)
                    .build();

            rules[2] = DefaultFlowRule.builder()
                    .makePermanent()
                    .withPriority(1000)
                    .forTable(0)
                    .forDevice(DeviceId.deviceId("of:00000a0a0a0a0a0a"))
                    .fromApp(applicationService.getId("org.opencord.olt"))
                    .withSelector(selector)
                    .withTreatment(treatment)
                    .build();
        }

        ///// DPU-MGMT and ANCP switching Upstream #2
        {
            TrafficSelector selector = DefaultTrafficSelector.builder()
                    .matchInPort(UNI)
                    .matchVlanId(VlanId.vlanId((short)6))
                    .matchEthSrc(MacAddress.valueOf(dpuMac))
                    .build();

            TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                    .immediate()
                    .setOutput(NNI)
                    .writeMetadata(createMetadata(VlanId.vlanId((short)0), TECH_PROFILE, PortNumber.portNumber(0)), 0)
                    .meter(meterId)
                    .build();

            rules[3] = DefaultFlowRule.builder()
                    .makePermanent()
                    .withPriority(1000)
                    .forTable(1)
                    .forDevice(DeviceId.deviceId("of:00000a0a0a0a0a0a"))
                    .fromApp(applicationService.getId("org.opencord.olt"))
                    .withSelector(selector)
                    .withTreatment(treatment)
                    .build();
        }

        ///// DPU-MGMT and ANCP switching Downstream #1
        {
            TrafficSelector selector = DefaultTrafficSelector.builder()
                    .matchInPort(NNI)
                    .matchVlanId(VlanId.vlanId((short)6))
                    .matchEthDst(MacAddress.valueOf(dpuMac))
                    .build();

            TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                    .immediate()
                    .writeMetadata(createMetadata(VlanId.vlanId((short)4096), TECH_PROFILE, UNI), 0)
                    .meter(meterId)
                    .transition(1)
                    .build();

            rules[4] = DefaultFlowRule.builder()
                    .makePermanent()
                    .withPriority(1000)
                    .forTable(0)
                    .forDevice(DeviceId.deviceId("of:00000a0a0a0a0a0a"))
                    .fromApp(applicationService.getId("org.opencord.olt"))
                    .withSelector(selector)
                    .withTreatment(treatment)
                    .build();
        }

        ///// DPU-MGMT and ANCP switching Downstream #2
        {
            TrafficSelector selector = DefaultTrafficSelector.builder()
                    .matchInPort(NNI)
                    .matchVlanId(VlanId.vlanId((short)6))
                    .matchEthDst(MacAddress.valueOf(dpuMac))
                    .build();

            TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                    .immediate()
                    .setVlanId(VlanId.vlanId((short)4))
                    .setOutput(UNI)
                    .writeMetadata(createMetadata(VlanId.vlanId((short)0), TECH_PROFILE, PortNumber.portNumber(0)), 0)
                    .meter(meterId)
                    .build();

            rules[5] = DefaultFlowRule.builder()
                    .makePermanent()
                    .withPriority(1000)
                    .forTable(1)
                    .forDevice(DeviceId.deviceId("of:00000a0a0a0a0a0a"))
                    .fromApp(applicationService.getId("org.opencord.olt"))
                    .withSelector(selector)
                    .withTreatment(treatment)
                    .build();
        }

        ///// Subscriber Upstream #1
        {
            TrafficSelector selector = DefaultTrafficSelector.builder()
                    .matchInPort(UNI)
                    .matchVlanId(VlanId.vlanId((short)101))
                    .build();

            TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                    .immediate()
                    .writeMetadata(createMetadata(VlanId.vlanId((short)0), TECH_PROFILE, NNI), 0)
                    .meter(meterId)
                    .transition(1)
                    .build();

            rules[6] = DefaultFlowRule.builder()
                    .makePermanent()
                    .withPriority(1000)
                    .forTable(0)
                    .forDevice(DeviceId.deviceId("of:00000a0a0a0a0a0a"))
                    .fromApp(applicationService.getId("org.opencord.olt"))
                    .withSelector(selector)
                    .withTreatment(treatment)
                    .build();
        }

        ///// Subscriber Upstream #2
        {
            TrafficSelector selector = DefaultTrafficSelector.builder()
                    .matchInPort(UNI)
                    .matchVlanId(VlanId.vlanId((short)101))
                    .build();

            TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                    .immediate()
                    .setVlanId(VlanId.vlanId((short)3101))
                    .setOutput(NNI)
                    .writeMetadata(createMetadata(VlanId.vlanId((short)0), TECH_PROFILE, PortNumber.portNumber(0)), 0)
                    .meter(meterId)
                    .build();

            rules[7] = DefaultFlowRule.builder()
                    .makePermanent()
                    .withPriority(1000)
                    .forTable(1)
                    .forDevice(DeviceId.deviceId("of:00000a0a0a0a0a0a"))
                    .fromApp(applicationService.getId("org.opencord.olt"))
                    .withSelector(selector)
                    .withTreatment(treatment)
                    .build();
        }

        ///// Subscriber Downstream #1
        {
            TrafficSelector selector = DefaultTrafficSelector.builder()
                    .matchInPort(NNI)
                    .matchVlanId(VlanId.vlanId((short)3101))
                    .build();

            TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                    .immediate()
                    .setVlanId(VlanId.vlanId((short)101))
                    .writeMetadata(createMetadata(VlanId.vlanId((short)4096), TECH_PROFILE, UNI), 0)
                    .meter(meterId)
                    .transition(1)
                    .build();

            rules[8] = DefaultFlowRule.builder()
                    .makePermanent()
                    .withPriority(1000)
                    .forTable(0)
                    .forDevice(DeviceId.deviceId("of:00000a0a0a0a0a0a"))
                    .fromApp(applicationService.getId("org.opencord.olt"))
                    .withSelector(selector)
                    .withTreatment(treatment)
                    .build();
        }

        ///// Subscriber Downstream #2
        {
            TrafficSelector selector = DefaultTrafficSelector.builder()
                    .matchInPort(NNI)
                    .matchMetadata(3101)
                    .matchVlanId(VlanId.vlanId((short)3101))
                    .build();

            TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                    .immediate()
                    .setOutput(UNI)
                    .writeMetadata(createMetadata(VlanId.vlanId((short)0), TECH_PROFILE, PortNumber.portNumber(0)), 0)
                    .meter(meterId)
                    .build();

            rules[9] = DefaultFlowRule.builder()
                    .makePermanent()
                    .withPriority(1000)
                    .forTable(1)
                    .forDevice(DeviceId.deviceId("of:00000a0a0a0a0a0a"))
                    .fromApp(applicationService.getId("org.opencord.olt"))
                    .withSelector(selector)
                    .withTreatment(treatment)
                    .build();
        }
    }

    @Activate
    protected void activate() {
        log.info("FTTB test started");

        CompletableFuture<Object> meterFuture = createMeter();

        // wait for the meter to be completed
        meterFuture.thenAccept(error -> {
            if (error != null) {
                log.error("Cannot create meter, TODO address me");
            }

            createRulesWithMeter((MeterId) meter.meterCellId());

            flowRuleService.applyFlowRules(rules[1], rules[2]);
        });
    }

    @Deactivate
    protected void deactivate() {
        flowRuleService.removeFlowRules(rules[1], rules[2]);
        log.info("FTTB test stopped");
    }

    @Modified
    public void modified(ComponentContext context) {
        log.info("Reconfigured");
    }

    @Override
    public void someMethod() {
        log.info("Invoked");
    }

}
