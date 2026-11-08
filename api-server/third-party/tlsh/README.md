# Official TLSH source module

TLSH fingerprint calculation stays in the Java API server. Commit and publication still require separate user approval.

- Upstream: https://github.com/trendmicro/tlsh
- Fixed revision: `ebdec8fde93a4ac359437f4f3796c78d3ae433bf`
- Original paths: `java/src/main/java/com/trendmicro/tlsh/{BucketOption,ChecksumOption,Tlsh,TlshCreator,TlshUtil,VersionOption}.java`
- All six source files are byte-for-byte copies, no algorithm changes.
- Upstream offers Apache-2.0 OR BSD-3-Clause. This integration elects **Apache-2.0**.
- `LICENSE`, `NOTICE.txt` and every source copyright header are retained. The Gradle module includes the first two under `META-INF` in its JAR.
- This is a local source-build module, not an asserted Maven Central coordinate.
- Upstream Java README: https://github.com/trendmicro/tlsh/blob/ebdec8fde93a4ac359437f4f3796c78d3ae433bf/java/README.md

Parameters: 128 buckets, one-byte checksum, five-byte sliding window, T1 encoding, `getHash(true)` / `isValid(true)` for the official 50-byte minimum. The Java default without force requires 256 bytes. Length-aware distance uses `totalDiff(other, true)` only in reference verification, not production verdicts.

Reference C++ measurements, hashes, real test results and the Java placement decision are recorded in `../../../docs/tlsh-pr1-report.md` (from this directory).
