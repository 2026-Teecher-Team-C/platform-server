#!/usr/bin/env bash
# 정상 묶음(B) YARA 스캔: 설치 파일마다 풀어서(fetch_benign.sh와 같은 방식) 풀어낸 파일 전체를 스캔하고 지운다.
#   bash scan_benign_yara.sh OUT_DIR RULES DISABLED
# 입력: OUT_DIR/installers/*, OUT_DIR/items-B.csv. 출력: OUT_DIR/yara-B.csv (패키지마다 덧붙이고 같은 item_id는 다시 쓰지 않는다)
# 이 스크립트는 처음부터 다시 만든다: 기존 yara-B.csv는 지운다. out/은 커밋하지 않는다.
set -uo pipefail
OUT=${1:?OUT_DIR}; RULES=${2:?RULES}; DISABLED=${3:?DISABLED}
mkdir -p "$OUT/tmp"
rm -f "$OUT/yara-B.csv"
for file in "$OUT"/installers/*; do
    [ -f "$file" ] || continue
    name=$(basename "$file")
    work="$OUT/tmp/$name"; rm -rf "$work"; mkdir -p "$work/.installer"
    7z x -y -bso0 -bsp0 -o"$work" "$file" >/dev/null 2>&1 || echo "WARN extract $name (설치 파일만 스캔)"
    cp "$file" "$work/.installer/"
    python -m quality.cli yara-scan --rules "$RULES" --disabled "$DISABLED" --items "$OUT/items-B.csv" \
        --files "$work" --out "$OUT/yara-B.csv" || echo "FAIL scan $name"
    rm -rf "$work"
done
rmdir "$OUT/tmp" 2>/dev/null
items_b=$(($(wc -l < "$OUT/items-B.csv") - 1))
yara_b=$(($(wc -l < "$OUT/yara-B.csv") - 1))
echo "items-B: $items_b, yara-B rows: $yara_b, 차이(결과 없음): $((items_b - yara_b))"
