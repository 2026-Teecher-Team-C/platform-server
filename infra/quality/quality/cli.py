"""탐지 품질 측정 하네스 명령. 순서는 infra/quality/README.md(런북)를 따른다.

관리자 계정은 QUALITY_ADMIN_USER / QUALITY_ADMIN_PASSWORD 환경 변수로만 받는다(인증을 끈 로컬 스택이면 생략).
"""
from __future__ import annotations

import argparse
import asyncio
import csv
import io
import os
import sys
import zipfile
from dataclasses import fields
from datetime import datetime, timezone
from pathlib import Path

from quality.analyze import join, missing_evidence, predict_mismatches, propose, report
from quality.client import Admin, check_target, register_agent, run_checks
from quality.mutate import build_variants
from quality.records import Response, read_evidence, read_items, read_responses, write_items, write_responses
from quality.sets import pick_variant_origins, scan_benign, select_malicious


def _lines(path: Path):
    if path.suffix == ".zip":
        with zipfile.ZipFile(path) as archive:
            name = next(n for n in archive.namelist() if n.endswith(".csv"))
            with archive.open(name) as raw:
                yield from io.TextIOWrapper(raw, encoding="utf-8", errors="replace")
    else:
        with open(path, encoding="utf-8", errors="replace") as src:
            yield from src


def _admin(http: str) -> Admin:
    admin = Admin(http)
    user, password = os.environ.get("QUALITY_ADMIN_USER"), os.environ.get("QUALITY_ADMIN_PASSWORD")
    if user and password:
        admin.login(user, password)
    return admin


def _read_all_items(paths) -> list:
    return [item for p in paths for item in read_items(Path(p))]


def cmd_build_malicious(args) -> None:
    blacklist = set(Path(args.blacklist_shas).read_text().split())
    since = datetime.fromisoformat(args.since).replace(tzinfo=timezone.utc)
    items, counts = select_malicious(_lines(Path(args.csv)), blacklist, since)
    write_items(Path(args.out), items)
    print(f"신종 악성: {dict(counts)} (블랙리스트 {len(blacklist)}건과 대조)")


def cmd_build_variants(args) -> None:
    root = Path(args.origins)
    blacklist = set(Path(args.blacklist_shas).read_text().split())
    benign = read_items(Path(args.benign_items))
    # fetch_benign.sh가 설치 파일 자체를 'NAME/NAME-VERSION.EXT'로 적는다.
    installer_tlsh = [i.tlsh for i in benign if i.family.count("/") == 1
                      and Path(i.family).name.startswith(i.family.split("/")[0] + "-")]
    candidates, _ = scan_benign(root, blacklist=set())
    originals = pick_variant_origins(candidates, root, installer_names=set(), count=args.count, seed=args.seed,
                                     exclude_shas=blacklist, installer_tlsh=installer_tlsh)
    items, skipped = build_variants(originals, seed=args.seed)
    write_items(Path(args.out), items)
    print(f"변종: 후보 {len(candidates)}개 중 원본 {len(originals)}개(설치 파일 지문 {len(installer_tlsh)}개와 대조), "
          f"항목 {len(items)}개, 건너뜀 {dict(skipped)}")


def cmd_run(args) -> None:
    check_target(args.http, args.grpc, args.i_know_this_is_prod)
    out = Path(args.out_dir)
    agent_file, responses_file = out / f"agent-{args.pass_id}.txt", out / f"responses-{args.pass_id}.csv"
    if agent_file.exists() or responses_file.exists():
        print(f"pass-id {args.pass_id}의 결과가 이미 있습니다. 다른 pass-id를 쓰세요(이전 측정 에이전트를 정리할 수 있게 "
              f"{agent_file.name}을 덮어쓰지 않습니다).", file=sys.stderr)
        raise SystemExit(2)
    items = _read_all_items(args.items)
    admin = _admin(args.http)
    agent_id, token = register_agent(args.grpc, admin.enrollment_token(), tls=args.tls)
    agent_file.write_text(agent_id + "\n")
    print(f"측정 에이전트 {agent_id}, 항목 {len(items)}개, 동시 {args.concurrency}")
    write_responses(responses_file, [])
    with open(responses_file, "a", newline="") as sink:
        writer = csv.writer(sink)

        def append(r: Response) -> None:
            writer.writerow([getattr(r, f.name) for f in fields(Response)])
            sink.flush()

        responses = asyncio.run(run_checks(args.grpc, args.tls, token, items, args.pass_id, args.concurrency,
                                           on_result=append, report_blocked=not args.no_report))
    errors = sum(1 for r in responses if r.error)
    reported = sum(1 for r in responses if r.source == "DECISION_SOURCE_SIMILARITY" and "report-failed" not in r.reason)
    failed = sum(1 for r in responses if "report-failed" in r.reason)
    print(f"완료: 응답 {len(responses)}개, 오류 {errors}개, 다운로드 이벤트 {0 if args.no_report else reported}개"
          f"(보고 실패 {failed}개) → {responses_file.name}")


def cmd_policy(args) -> None:
    admin = _admin(args.http)
    if args.action == "set":
        check_target(args.http, args.http, args.i_know_this_is_prod)
        admin.put_policy(args.block, args.warn)
    block, warn = admin.get_policy()
    print(f"정책: 차단 ≤{block}, 경고 ≤{warn}")


def cmd_variants(args) -> None:
    check_target(args.http, args.http, args.i_know_this_is_prod)
    registered = Path(args.out_dir) / f"registered-{args.run_id}.txt"
    admin = _admin(args.http)
    if args.action == "register":
        if registered.exists():
            print(f"{registered.name}이 이미 있습니다. 먼저 release 하세요.", file=sys.stderr)
            raise SystemExit(1)
        origins = [i for i in read_items(Path(args.items)) if i.variant == "origin"]
        result = admin.import_blacklist(origins, label=f"quality-test-{args.run_id}")
        print(f"변종 원본 등록: {len(origins)}개 요청, 결과 {result}")
        if result.get("added") != len(origins):
            # 이미 있던 항목(skippedActive 등)을 나중에 해제하면 측정 전부터 있던 운영 등록을 지우게 된다.
            print("일부가 새로 추가되지 않았습니다. 해제 목록을 만들지 않습니다. 추가된 항목은 사유 "
                  f"quality-test-{args.run_id}로 찾아 직접 확인하세요.", file=sys.stderr)
            raise SystemExit(1)
        registered.write_text("".join(i.sha256 + "\n" for i in origins))
        return
    if not registered.exists():
        print(f"{registered.name}이 없습니다. 등록에 성공한 목록만 해제합니다.", file=sys.stderr)
        raise SystemExit(1)
    shas = registered.read_text().split()
    released, failed = admin.release_blacklist(shas)
    print(f"변종 원본 해제: {released}/{len(shas)}")
    if failed:
        print("해제 실패: " + " ".join(failed), file=sys.stderr)
        raise SystemExit(1)
    registered.rename(registered.with_suffix(".released"))


def _rows(args):
    return join(_read_all_items(args.items), read_responses(Path(args.responses)), read_evidence(Path(args.evidence)))


def _require_evidence(rows) -> None:
    missing = missing_evidence(rows)
    if missing:
        print(f"근거 행이 없는 요청이 {missing}건입니다. 응답과 근거가 같은 회차·같은 에이전트 것인지 확인하세요.",
              file=sys.stderr)
        raise SystemExit(1)


def cmd_analyze(args) -> None:
    rows = _rows(args)
    _require_evidence(rows)
    meta = dict(kv.split("=", 1) for kv in args.meta)
    proposal = propose(rows)
    Path(args.out).write_text(report(rows, proposal, meta))
    print(f"제안: 차단 ≤{proposal.block}, 경고 ≤{proposal.warn} → {args.out}")


def cmd_predict(args) -> None:
    items = _read_all_items(args.items)
    base = join(items, read_responses(Path(args.base_responses)), read_evidence(Path(args.base_evidence)))
    second = join(items, read_responses(Path(args.responses)), read_evidence(Path(args.evidence)))
    _require_evidence(base)
    _require_evidence(second)
    mismatches = predict_mismatches(base, second, args.block, args.warn)
    print(f"예측 불일치 {len(mismatches)}건")
    for item_id in mismatches[:50]:
        print("  " + item_id)
    if mismatches:
        raise SystemExit(1)


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="quality")
    sub = p.add_subparsers(dest="command", required=True)

    def target(s):
        s.add_argument("--http", required=True)
        s.add_argument("--i-know-this-is-prod", action="store_true")

    s = sub.add_parser("build-malicious")
    s.add_argument("--csv", required=True)
    s.add_argument("--blacklist-shas", required=True)
    s.add_argument("--since", default="2026-11-17")
    s.add_argument("--out", default="out/items-M.csv")
    s.set_defaults(func=cmd_build_malicious)

    s = sub.add_parser("build-variants")
    s.add_argument("--origins", default="out/benign/origins")
    s.add_argument("--blacklist-shas", required=True)
    s.add_argument("--benign-items", default="out/items-B.csv")
    s.add_argument("--count", type=int, default=50)
    s.add_argument("--seed", type=int, default=1)
    s.add_argument("--out", default="out/items-V.csv")
    s.set_defaults(func=cmd_build_variants)

    s = sub.add_parser("run")
    target(s)
    s.add_argument("--grpc", required=True)
    s.add_argument("--tls", action="store_true")
    s.add_argument("--items", nargs="+", required=True)
    s.add_argument("--pass-id", required=True)
    s.add_argument("--concurrency", type=int, default=4)
    s.add_argument("--no-report", action="store_true", help="TLSH 차단 건을 다운로드 이벤트로 남기지 않는다")
    s.add_argument("--out-dir", default="out")
    s.set_defaults(func=cmd_run)

    s = sub.add_parser("policy")
    target(s)
    s.add_argument("action", choices=["get", "set"])
    s.add_argument("--block", type=int)
    s.add_argument("--warn", type=int)
    s.set_defaults(func=cmd_policy)

    s = sub.add_parser("variants")
    target(s)
    s.add_argument("action", choices=["register", "release"])
    s.add_argument("--items", default="out/items-V.csv")
    s.add_argument("--run-id", required=True)
    s.add_argument("--out-dir", default="out")
    s.set_defaults(func=cmd_variants)

    s = sub.add_parser("analyze")
    s.add_argument("--items", nargs="+", required=True)
    s.add_argument("--responses", required=True)
    s.add_argument("--evidence", required=True)
    s.add_argument("--out", default="out/report.md")
    s.add_argument("--meta", nargs="*", default=[])
    s.set_defaults(func=cmd_analyze)

    s = sub.add_parser("predict", help="1회차 거리로 예측한 판정과 2회차 실제 판정 대조")
    s.add_argument("--items", nargs="+", required=True)
    s.add_argument("--base-responses", required=True)
    s.add_argument("--base-evidence", required=True)
    s.add_argument("--responses", required=True)
    s.add_argument("--evidence", required=True, help="2회차 근거(경고 판정 확인용)")
    s.add_argument("--block", type=int, required=True)
    s.add_argument("--warn", type=int, required=True)
    s.set_defaults(func=cmd_predict)
    return p


def main(argv=None) -> None:
    args = parser().parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    main()
