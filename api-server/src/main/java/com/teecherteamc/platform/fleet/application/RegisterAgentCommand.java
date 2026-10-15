package com.teecherteamc.platform.fleet.application;

public record RegisterAgentCommand(
        String enrollmentToken, String hostname, String osPlatform, String agentVersion, String hardwareUuidOrNull) {}
