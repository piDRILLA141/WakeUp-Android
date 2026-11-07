#!/usr/bin/env python3
"""Package actual Gradle reports for downloading from GitHub Actions."""
import argparse
from datetime import datetime, timedelta, timezone
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("output", type=Path)
parser.add_argument("--build-outcome", required=True)
parser.add_argument("--build-log", type=Path, required=True)
args = parser.parse_args()
output = args.output
output.mkdir(parents=True, exist_ok=True)
for source, target in (
    (Path("core/build/reports"), "core-reports"),
    (Path("app/build/reports"), "app-reports"),
    (Path("core/build/test-results"), "core-test-results"),
    (Path("app/build/test-results"), "app-test-results"),
):
    if source.exists():
        shutil.copytree(source, output / target, dirs_exist_ok=True)
if args.build_log.exists():
    shutil.copy2(args.build_log, output / "build.log")

rows = []
for label, folder in (
    ("Kotlin / JUnit", Path("core/build/test-results/test")),
    ("Android / Robolectric / Compose", Path("app/build/test-results/testDebugUnitTest")),
):
    suites = [ET.parse(path).getroot() for path in sorted(folder.glob("TEST-*.xml"))]
    counts = [sum(int(suite.get(key, 0)) for suite in suites)
              for key in ("tests", "failures", "errors", "skipped")]
    rows.append(f"| {label} | " + " | ".join(map(str, counts)) + (" |\n" if suites else " (отчёты отсутствуют) |\n"))

lint = Path("app/build/reports/lint-results-debug.xml")
if lint.exists():
    issues = ET.parse(lint).getroot().findall("issue")
    errors = sum(issue.get("severity") in ("Error", "Fatal") for issue in issues)
    warnings = sum(issue.get("severity") == "Warning" for issue in issues)
    lint_summary = f"Android Lint: ошибок {errors}, предупреждений {warnings}."
else:
    lint_summary = "Отчёт Android Lint отсутствует; успешная проверка не подтверждена."
commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
when = datetime.now(timezone(timedelta(hours=3))).isoformat(timespec="seconds")
summary = f"""# Результаты сборки и проверок «Подъём»

Дата: {when}. Коммит: `{commit}`.
Результат шага сборки GitHub Actions: **{args.build_outcome}**.

| Набор | Тестов | Неудачных | Ошибок | Пропущено |
|---|---:|---:|---:|---:|
""" + "".join(rows) + f"""
{lint_summary}

- [Полный журнал сборки](build.log).
- [HTML тестов ядра](core-reports/tests/test/index.html).
- [HTML Android/Compose-тестов](app-reports/tests/testDebugUnitTest/index.html).
- [HTML Android Lint](app-reports/lint-results-debug.html).
- XML JUnit находится в `core-test-results` и `app-test-results`.

Распакуйте ZIP целиком перед открытием HTML: стили и ресурсы находятся рядом.
Отчёты созданы этим запуском workflow. Инструментальный тестовый APK собирается,
но `connectedDebugAndroidTest` на физическом телефоне здесь не запускается.
Звук, блокировка экрана, Doze, Bluetooth и OriginOS на iQOO 15R требуют проверки
на самом устройстве. Автоматические тесты не подтверждают эти сценарии.
"""
(output / "RESULTS.md").write_text(summary, encoding="utf-8")
print(summary)
