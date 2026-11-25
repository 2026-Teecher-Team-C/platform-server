"""측정 데이터 묶음 만들기: M(신종 악성), B(정상)."""
from __future__ import annotations

import csv
import re
from collections import Counter
from collections.abc import Iterable, Iterator
from datetime import datetime, timezone

from quality.records import Item

SHA256 = re.compile(r"^[0-9a-f]{64}$")
# 서버 CheckHashQuery.claimedTlsh와 같은 형식. 다르면 서버가 TLSH 없음으로 보고 근거를 남기지 않는다.
TLSH = re.compile(r"^T1[0-9A-F]{70}$")


def _value(raw: str | None) -> str:
    value = (raw or "").strip()
    return "" if value == "n/a" else value


def _rows(lines: Iterable[str]) -> Iterator[dict]:
    """MalwareBazaar CSV. 열 이름은 '# "first_seen_utc","sha256_hash",...' 주석 줄에 있다."""
    header = None
    for line in lines:
        stripped = line.strip()
        if not stripped:
            continue
        if stripped.startswith("#"):
            if header is None and "sha256_hash" in stripped:
                header = next(csv.reader([stripped.lstrip("# ")], skipinitialspace=True))
            continue
        if header is None:
            raise ValueError("CSV 헤더(sha256_hash)를 찾지 못했습니다. 내보내기 형식이 바뀌었는지 확인하세요.")
        yield dict(zip(header, next(csv.reader([stripped], skipinitialspace=True))))


def select_malicious(lines: Iterable[str], blacklist: set[str], since: datetime) -> tuple[list[Item], Counter]:
    counts: Counter = Counter()
    seen: set[str] = set()
    items: list[Item] = []
    for row in _rows(lines):
        sha256 = _value(row.get("sha256_hash")).lower()
        if not SHA256.match(sha256):
            counts["bad_sha"] += 1
            continue
        if sha256 in seen:
            counts["duplicate"] += 1
            continue
        seen.add(sha256)
        first_seen = datetime.strptime(row["first_seen_utc"].strip(), "%Y-%m-%d %H:%M:%S").replace(tzinfo=timezone.utc)
        if first_seen < since:
            counts["old"] += 1
            continue
        tlsh = _value(row.get("tlsh"))
        if not tlsh:
            counts["no_tlsh"] += 1
            continue
        if not TLSH.match(tlsh):
            counts["bad_tlsh"] += 1
            continue
        if sha256 in blacklist:
            counts["in_blacklist"] += 1
            continue
        counts["selected"] += 1
        items.append(Item(item_id="M-" + sha256[:12], set="M", sha256=sha256, tlsh=tlsh, file_size=0,
                          family=_value(row.get("signature")), file_type=_value(row.get("file_type_guess")),
                          variant="", origin_sha256=""))
    return items, counts


MIN_TLSH_BYTES = 50
SIZE_BUCKETS = (100_000, 1_000_000, 10_000_000)


def file_record(path, data: bytes, set_name: str, **extra) -> Item | None:
    """에이전트와 같은 py-tlsh로 지문을 계산한다. 계산이 안 되면(TNULL) None."""
    import hashlib

    import tlsh

    digest = tlsh.hash(data)
    if not TLSH.match(digest):
        return None
    sha256 = hashlib.sha256(data).hexdigest()
    fields = {"item_id": f"{set_name}-{sha256[:12]}", "set": set_name, "sha256": sha256, "tlsh": digest,
              "file_size": len(data), "family": "", "file_type": path.suffix.lstrip(".").lower(), "variant": "",
              "origin_sha256": ""}
    fields.update(extra)
    return Item(**fields)


def scan_benign(root, blacklist: set[str]) -> tuple[list[Item], Counter]:
    """정상 파일 폴더를 훑는다. family 열에는 root 기준 상대 경로를 둔다(어떤 파일인지 되짚기 위해)."""
    counts: Counter = Counter()
    items: list[Item] = []
    seen: set[str] = set()
    for path in sorted(p for p in root.rglob("*") if p.is_file()):
        data = path.read_bytes()
        if len(data) < MIN_TLSH_BYTES:
            counts["too_small"] += 1
            continue
        item = file_record(path, data, "B", family=str(path.relative_to(root)))
        if item is None:
            counts["tnull"] += 1
            continue
        if item.sha256 in seen:
            counts["duplicate"] += 1
            continue
        seen.add(item.sha256)
        if item.sha256 in blacklist:
            counts["in_blacklist"] += 1
            items.append(Item(**{**item.__dict__, "variant": "in_blacklist"}))
            continue
        counts["selected"] += 1
        items.append(item)
    return items, counts


def pick_variant_origins(items: list[Item], root, installer_names: set[str], count: int = 50,
                         seed: int = 0) -> list:
    """변종 원본: 설치 파일 자체는 빼고(사용자가 직접 받는 파일), PE(MZ)만, 크기 구간을 돌아가며 고른다."""
    import bisect
    import random

    buckets: list[list] = [[] for _ in range(len(SIZE_BUCKETS) + 1)]
    for item in items:
        if item.variant:
            continue
        path = root / item.family
        if path.name in installer_names:
            continue
        with open(path, "rb") as src:
            if src.read(2) != b"MZ":
                continue
        buckets[bisect.bisect_right(SIZE_BUCKETS, item.file_size)].append(path)
    rng = random.Random(seed)
    for bucket in buckets:
        rng.shuffle(bucket)
    picked: list = []
    while len(picked) < count and any(buckets):
        for bucket in buckets:
            if bucket and len(picked) < count:
                picked.append(bucket.pop())
    return picked
