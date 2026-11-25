#!/usr/bin/env bash
# 정상 파일 묶음(B) 만들기: 공식 배포처 설치 파일 받기 → sha256 대조 → 패키지마다 풀어서 지문 계산 → 풀어낸 파일 삭제.
#   bash fetch_benign.sh OUT_DIR            매니페스트의 sha256과 다르면 그 패키지는 실패로 표시하고 건너뛴다
#   bash fetch_benign.sh OUT_DIR --record   sha256이 빈 줄은 처음 받은 값을 매니페스트에 적는다(처음 한 번만)
# 결과: OUT_DIR/items-B.csv(지문), OUT_DIR/origins/<패키지>/(변종 원본 후보), OUT_DIR/installers/(설치 파일)
# 풀어낸 파일은 패키지 하나를 처리한 뒤 바로 지운다(전부 풀면 수 GB라 디스크가 찬다). out/은 커밋하지 않는다.
set -uo pipefail
OUT=${1:?OUT_DIR}; RECORD=${2:-}
HERE=$(cd "$(dirname "$0")" && pwd); MANIFEST="$HERE/benign-manifest.csv"
mkdir -p "$OUT/installers" "$OUT/origins"
rm -f "$OUT/items-B.csv"
new_manifest=$(mktemp); head -1 "$MANIFEST" > "$new_manifest"
while IFS=, read -r name version url expected; do
    [ -z "$name" ] && continue
    ext=$(basename "${url%%\?*}" | sed -E 's/.*\.([A-Za-z0-9]+)$/\1/; t; s/.*/bin/')
    file="$OUT/installers/$name-$version.$ext"
    if [ ! -s "$file" ] && ! curl -fsSL --retry 2 -o "$file" "$url"; then
        echo "FAIL download $name"; rm -f "$file"; echo "$name,$version,$url,$expected" >> "$new_manifest"; continue
    fi
    actual=$(sha256sum "$file" | cut -d' ' -f1)
    [ -z "$expected" ] && [ "$RECORD" = "--record" ] && expected=$actual
    echo "$name,$version,$url,$expected" >> "$new_manifest"
    if [ "$actual" != "$expected" ]; then echo "FAIL sha256 $name"; continue; fi
    work="$OUT/tmp/$name"; rm -rf "$work"; mkdir -p "$work"
    7z x -y -bso0 -bsp0 -o"$work" "$file" >/dev/null 2>&1 || { echo "WARN extract $name (설치 파일만 씀)"; rm -rf "$work"; }
    python -m quality.benign "$name" "$file" "$work" "$OUT/origins" "$OUT/items-B.csv" || echo "FAIL scan $name"
    rm -rf "$work"
done < <(tail -n +2 "$MANIFEST")
rmdir "$OUT/tmp" 2>/dev/null
if [ "$RECORD" = "--record" ]; then mv "$new_manifest" "$MANIFEST"; else rm -f "$new_manifest"; fi
echo "items: $(($(wc -l < "$OUT/items-B.csv") - 1)), origins: $(find "$OUT/origins" -type f | wc -l)"
