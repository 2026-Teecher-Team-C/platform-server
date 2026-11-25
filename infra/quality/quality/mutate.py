"""변종 만들기. 재패킹은 하지 않는다(설계 4-4)."""
from __future__ import annotations

import random
import re
from collections import Counter
from collections.abc import Callable

from quality.records import Item
from quality.sets import file_record

ASCII_RUN = re.compile(rb"[\x20-\x7e]{4,}")
PRINTABLE = bytes(range(0x21, 0x7F))


class SkipMutation(Exception):
    """이 파일에는 적용할 수 없는 변형."""


def _append(size: int) -> Callable[[bytes, random.Random], bytes]:
    return lambda data, rng: data + rng.randbytes(size)


def _patch(ratio: float) -> Callable[[bytes, random.Random], bytes]:
    def patch(data: bytes, rng: random.Random) -> bytes:
        out = bytearray(data)
        for offset in rng.sample(range(len(data)), max(1, int(len(data) * ratio))):
            out[offset] ^= rng.randrange(1, 256)
        return bytes(out)
    return patch


def _string_edit(data: bytes, rng: random.Random) -> bytes:
    runs = list(ASCII_RUN.finditer(data))
    if not runs:
        raise SkipMutation
    out = bytearray(data)
    for run in rng.sample(runs, min(5, len(runs))):
        pos = run.start()
        out[pos] = rng.choice([c for c in PRINTABLE if c != out[pos]])
    return bytes(out)


def _truncate(data: bytes, rng: random.Random) -> bytes:
    return data[:len(data) - max(1, len(data) // 100)]


MUTATIONS: dict[str, Callable[[bytes, random.Random], bytes]] = {
    "append-1k": _append(1024),
    "append-64k": _append(65536),
    "patch-0.1%": _patch(0.001),
    "patch-1%": _patch(0.01),
    "patch-5%": _patch(0.05),
    "string-edit": _string_edit,
    "truncate-1%": _truncate,
}


def build_variants(originals: list, seed: int) -> tuple[list[Item], Counter]:
    items: list[Item] = []
    skipped: Counter = Counter()
    for path in originals:
        data = path.read_bytes()
        origin = file_record(path, data, "V", variant="origin")
        if origin is None:
            skipped["origin-tnull"] += 1
            continue
        origin = Item(**{**origin.__dict__, "origin_sha256": origin.sha256,
                         "item_id": f"V-{origin.sha256[:12]}-origin"})
        items.append(origin)
        for name, mutate in MUTATIONS.items():
            try:
                mutated = mutate(data, random.Random(f"{seed}:{origin.sha256}:{name}"))
            except SkipMutation:
                skipped[name] += 1
                continue
            item = file_record(path, mutated, "V", variant=name, origin_sha256=origin.sha256)
            if item is None:
                skipped[f"{name}-tnull"] += 1
                continue
            items.append(Item(**{**item.__dict__, "item_id": f"V-{origin.sha256[:12]}-{name}"}))
    return items, skipped
