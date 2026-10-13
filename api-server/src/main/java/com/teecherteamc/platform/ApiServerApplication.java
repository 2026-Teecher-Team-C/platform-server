package com.teecherteamc.platform;

import com.teecherteamc.platform.proto.engine.v1.DetectionEngineGrpc;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.grpc.client.ImportGrpcClients;

@SpringBootApplication
@ImportGrpcClients(target = "detection-engine", types = DetectionEngineGrpc.DetectionEngineStub.class)
public class ApiServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiServerApplication.class, args);
    }

}
