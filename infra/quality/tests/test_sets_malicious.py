from datetime import datetime, timezone

from quality.records import Item, read_items, write_items
from quality.sets import select_malicious

SINCE = datetime(2026, 11, 17, tzinfo=timezone.utc)
TLSH = "T1" + "A" * 70
HEADER = ('# "first_seen_utc","sha256_hash","md5_hash","sha1_hash","reporter","file_name","file_type_guess",'
          '"mime_type","signature","clamav","vtpercent","imphash","ssdeep","tlsh"')


def sha(ch):
    return ch * 64


def row(first_seen, sha256, tlsh, signature="AgentTesla", file_type="exe"):
    return (f'"{first_seen}", "{sha256}", "m", "s", "r", "a.exe", "{file_type}", "x", "{signature}", '
            f'"n/a", "n/a", "n/a", "n/a", "{tlsh}"')


def lines(*rows):
    return ["#", HEADER, *rows, "#"]


def test_고르는_조건():
    items, counts = select_malicious(lines(
        row("2026-11-16 23:59:59", sha("1"), TLSH),
        row("2026-11-17 00:00:00", sha("2"), TLSH),
        row("2026-11-18 10:00:00", sha("3"), "n/a"),
        row("2026-11-18 10:00:00", sha("4"), TLSH.lower()),
        row("2026-11-18 10:00:00", sha("5"), TLSH),
        row("2026-11-18 10:00:00", sha("2"), TLSH),
        row("2026-11-18 10:00:00", "not-a-hash", TLSH),
    ), blacklist={sha("5")}, since=SINCE)

    assert [i.sha256 for i in items] == [sha("2")]
    assert counts["old"] == 1
    assert counts["no_tlsh"] == 1
    assert counts["bad_tlsh"] == 1
    assert counts["in_blacklist"] == 1
    assert counts["duplicate"] == 1
    assert counts["bad_sha"] == 1
    assert counts["selected"] == 1


def test_레코드_값():
    items, _ = select_malicious(lines(
        row("2026-11-20 01:02:03", sha("a"), TLSH, signature="Mirai", file_type="elf"),
        row("2026-11-20 01:02:03", sha("b"), TLSH, signature="n/a"),
    ), blacklist=set(), since=SINCE)

    first, second = items
    assert first == Item(item_id="M-" + sha("a")[:12], set="M", sha256=sha("a"), tlsh=TLSH, file_size=0,
                         family="Mirai", file_type="elf", variant="", origin_sha256="")
    assert second.family == ""


def test_items_csv_왕복(tmp_path):
    item = Item(item_id="B-1", set="B", sha256=sha("c"), tlsh=TLSH, file_size=10, family="", file_type="dll",
                variant="", origin_sha256="")
    path = tmp_path / "items.csv"

    write_items(path, [item])

    assert read_items(path) == [item]
