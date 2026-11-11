package com.teecherteamc.platform.sample.domain;

import java.util.Optional;

public interface FindSamplePort {
    Optional<SampleDetail> find(String sha256);
}
