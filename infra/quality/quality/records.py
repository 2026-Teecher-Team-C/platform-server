"""측정 단계 사이를 잇는 CSV 레코드. 열 이름은 여기 한 곳에서만 정한다."""
from __future__ import annotations

import csv
from dataclasses import asdict, dataclass, fields
from pathlib import Path


@dataclass(frozen=True)
class Item:
    item_id: str
    set: str  # "M" 신종 악성, "B" 정상, "V" 변종
    sha256: str
    tlsh: str
    file_size: int
    family: str
    file_type: str
    variant: str
    origin_sha256: str


@dataclass(frozen=True)
class Response:
    item_id: str
    event_id: str
    decision: str
    source: str
    reason: str
    error: str
    pass_id: str


@dataclass(frozen=True)
class Evidence:
    event_id: str
    distance: int | None
    matched_sample_sha256: str
    classification: str
    policy_version: str
    matched_label: str


def _write(path: Path, cls, rows) -> None:
    names = [f.name for f in fields(cls)]
    with open(path, "w", newline="") as out:
        writer = csv.DictWriter(out, fieldnames=names)
        writer.writeheader()
        for row in rows:
            writer.writerow({k: ("" if v is None else v) for k, v in asdict(row).items()})


def _read(path: Path, cls, convert) -> list:
    with open(path, newline="") as src:
        return [cls(**convert(row)) for row in csv.DictReader(src)]


def write_items(path: Path, items) -> None:
    _write(path, Item, items)


def read_items(path: Path) -> list[Item]:
    return _read(path, Item, lambda r: {**r, "file_size": int(r["file_size"])})


def write_responses(path: Path, responses) -> None:
    _write(path, Response, responses)


def read_responses(path: Path) -> list[Response]:
    return _read(path, Response, lambda r: r)


def write_evidence(path: Path, evidence) -> None:
    _write(path, Evidence, evidence)


def read_evidence(path: Path) -> list[Evidence]:
    return _read(path, Evidence, lambda r: {**r, "distance": int(r["distance"]) if r["distance"] else None})
