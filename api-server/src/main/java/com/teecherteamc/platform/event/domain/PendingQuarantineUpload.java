package com.teecherteamc.platform.event.domain;

public record PendingQuarantineUpload(long fileSize, String bucket, String key) {}
