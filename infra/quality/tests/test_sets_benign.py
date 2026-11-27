import hashlib
import random

from quality.sets import TLSH, pick_variant_origins, scan_benign


def random_bytes(n, seed=1):
    return random.Random(seed).randbytes(n)


def test_scan_benign(tmp_path):
    (tmp_path / "tiny.bin").write_bytes(b"x" * 49)
    (tmp_path / "flat.bin").write_bytes(b"a" * 1024)
    data = random_bytes(4096)
    (tmp_path / "sub").mkdir()
    (tmp_path / "sub" / "good.dll").write_bytes(data)
    listed = random_bytes(4096, seed=2)
    (tmp_path / "listed.exe").write_bytes(listed)
    listed_sha = hashlib.sha256(listed).hexdigest()

    items, counts = scan_benign(tmp_path, blacklist={listed_sha})

    assert counts["too_small"] == 1
    assert counts["tnull"] == 1
    assert counts["in_blacklist"] == 1
    assert counts["selected"] == 1
    good = next(i for i in items if i.variant == "")
    assert good.set == "B"
    assert good.sha256 == hashlib.sha256(data).hexdigest()
    assert TLSH.match(good.tlsh)
    assert good.file_size == 4096
    assert good.file_type == "dll"
    assert good.family == "sub/good.dll"
    flagged = next(i for i in items if i.variant == "in_blacklist")
    assert flagged.sha256 == listed_sha


def test_pick_variant_origins(tmp_path):
    (tmp_path / "7z-setup.exe").write_bytes(b"MZ" + random_bytes(5000))
    (tmp_path / "x").mkdir()
    for n, size in enumerate([2_000, 50_000, 300_000, 2_000_000, 20_000_000]):
        (tmp_path / "x" / f"pe{n}.dll").write_bytes(b"MZ" + random_bytes(size, seed=n))
    (tmp_path / "x" / "notpe.dat").write_bytes(b"PK" + random_bytes(60_000))
    items, _ = scan_benign(tmp_path, blacklist=set())

    picked = pick_variant_origins(items, tmp_path, installer_names={"7z-setup.exe"}, count=4, seed=1)

    names = {p.name for p in picked}
    assert len(picked) == 4
    assert "7z-setup.exe" not in names
    assert "notpe.dat" not in names
    sizes = sorted(p.stat().st_size for p in picked)
    assert sizes[0] < 100_000 and sizes[-1] >= 10_000_000


def test_변종_원본은_내부_dll만_고르고_위험한_파일은_뺀다(tmp_path):
    import tlsh as py_tlsh
    files = {
        "app/core.dll": b"MZ" + random_bytes(60_000, seed=21),
        "app/node.exe": b"MZ" + random_bytes(60_000, seed=22),
        "app/vcruntime140.dll": b"MZ" + random_bytes(60_000, seed=23),
        "app/u0": b"MZ" + random_bytes(60_000, seed=24),
        "app/listed.dll": b"MZ" + random_bytes(60_000, seed=25),
        "app/near.dll": b"MZ" + random_bytes(60_000, seed=26),
    }
    for name, data in files.items():
        (tmp_path / name).parent.mkdir(exist_ok=True)
        (tmp_path / name).write_bytes(data)
    items, _ = scan_benign(tmp_path, blacklist=set())
    listed = hashlib.sha256(files["app/listed.dll"]).hexdigest()
    installer_tlsh = [py_tlsh.hash(files["app/near.dll"] + b"tail")]

    picked = pick_variant_origins(items, tmp_path, installer_names=set(), count=10, seed=1,
                                  exclude_shas={listed}, installer_tlsh=installer_tlsh)

    assert [p.name for p in picked] == ["core.dll"]
