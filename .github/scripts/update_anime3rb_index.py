#!/usr/bin/env python3
"""Replace only the Anime3rb entry in an existing extension index.

Usage: update_anime3rb_index.py <apk_artifact_dir> <repo_dir> <workspace_root> <signing_sha256_hex>

Builds a one-extension index in a temporary folder with make_index.py, then merges that
entry into <repo_dir>/index.min.json. Every other entry, APK and icon in the repo branch is
left as it is. repo.json gets the signing fingerprint of the key used for this build.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile

PKG = "eu.kanade.tachiyomi.animeextension.ar.anime3rb"


def main() -> None:
    artifact_dir, repo_dir, workspace, fingerprint = sys.argv[1:5]
    tmp = tempfile.mkdtemp()
    subprocess.check_call(
        [sys.executable, os.path.join(workspace, ".github", "scripts", "make_index.py"), artifact_dir, tmp, workspace]
    )
    with open(os.path.join(tmp, "index.min.json"), encoding="utf-8") as f:
        new_entries = [e for e in json.load(f) if e["pkg"] == PKG]
    if len(new_entries) != 1:
        sys.exit(f"expected exactly one {PKG} entry, got {len(new_entries)}")
    new = new_entries[0]

    index_path = os.path.join(repo_dir, "index.min.json")
    entries = []
    if os.path.exists(index_path):
        with open(index_path, encoding="utf-8") as f:
            entries = json.load(f)

    kept = []
    for e in entries:
        if e["pkg"] == PKG:
            old_apk = os.path.join(repo_dir, "apk", e["apk"])
            if e["apk"] != new["apk"] and os.path.exists(old_apk):
                os.remove(old_apk)
        else:
            kept.append(e)
    kept.append(new)

    os.makedirs(os.path.join(repo_dir, "apk"), exist_ok=True)
    os.makedirs(os.path.join(repo_dir, "icon"), exist_ok=True)
    shutil.copyfile(os.path.join(tmp, "apk", new["apk"]), os.path.join(repo_dir, "apk", new["apk"]))
    icon = os.path.join(tmp, "icon", f"{PKG}.png")
    if os.path.exists(icon):
        shutil.copyfile(icon, os.path.join(repo_dir, "icon", f"{PKG}.png"))

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

    print(f"index: {len(kept)} entries, Anime3rb -> {new['apk']} (others untouched)")


if __name__ == "__main__":
    main()
