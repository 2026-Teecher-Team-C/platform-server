import hashlib
import random

import pytest

from quality.mutate import MUTATIONS, build_variants

DATA = random.Random(7).randbytes(20_000) + b"\x00Hello World Config\x00" + random.Random(8).randbytes(20_000)


def sha(b):
    return hashlib.sha256(b).hexdigest()


def test_변형_종류():
    assert set(MUTATIONS) == {"append-1k", "append-64k", "patch-0.1%", "patch-1%", "patch-5%", "string-edit",
                              "truncate-1%"}


@pytest.mark.parametrize("name", sorted(MUTATIONS))
def test_변형은_sha를_바꾼다(name):
    assert sha(MUTATIONS[name](DATA, random.Random(1))) != sha(DATA)


def test_길이():
    rng = lambda: random.Random(1)  # noqa: E731
    assert len(MUTATIONS["append-1k"](DATA, rng())) == len(DATA) + 1024
    assert len(MUTATIONS["append-64k"](DATA, rng())) == len(DATA) + 65536
    assert len(MUTATIONS["truncate-1%"](DATA, rng())) == len(DATA) - len(DATA) // 100
    assert len(MUTATIONS["string-edit"](DATA, rng())) == len(DATA)
    patched = MUTATIONS["patch-1%"](DATA, rng())
    assert len(patched) == len(DATA)
    assert sum(a != b for a, b in zip(DATA, patched)) <= len(DATA) // 100


def test_같은_seed면_같은_결과():
    for name, fn in MUTATIONS.items():
        assert fn(DATA, random.Random(3)) == fn(DATA, random.Random(3)), name


def test_문자열이_없으면_string_edit을_건너뛴다(tmp_path):
    origin = tmp_path / "noascii.dll"
    origin.write_bytes(bytes([0x80 + (i % 100) for i in range(8000)]))

    items, skipped = build_variants([origin], seed=1)

    assert skipped["string-edit"] == 1
    assert "string-edit" not in {i.variant for i in items}


def test_build_variants(tmp_path):
    origin = tmp_path / "a.dll"
    origin.write_bytes(DATA)

    items, skipped = build_variants([origin], seed=1)

    origins = [i for i in items if i.variant == "origin"]
    assert len(origins) == 1 and origins[0].sha256 == sha(DATA)
    variants = [i for i in items if i.variant != "origin"]
    assert {i.variant for i in variants} == set(MUTATIONS)
    assert all(i.set == "V" and i.origin_sha256 == sha(DATA) for i in items)
    assert len({i.item_id for i in items}) == len(items)
    assert not skipped
