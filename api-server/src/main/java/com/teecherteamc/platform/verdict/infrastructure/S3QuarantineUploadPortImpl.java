package com.teecherteamc.platform.verdict.infrastructure;

import com.teecherteamc.platform.verdict.domain.QuarantineUploadPort;
import com.teecherteamc.platform.verdict.domain.QuarantineUploadResult;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * 격리 파일을 S3(운영)/LocalStack(로컬)에 올린다. 키는 {@code quarantine/YYYY/MM/DD/{uuid}}(확장자
 * 없음) — CLAUDE.md에 명시된 형식. 공격자가 정한 filename이나 sha256과 무관하게 매번 새 UUID를
 * 뽑는다. Content-Type은 항상 application/octet-stream으로 강제한다(파일이 실행 가능한 형태로
 * 열리지 않게).
 */
@Component
class S3QuarantineUploadPortImpl implements QuarantineUploadPort {

    private static final DateTimeFormatter KEY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final S3Client s3Client;
    private final String bucket;
    private final boolean autoCreateBucket;

    S3QuarantineUploadPortImpl(
            @Value("${quarantine.s3.bucket}") String bucket,
            @Value("${quarantine.s3.region}") String region,
            @Value("${quarantine.s3.endpoint-override}") String endpointOverride,
            @Value("${quarantine.s3.path-style-access}") boolean pathStyleAccess) {
        this.bucket = bucket;
        // endpoint-override가 있으면 로컬 LocalStack이라고 본다 — 운영(실제 AWS)은 버킷이 이미 있다고
        // 가정하고 건드리지 않는다.
        this.autoCreateBucket = !endpointOverride.isBlank();

        S3ClientBuilder builder = S3Client.builder().region(Region.of(region)).forcePathStyle(pathStyleAccess);
        if (!endpointOverride.isBlank()) {
            builder.endpointOverride(URI.create(endpointOverride));
        }
        this.s3Client = builder.build();
    }

    @PostConstruct
    void ensureBucketExists() {
        if (!autoCreateBucket) {
            return;
        }
        try {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (BucketAlreadyOwnedByYouException ignored) {
            // 이미 있다 — 로컬 재기동 때마다 매번 겪는 정상 케이스.
        }
    }

    @Override
    public QuarantineUploadResult upload(Path file) {
        String key = "quarantine/" + LocalDate.now(ZoneOffset.UTC).format(KEY_DATE_FORMAT) + "/" + UUID.randomUUID();
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType("application/octet-stream")
                        .build(),
                file);
        return new QuarantineUploadResult(bucket, key);
    }
}
