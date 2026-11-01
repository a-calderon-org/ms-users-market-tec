#!/usr/bin/env python3
"""Require executed, passing testcases, not merely compiled sources or suite discovery."""
import argparse
from collections import Counter
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def verify(directory, expected_count):
    reports = sorted((ROOT / "target" / directory).glob("TEST-*.xml"))
    if not reports:
        raise ValueError(f"No XML test reports in {directory}")
    cases = []
    for path in reports:
        suite = ET.parse(path).getroot()
        actual = suite.findall("testcase")
        if int(suite.get("tests", "-1")) != len(actual):
            raise ValueError(f"Suite/testcase count mismatch: {path.name}")
        for attribute in ("failures", "errors", "skipped"):
            if int(suite.get(attribute, "-1")) != 0:
                raise ValueError(f"{path.name}: {attribute} must be zero")
        for case in actual:
            if any(case.find(tag) is not None for tag in ("failure", "error", "skipped", "flakyFailure", "rerunFailure", "rerunError")):
                raise ValueError(f"Case did not pass on its first execution: {case.get('name')}")
            cases.append((case.get("classname"), case.get("name")))
    if len(cases) != expected_count:
        raise ValueError(f"{directory}: expected {expected_count} executed cases, got {len(cases)}")
    return cases


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--normal-only", action="store_true")
    args = parser.parse_args()
    inventory = json.loads((ROOT / "scripts/mtec35-test-inventory.json").read_text())
    normal = verify("surefire-reports", inventory["normal_count"])
    result = {"normal": {"executed": len(normal), "failures": 0, "errors": 0, "skipped": 0}}
    if not args.normal_only:
        sql = verify("failsafe-reports", len(inventory["sql_methods"]))
        expected = Counter((inventory["sql_class"], method) for method in inventory["sql_methods"])
        if Counter(sql) != expected:
            raise ValueError(f"SQL method inventory mismatch: missing={list((expected-Counter(sql)).elements())}; unexpected={list((Counter(sql)-expected).elements())}")
        summary = ET.parse(ROOT / "target/failsafe-reports/failsafe-summary.xml").getroot()
        if int(summary.findtext("completed", "-1")) != len(sql):
            raise ValueError("Failsafe summary does not match executed SQL cases")
        for attribute in ("errors", "failures", "skipped"):
            if int(summary.findtext(attribute, "-1")) != 0:
                raise ValueError(f"Failsafe summary: {attribute} must be zero")
        result["sql"] = {"executed": len(sql), "failures": 0, "errors": 0, "skipped": 0,
                         "methods": sorted(method for _, method in sql)}
    output = ROOT / "target" / ("mtec35-normal-results.json" if args.normal_only else "mtec35-qa-results.json")
    output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, ET.ParseError) as failure:
        raise SystemExit(f"MTEC35 STRICT QA FAILED: {failure}")
