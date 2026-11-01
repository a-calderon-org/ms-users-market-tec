#!/usr/bin/env python3
"""Require executed, passing testcases, not merely compiled sources or suite discovery."""
import argparse
from collections import Counter
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NON_PASS_ELEMENTS = frozenset({
    "failure", "error", "skipped", "flakyFailure", "flakyError", "rerunFailure", "rerunError"
})


def local_name(element):
    """ElementTree expands both default and prefixed XML namespaces to {uri}name."""
    return element.tag.rsplit("}", 1)[-1]


def reject_non_pass_results(root, source, summary=False):
    for element in root.iter():
        if local_name(element) in NON_PASS_ELEMENTS and not (summary and local_name(element) == "skipped"):
            raise ValueError(f"{source}: non-passing/retried result <{local_name(element)}> is forbidden")


def verify_summary(path, expected_count):
    summary = ET.parse(path).getroot()
    if local_name(summary) != "failsafe-summary":
        raise ValueError("Invalid Failsafe summary root")
    reject_non_pass_results(summary, path.name, summary=True)
    # A fork/startup failure or timeout must not be hidden by zero testcase counters.
    if summary.get("timeout", "false").lower() != "false":
        raise ValueError("Failsafe summary reports a timeout")
    if summary.get("result") not in (None, "", "0", "null"):
        raise ValueError("Failsafe summary reports an unsuccessful result")
    fields = {}
    for element in summary:
        name = local_name(element)
        if name in fields:
            raise ValueError(f"Duplicate Failsafe summary field: {name}")
        fields[name] = element.text
    if int(fields.get("completed") or "-1") != expected_count:
        raise ValueError("Failsafe summary does not match executed SQL cases")
    for name in ("errors", "failures", "skipped"):
        if int(fields.get(name) or "-1") != 0:
            raise ValueError(f"Failsafe summary: {name} must be zero")
    if int(fields.get("flakes") or "0") != 0:
        raise ValueError("Failsafe summary: flakes must be zero")
    if (fields.get("failureMessage") or "").strip():
        raise ValueError("Failsafe summary contains a failure message")


def verify(directory, expected_count):
    reports = sorted((ROOT / "target" / directory).glob("TEST-*.xml"))
    if not reports:
        raise ValueError(f"No XML test reports in {directory}")
    cases = []
    for path in reports:
        root = ET.parse(path).getroot()
        if local_name(root) not in ("testsuite", "testsuites"):
            raise ValueError(f"Unsupported XML report root: {path.name}")
        reject_non_pass_results(root, path.name)
        suites = [element for element in root.iter() if local_name(element) == "testsuite"]
        if not suites:
            raise ValueError(f"No testsuites in {path.name}")
        for suite in suites:
            actual = [element for element in suite.iter() if local_name(element) == "testcase"]
            if int(suite.get("tests", "-1")) != len(actual):
                raise ValueError(f"Suite/testcase count mismatch: {path.name}")
            for attribute in ("failures", "errors", "skipped"):
                if int(suite.get(attribute, "-1")) != 0:
                    raise ValueError(f"{path.name}: {attribute} must be zero")
        if local_name(root) == "testsuites":
            for attribute in ("tests", "failures", "errors", "skipped"):
                if attribute in root.attrib:
                    expected = len([e for e in root.iter() if local_name(e) == "testcase"]) if attribute == "tests" else 0
                    if int(root.get(attribute)) != expected:
                        raise ValueError(f"Aggregate {attribute} mismatch: {path.name}")
        actual = [element for element in root.iter() if local_name(element) == "testcase"]
        for case in actual:
            if not case.get("classname", "").strip() or not case.get("name", "").strip():
                raise ValueError(f"Missing testcase identity: {path.name}")
            cases.append((case.get("classname"), case.get("name")))
    if len(set(cases)) != len(cases):
        raise ValueError(f"Duplicate testcase identities in {directory}")
    if len(cases) != expected_count:
        raise ValueError(f"{directory}: expected {expected_count} executed cases, got {len(cases)}")
    return cases


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--normal-only", action="store_true")
    args = parser.parse_args()
    inventory = json.loads((ROOT / "scripts/mtec35-test-inventory.json").read_text())
    normal = verify("surefire-reports", inventory["normal_count"])
    required = Counter((class_name, method)
                       for class_name, methods in inventory["required_normal_methods"].items()
                       for method in methods)
    if required - Counter(normal):
        raise ValueError(f"Mandatory normal tests missing: {list((required-Counter(normal)).elements())}")
    result = {"normal": {"executed": len(normal), "failures": 0, "errors": 0, "skipped": 0}}
    if not args.normal_only:
        sql = verify("failsafe-reports", len(inventory["sql_methods"]))
        expected = Counter((inventory["sql_class"], method) for method in inventory["sql_methods"])
        if Counter(sql) != expected:
            raise ValueError(f"SQL method inventory mismatch: missing={list((expected-Counter(sql)).elements())}; unexpected={list((Counter(sql)-expected).elements())}")
        verify_summary(ROOT / "target/failsafe-reports/failsafe-summary.xml", len(sql))
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
