#!/usr/bin/env bash
# 정상 파일 묶음(B) 받기: 공식 배포처 설치 파일 → sha256 대조 → 7z로 풀기.
#   bash fetch_benign.sh OUT_DIR            매니페스트의 sha256과 다르면 그 파일은 실패로 표시하고 건너뛴다
#   bash fetch_benign.sh OUT_DIR --record   sha256이 빈 줄은 처음 받은 값을 매니페스트에 적는다(처음 한 번만)
# 받은 파일과 풀어낸 파일은 커밋하지 않는다(out/은 .gitignore).
set -uo pipefail
OUT=${1:?OUT_DIR}; RECORD=${2:-}
HERE=$(cd "$(dirname "$0")" && pwd); MANIFEST="$HERE/benign-manifest.csv"
mkdir -p "$OUT/installers" "$OUT/extracted"
fail=0; tmp=$(mktemp)
head -1 "$MANIFEST" > "$tmp"
tail -n +2 "$MANIFEST" | while IFS=, read -r name version url expected; do
    [ -z "$name" ] && continue
    file="$OUT/installers/$name-$version.$(basename "${url%%\?*}" | sed -E 's/.*\.([A-Za-z0-9]+)$/\1/; t; s/.*/bin/')"
    if [ ! -s "$file" ] && ! curl -fsSL --retry 2 -o "$file" "$url"; then
        echo "FAIL download $name"; rm -f "$file"; echo "$name,$version,$url,$expected" >> "$tmp"; continue
    fi
    actual=$(sha256sum "$file" | cut -d' ' -f1)
    if [ -z "$expected" ] && [ "$RECORD" = "--record" ]; then expected=$actual; fi
    echo "$name,$version,$url,$expected" >> "$tmp"
    if [ "$actual" != "$expected" ]; then echo "FAIL sha256 $name"; rm -f "$file"; continue; fi
    mkdir -p "$OUT/extracted/$name"
    7z x -y -bso0 -bsp0 -o"$OUT/extracted/$name" "$file" >/dev/null 2>&1 || echo "WARN extract $name (설치 파일만 씀)"
    echo "ok $name"
done
[ "$RECORD" = "--record" ] && cp "$tmp" "$MANIFEST"
rm -f "$tmp"
echo "files: $(find "$OUT" -type f | wc -l)"
