-- 블랙리스트에 있는 SHA-256 전부(해제된 것 포함). 신종 악성 묶음에서 빼는 기준이다.
COPY (SELECT hash_value FROM hash_blacklist WHERE hash_type = 'SHA256' ORDER BY hash_value) TO STDOUT;
