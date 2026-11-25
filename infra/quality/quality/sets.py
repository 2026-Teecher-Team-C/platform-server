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
