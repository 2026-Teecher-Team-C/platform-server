package com.teecherteamc.platform.verdict.infrastructure;

import com.teecherteamc.platform.verdict.domain.SpoolPort;
import com.teecherteamc.platform.verdict.domain.SpoolResult;
import com.teecherteamc.platform.verdict.domain.SpoolSession;
import com.teecherteamc.platform.verdict.domain.SpoolSizeExceededException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class FileSpoolPortImpl implements SpoolPort {

    // TODO(sprint-4): policy 컨텍스트의 GetPolicy(file_type_policies.max_inspect_size_bytes)가
    // 생기면 이 설정값을 대체한다. 지금은 엔진 자체 상한(ENGINE_MAX_FILE_BYTES=256MB)과 맞춘
    // 안전장치일 뿐이다. 테스트에서 작은 값으로 오버라이드할 수 있게 설정으로 뺐다.
    private final long maxSpoolBytes;

    FileSpoolPortImpl(@Value("${submit-file.max-spool-bytes:268435456}") long maxSpoolBytes) {
        this.maxSpoolBytes = maxSpoolBytes;
    }

    @Override
    public SpoolSession open() {
        Path path;
        try {
            path = Files.createTempFile("submit-file-", ".spool");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            return new FileSpoolSession(path, maxSpoolBytes);
        } catch (RuntimeException e) {
            // 생성자가 실패하면 세션을 쥔 사람이 아무도 없다 — 방금 만든 빈 파일이 안 지워지고 남는다.
            deleteQuietly(path);
            throw e;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 정리 실패까지 여기서 또 던지면 원래 예외가 묻힌다 — 로그 없이 조용히 넘어간다.
        }
    }

    private static final class FileSpoolSession implements SpoolSession {
        private final Path path;
        private final long maxSpoolBytes;
        private final MessageDigest digest;
        private final OutputStream out;
        private long bytesWritten;

        FileSpoolSession(Path path, long maxSpoolBytes) {
            this.path = path;
            this.maxSpoolBytes = maxSpoolBytes;
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
                this.out = Files.newOutputStream(path);
            } catch (NoSuchAlgorithmException | IOException e) {
                throw new IllegalStateException("스풀 세션을 열 수 없다", e);
            }
        }

        @Override
        public void write(byte[] chunk) {
            bytesWritten += chunk.length;
            if (bytesWritten > maxSpoolBytes) {
                throw new SpoolSizeExceededException(
                        "스풀이 상한(%d bytes)을 넘었다".formatted(maxSpoolBytes));
            }
            try {
                out.write(chunk);
                digest.update(chunk);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public SpoolResult finish() {
            closeQuietly();
            String sha256 = HexFormat.of().formatHex(digest.digest());
            return new SpoolResult(path, sha256, bytesWritten);
        }

        @Override
        public void delete() {
            closeQuietly();
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void closeQuietly() {
            try {
                out.close();
            } catch (IOException ignored) {
                // finish() 뒤에 delete()가 또 불릴 수 있다 — 이미 닫혀 있어도 괜찮다.
            }
        }
    }
}
