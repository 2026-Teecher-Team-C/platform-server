package com.teecherteamc.platform;

import com.teecherteamc.platform.proto.engine.v1.DetectionEngineGrpc;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.grpc.client.ImportGrpcClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ImportGrpcClients(target = "detection-engine",
        types = {DetectionEngineGrpc.DetectionEngineStub.class, DetectionEngineGrpc.DetectionEngineBlockingStub.class})
// 블룸 필터 주기 재구축(BloomFilterRebuildService)에 필요 — 이 레포 최초의 스케줄링 사용.
@EnableScheduling
public class ApiServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiServerApplication.class, args);
    }

}
