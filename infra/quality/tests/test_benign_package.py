import random

from quality.benign import process_package


def rnd(n, seed):
    return random.Random(seed).randbytes(n)


def test_패키지_하나를_처리하고_원본_후보만_남긴다(tmp_path):
    installer = tmp_path / "dl" / "7zip-24.09.exe"
    installer.parent.mkdir()
    installer.write_bytes(b"MZ" + rnd(5000, 1))
    extracted = tmp_path / "x"
    (extracted / "bin").mkdir(parents=True)
    for n, size in enumerate([3_000, 200_000, 2_000_000, 12_000_000, 4_000]):
        (extracted / "bin" / f"f{n}.dll").write_bytes(b"MZ" + rnd(size, n + 10))
    (extracted / "readme.txt").write_bytes(rnd(3000, 99))
    origins = tmp_path / "origins"

    items, counts = process_package("7zip", installer, extracted, origins, per_package=3, seed=1)

    assert counts["selected"] == 7
    assert {i.family.split("/")[0] for i in items} == {"7zip"}
    assert any(i.family == "7zip/7zip-24.09.exe" for i in items)
    kept = sorted(p.name for p in (origins / "7zip").iterdir())
    assert len(kept) == 3
    assert "7zip-24.09.exe" not in kept and "readme.txt" not in kept
    sizes = sorted((origins / "7zip" / k).stat().st_size for k in kept)
    assert sizes[0] < 100_000 and sizes[-1] >= 1_000_000


def test_풀기에_실패해도_설치_파일은_센다(tmp_path):
    installer = tmp_path / "putty.msi"
    installer.write_bytes(rnd(8000, 3))

    items, counts = process_package("putty", installer, tmp_path / "missing", tmp_path / "o", per_package=3, seed=1)

    assert counts["selected"] == 1
    assert items[0].family == "putty/putty.msi"
