#!/usr/bin/env python3
"""Replace only the listed package entries in an existing extension index.

Usage: update_index.py <apk_artifact_dir> <repo_dir> <workspace_root> <signing_sha256_hex> <pkg> [<pkg> ...]

Builds an index of the freshly built extensions in a temporary folder with make_index.py,
then merges only the entries for the listed packages into <repo_dir>/index.min.json.
Every other entry, APK and icon in the repo branch is left as it is.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile


def main() -> None:
    artifact_dir, repo_dir, workspace, fingerprint = sys.argv[1:5]
    pkgs = set(sys.argv[5:])
    if not pkgs:
        sys.exit("no packages given")
    tmp = tempfile.mkdtemp()
    subprocess.check_call(
        [sys.executable, os.path.join(workspace, ".github", "scripts", "make_index.py"), artifact_dir, tmp, workspace]
    )
    with open(os.path.join(tmp, "index.min.json"), encoding="utf-8") as f:
        built = {e["pkg"]: e for e in json.load(f) if e["pkg"] in pkgs}
    if set(built) != pkgs:
        sys.exit(f"expected entries {sorted(pkgs)}, got {sorted(built)}")

    index_path = os.path.join(repo_dir, "index.min.json")
    entries = []
    if os.path.exists(index_path):
        with open(index_path, encoding="utf-8") as f:
            entries = json.load(f)

    kept = []
    for e in entries:
        if e["pkg"] in pkgs:
            new = built[e["pkg"]]
            old_apk = os.path.join(repo_dir, "apk", e["apk"])
            if e["apk"] != new["apk"] and os.path.exists(old_apk):
                os.remove(old_apk)
        else:
            kept.append(e)
    os.makedirs(os.path.join(repo_dir, "apk"), exist_ok=True)
    os.makedirs(os.path.join(repo_dir, "icon"), exist_ok=True)
    for pkg, new in built.items():
        kept.append(new)
        shutil.copyfile(os.path.join(tmp, "apk", new["apk"]), os.path.join(repo_dir, "apk", new["apk"]))
        icon = os.path.join(tmp, "icon", f"{pkg}.png")
        if os.path.exists(icon):
            shutil.copyfile(icon, os.path.join(repo_dir, "icon", f"{pkg}.png"))

    with open(index_path, "w", encoding="utf-8") as f:
        json.dump(kept, f, ensure_ascii=False, separators=(",", ":"))

    meta_path = os.path.join(repo_dir, "repo.json")
    meta = {"meta": {}}
    if os.path.exists(meta_path):
        with open(meta_path, encoding="utf-8") as f:
            meta = json.load(f)
    meta.setdefault("meta", {})["signingKeyFingerprint"] = fingerprint.lower()
    with open(meta_path, "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, separators=(",", ":"))

    print(f"index: {len(kept)} entries; replaced {sorted(pkgs)} (others untouched)")


if __name__ == "__main__":
    main()
