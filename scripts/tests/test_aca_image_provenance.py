"""MTEC-37: `ms-users-aca.sh image --sha` builds only from the CI-approved commit, never the local tree."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/azure/aca/ms-users-aca.sh"
COMMITTED_DOCKERFILE = "FROM scratch\nLABEL source=approved-commit\n"
COMMITTED_DOCKERIGNORE = "target/\n"


def git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True, text=True,
                          env={**os.environ, "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@example.invalid",
                               "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@example.invalid"}).stdout.strip()


class AcaImageProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.repo = self.tmp / "repo"
        script = self.repo / "scripts/azure/aca/ms-users-aca.sh"
        script.parent.mkdir(parents=True)
        shutil.copy(SCRIPT, script)
        git(self.repo, "init", "-q")
        self.script = script
        self.captured = self.tmp / "captured"
        self.captured.mkdir()
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        stubs = {
            "az": "exit 0",
            "gh": "echo 1",
            # Record the build context exactly as docker would receive it (last argument).
            "docker": 'for a; do ctx="$a"; done; cp -R "$ctx/." "$CAPTURE/"; touch "$CAPTURE/.built"',
        }
        for name, body in stubs.items():
            stub = self.bin / name
            stub.write_text(f"#!/bin/sh\n{body}\n")
            stub.chmod(0o700)

    def commit(self, files):
        for name, content in files.items():
            (self.repo / name).write_text(content)
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-q", "-m", "fixture")
        return git(self.repo, "rev-parse", "HEAD")

    def run_image(self, sha):
        env = {"PATH": f"{self.bin}{os.pathsep}{os.environ['PATH']}", "HOME": str(self.tmp),
               "CAPTURE": str(self.captured)}
        return subprocess.run(["bash", str(self.script), "image", "--sha", sha], capture_output=True, text=True,
                              env=env, timeout=60)

    def test_local_dockerfile_changes_never_reach_the_build_context(self):
        sha = self.commit({"Dockerfile": COMMITTED_DOCKERFILE, ".dockerignore": COMMITTED_DOCKERIGNORE})
        (self.repo / "Dockerfile").write_text("FROM scratch\nLABEL source=local-tampered\n")
        (self.repo / ".dockerignore").write_text("*\n")
        (self.repo / "untracked.txt").write_text("local only\n")

        result = self.run_image(sha)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(COMMITTED_DOCKERFILE, (self.captured / "Dockerfile").read_text())
        self.assertEqual(COMMITTED_DOCKERIGNORE, (self.captured / ".dockerignore").read_text())
        self.assertFalse((self.captured / "untracked.txt").exists())

    def test_rejects_commit_without_dockerfile_even_if_present_locally(self):
        sha = self.commit({".dockerignore": COMMITTED_DOCKERIGNORE})
        (self.repo / "Dockerfile").write_text("FROM scratch\n")

        result = self.run_image(sha)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("Dockerfile missing in commit", result.stderr)
        self.assertFalse((self.captured / ".built").exists())

    def test_rejects_commit_without_dockerignore_even_if_present_locally(self):
        sha = self.commit({"Dockerfile": COMMITTED_DOCKERFILE})
        (self.repo / ".dockerignore").write_text(COMMITTED_DOCKERIGNORE)

        result = self.run_image(sha)

        self.assertNotEqual(0, result.returncode)
        self.assertIn(".dockerignore missing in commit", result.stderr)
        self.assertFalse((self.captured / ".built").exists())

    def test_still_requires_green_ci_before_building(self):
        sha = self.commit({"Dockerfile": COMMITTED_DOCKERFILE, ".dockerignore": COMMITTED_DOCKERIGNORE})
        (self.bin / "gh").write_text("#!/bin/sh\necho 0\n")

        result = self.run_image(sha)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("no successful 'ms-users CI' run", result.stderr)
        self.assertFalse((self.captured / ".built").exists())

    def test_image_is_tagged_and_labelled_with_the_sha(self):
        source = SCRIPT.read_text()
        self.assertIn('--label "org.opencontainers.image.revision=$SHA"', source)
        self.assertIn('-t "$ACR.azurecr.io/ms-users:$SHA"', source)
        image_step = source.split("  image)", 1)[1].split(";;", 1)[0]
        self.assertNotRegex(image_step, r"\bcp\b|\$ROOT/Dockerfile|\$ROOT/\.dockerignore")


if __name__ == "__main__":
    unittest.main()
