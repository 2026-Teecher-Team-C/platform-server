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

    @Test
    void 판정_근거_필드_번호가_고정되어_있다() {
        var file = com.teecherteamc.platform.proto.verdict.v1.CheckHashResponse.getDescriptor().getFile();
        assertThat(file.findMessageTypeByName("CheckHashResponse").findFieldByName("evidence"))
                .isNotNull().extracting(f -> f.getNumber()).isEqualTo(4);
        assertThat(file.findMessageTypeByName("SubmitFileResponse").findFieldByName("evidence"))
                .isNotNull().extracting(f -> f.getNumber()).isEqualTo(5);
        var evidence = file.findMessageTypeByName("VerdictEvidence");
        assertThat(evidence).isNotNull();
        assertThat(evidence.getFields()).extracting(f -> f.getName() + "=" + f.getNumber()).containsExactly(
                "kind=1", "label=2", "family=3", "tlsh_distance=4", "matched_sample_sha256=5", "rules=6",
                "previous_verdict=7");
        var rule = file.findMessageTypeByName("MatchedRule");
        assertThat(rule.getFields()).extracting(f -> f.getName() + "=" + f.getNumber())
                .containsExactly("name=1", "severity=2");
        var kind = file.findEnumTypeByName("EvidenceKind");
        assertThat(kind.getValues()).extracting(v -> v.getName() + "=" + v.getNumber()).containsExactly(
                "EVIDENCE_KIND_UNSPECIFIED=0", "EVIDENCE_KIND_BLACKLIST=1", "EVIDENCE_KIND_SIMILARITY=2",
                "EVIDENCE_KIND_YARA=3", "EVIDENCE_KIND_CACHE=4");
    }

    @Test
    void appeal_url_field_numbers_are_fixed() {
        var file = com.teecherteamc.platform.proto.verdict.v1.CheckHashResponse.getDescriptor().getFile();
        assertThat(file.findMessageTypeByName("CheckHashResponse").findFieldByName("appeal_url"))
                .isNotNull().extracting(f -> f.getNumber()).isEqualTo(5);
        assertThat(file.findMessageTypeByName("SubmitFileResponse").findFieldByName("appeal_url"))
                .isNotNull().extracting(f -> f.getNumber()).isEqualTo(6);
    }
}
