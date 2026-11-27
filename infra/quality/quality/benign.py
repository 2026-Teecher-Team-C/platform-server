"""정상 파일 묶음(B)을 패키지 하나씩 처리한다.

풀어낸 파일 전체를 디스크에 쌓아 두지 않으려고(설치 파일 30개를 다 풀면 수 GB), 패키지마다
풀기 → 지문 계산 → 변종 원본 후보 몇 개만 복사 → 풀어낸 폴더 삭제 순서로 간다(삭제는 fetch_benign.sh).

    python -m quality.benign NAME INSTALLER EXTRACTED_DIR ORIGINS_DIR ITEMS_CSV
"""
from __future__ import annotations

import shutil
import sys
from collections import Counter
from pathlib import Path

from quality.records import Item, read_items, write_items
from quality.sets import file_record, pick_variant_origins, scan_benign


def process_package(name: str, installer: Path, extracted: Path, origins: Path, per_package: int = 3,
                    seed: int = 0) -> tuple[list[Item], Counter]:
    """설치 파일과 풀어낸 파일의 지문을 계산하고, 변종 원본 후보 per_package개를 origins/NAME/에 복사한다.

    family 열은 'NAME/상대 경로'다. 풀기에 실패했으면(extracted 없음) 설치 파일만 센다.
    """
    if not extracted.is_dir():
        item = file_record(installer, installer.read_bytes(), "B", family=f"{name}/{installer.name}")
        return ([item], Counter(selected=1)) if item else ([], Counter(tnull=1))
    staging = extracted / ".installer"
    staging.mkdir(exist_ok=True)
    shutil.copy2(installer, staging / installer.name)
    root = extracted
    found, counts = scan_benign(root, blacklist=set())
    picked = pick_variant_origins(found, root, installer_names={installer.name}, count=per_package, seed=seed)
    if picked:
        (origins / name).mkdir(parents=True, exist_ok=True)
        for path in picked:
            shutil.copy2(path, origins / name / path.name)
    items = []
    for item in found:
        family = installer.name if Path(item.family).name == installer.name else item.family
        items.append(Item(**{**item.__dict__, "family": f"{name}/{family}"}))
    return items, counts


def main(argv: list[str]) -> None:
    name, installer, extracted, origins, items_csv = argv
    items, counts = process_package(name, Path(installer), Path(extracted), Path(origins))
    path = Path(items_csv)
    existing = read_items(path) if path.exists() else []
    seen = {i.sha256 for i in existing}
    write_items(path, existing + [i for i in items if i.sha256 not in seen])
    print(f"{name}: {dict(counts)}")


if __name__ == "__main__":
    main(sys.argv[1:])
