package com.teecherteamc.platform.verdict.domain;

import java.nio.file.Path;

public record SpoolResult(Path path, String sha256, long bytesWritten) {}
