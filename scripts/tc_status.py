"""
테스트 명세(docs/test-cases)와 자동화 테스트, 실제 실행 결과를 TC ID로 대조한다. (HomeFin의 스크립트에서 백엔드 부분만 남김)

- 명세: docs/test-cases/*.md 표의 행 "| TC-XXX-000 | ... |"
- 테스트 코드: @DisplayName("[TC-...") 다음 메서드
- 실행 결과: JUnit XML target/surefire-reports/TEST-*.xml (테스트를 먼저 실행해야 생김)

결과 XML이 없으면 "미실행"으로 표시한다. 실행하지 않은 테스트를 통과로 세지 않기 위해서다.

사용: python scripts/tc_status.py [--strict] [--markdown]
  --strict   명세·코드 불일치나 실패·미실행이 있으면 종료 코드 1 (CI용)
  --markdown 결과를 Markdown 표로 출력 (TEST_REPORT에 붙일 때)
표준 라이브러리만 사용한다.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ID_RE = re.compile(r"TC-[A-Z]+-\d{3}")
NOT_AUTOMATED = "자동화 안 함"
PLANNED = "작성 예정"


def path(*parts):
    return os.path.join(ROOT, *parts)


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


# 명세: {id: (파일, "자동화 안 함" | "작성 예정" | None)}
def load_specs():
    specs = {}
    for md in sorted(glob.glob(path("docs", "test-cases", "*.md"))):
        for line in read(md).splitlines():
            m = re.match(r"\|\s*(TC-[A-Z]+-\d{3})\s*\|", line)
            if m:
                mark = NOT_AUTOMATED if NOT_AUTOMATED in line else PLANNED if PLANNED in line else None
                specs[m.group(1)] = (os.path.basename(md), mark)
    return specs


# 테스트 코드: {id: [(모듈, 결과 XML에서 찾을 키, 위치)]}
def load_tests():
    tests = {}

    def add(tc_id, module, key, where):
        tests.setdefault(tc_id, []).append((module, key, where))

    for java in glob.glob(path("src", "test", "java", "**", "*.java"), recursive=True):
        lines = read(java).splitlines()
        cls = os.path.splitext(os.path.basename(java))[0]
        for i, line in enumerate(lines):
            m = re.search(r'@DisplayName\("\[(TC-[A-Z]+-\d{3})\]', line)
            if not m:
                continue
            for nxt in lines[i + 1:i + 6]:
                mm = re.search(r"void\s+(\w+)\s*\(", nxt)
                if mm:
                    add(m.group(1), "backend", (cls, mm.group(1)), f"{cls}.{mm.group(1)}")
                    break
    return tests


# 실행 결과: 모듈별 [(classname, name, 성공 여부)], 결과 파일이 없으면 None
def load_results():
    def parse(files):
        if not files:
            return None
        cases = []
        for f in files:
            for tc in ET.parse(f).getroot().iter("testcase"):
                ok = not any(child.tag in ("failure", "error", "skipped") for child in tc)
                cases.append((tc.get("classname", ""), tc.get("name", ""), ok))
        return cases

    return {"backend": parse(glob.glob(path("target", "surefire-reports", "TEST-*.xml")))}


def status_of(module, key, results):
    cases = results[module]
    if cases is None:
        return "미실행"
    cls, method = key
    hits = [ok for c, n, ok in cases
            if c.endswith("." + cls) and (n == method or n.startswith(method + "(") or n.startswith(method + "["))]
    if not hits:
        return "미실행"
    return "통과" if all(hits) else "실패"


def main():
    # Windows 콘솔(cp949)에서도 한글이 깨지지 않게 UTF-8로 출력
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    strict = "--strict" in sys.argv
    markdown = "--markdown" in sys.argv
    specs, tests, results = load_specs(), load_tests(), load_results()

    rows, problems = [], []
    for tc_id in sorted(set(specs) | set(tests)):
        spec = specs.get(tc_id)
        locs = tests.get(tc_id, [])
        if spec and spec[1]:
            state = spec[1] if not locs else f"확인 필요: {spec[1]}인데 테스트 있음"
        elif not spec:
            state = "확인 필요: 명세 없음"
        elif not locs:
            state = "확인 필요: 테스트 없음"
        else:
            states = {status_of(m, k, results) for m, k, _ in locs}
            state = "실패" if "실패" in states else "미실행" if "미실행" in states else "통과"
        if state.startswith("확인 필요") or state in ("실패", "미실행"):
            problems.append(tc_id)
        rows.append((tc_id, spec[0] if spec else "-", ", ".join(w for _, _, w in locs) or "-", state))

    if markdown:
        print("| ID | 명세 | 자동화 테스트 | 결과 |\n| --- | --- | --- | --- |")
        for r in rows:
            print("| " + " | ".join(r) + " |")
    else:
        for r in rows:
            print(f"{r[0]:15} {r[3]:8} {r[1]:22} {r[2]}")

    counts = {}
    for r in rows:
        key = r[3] if not r[3].startswith("확인 필요") else "확인 필요"
        counts[key] = counts.get(key, 0) + 1
    print("\n합계: " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())))
    for module, cases in results.items():
        print(f"  {module}: " + ("결과 XML 없음 (미실행)" if cases is None else f"결과 XML의 테스트 {len(cases)}건"))
    return 1 if strict and problems else 0


if __name__ == "__main__":
    sys.exit(main())
