package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.teecherteamc.platform.proto.agent.v1.AgentServiceGrpc;
import com.teecherteamc.platform.proto.engine.v1.DetectionEngineGrpc;
import com.teecherteamc.platform.proto.verdict.v1.VerdictServiceGrpc;
import org.junit.jupiter.api.Test;

class ProtoContractTest {

    @Test
    void unknown_similarity_enum_round_trips_through_an_old_proto3_descriptor() throws Exception {
        var current = com.teecherteamc.platform.proto.verdict.v1.DecisionSource.getDescriptor().getFile();
        var oldFile = current.toProto().toBuilder();
        for (int i=0; i<oldFile.getEnumTypeCount(); i++) {
            var type = oldFile.getEnumType(i);
            if (type.getName().equals("DecisionSource")) {
                var oldEnum = type.toBuilder().clearValue();
                type.getValueList().stream().filter(v -> v.getNumber()<=5).forEach(oldEnum::addValue);
                oldFile.setEnumType(i, oldEnum);
            }
        }
        var oldDescriptor = com.google.protobuf.Descriptors.FileDescriptor.buildFrom(oldFile.build(),
                new com.google.protobuf.Descriptors.FileDescriptor[]{com.google.protobuf.TimestampProto.getDescriptor()});
        var response = com.teecherteamc.platform.proto.verdict.v1.SubmitFileResponse.newBuilder()
                .setDecisionValue(2).setSourceValue(6).build();
        var oldResponse = com.google.protobuf.DynamicMessage.parseFrom(
                oldDescriptor.findMessageTypeByName("SubmitFileResponse"), response.toByteArray());
        var source = (com.google.protobuf.Descriptors.EnumValueDescriptor) oldResponse.getField(
                oldResponse.getDescriptorForType().findFieldByName("source"));
        assertThat(source.getNumber()).isEqualTo(6);
        var reportType = oldDescriptor.findMessageTypeByName("ReportEventRequest");
        var oldReport = com.google.protobuf.DynamicMessage.newBuilder(reportType)
                .setField(reportType.findFieldByName("decision_source"), source).build();
        var received = com.teecherteamc.platform.proto.verdict.v1.ReportEventRequest.parseFrom(oldReport.toByteArray());
        assertThat(received.getDecisionSource()).isEqualTo(
                com.teecherteamc.platform.proto.verdict.v1.DecisionSource.DECISION_SOURCE_SIMILARITY);
        assertThat(com.teecherteamc.platform.proto.verdict.v1.DecisionSource.DECISION_SOURCE_POLICY.getNumber()).isEqualTo(5);
    }

    @Test
    void 레포_루트_proto에서_gRPC_서비스_스텁이_생성된다() {
        assertThat(VerdictServiceGrpc.getServiceDescriptor().getName())
                .isEqualTo("teecher.verdict.v1.VerdictService");
        assertThat(AgentServiceGrpc.getServiceDescriptor().getName())
                .isEqualTo("teecher.agent.v1.AgentService");
        assertThat(DetectionEngineGrpc.getServiceDescriptor().getName())
                .isEqualTo("teecher.engine.v1.DetectionEngine");
    }
}
