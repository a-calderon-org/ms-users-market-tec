"""MTEC-37: `ms-users-aca.sh app --sha` on an existing app merges the `sha` tag, never replaces other tags."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/azure/aca/ms-users-aca.sh"
SHA = "f" * 40
APP_ID = "/subscriptions/s/resourceGroups/rg/providers/Microsoft.App/containerApps/ca-ms-users-stg"

# Records every az call (one per line) and answers the id lookups the app step makes.
AZ_STUB = f"""#!/bin/sh
echo "$*" >> "$AZ_LOG"
case "$*" in
  "containerapp show"*"--query id"*) echo "{APP_ID}" ;;
esac
exit 0
"""


class AcaAppTagsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        for name, body in {"az": AZ_STUB, "gh": "#!/bin/sh\necho 1\n"}.items():
            stub = self.bin / name
            stub.write_text(body)
            stub.chmod(0o700)
        self.log = self.tmp / "az.log"

    def run_app(self):
        env = {"PATH": f"{self.bin}{os.pathsep}{os.environ['PATH']}", "HOME": str(self.tmp),
               "AZ_LOG": str(self.log)}
        result = subprocess.run(["bash", str(SCRIPT), "app", "--sha", SHA], capture_output=True, text=True,
                                env=env, timeout=60)
        self.assertEqual(0, result.returncode, result.stderr)
        return self.log.read_text().splitlines()

    def test_update_does_not_replace_tags(self):
        calls = self.run_app()
        updates = [c for c in calls if c.startswith("containerapp update")]
        self.assertEqual(1, len(updates), calls)
        self.assertNotIn("--tags", updates[0])

    def test_sha_tag_is_merged_incrementally_into_existing_tags(self):
        calls = self.run_app()
        tags = [c for c in calls if c.startswith("resource tag")]
        self.assertEqual([f"resource tag --ids {APP_ID} --is-incremental --tags sha={SHA} -o none"], tags)
        self.assertLess(calls.index(next(c for c in calls if c.startswith("containerapp update"))),
                        calls.index(tags[0]))


if __name__ == "__main__":
    unittest.main()
