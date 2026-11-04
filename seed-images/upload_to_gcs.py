#!/usr/bin/env python3
"""
Uploads processed image cache entries to Google Cloud Storage.

Features:
- Preserves exact contract required by GcsImageStore.java
- Automatically checks existing GCS hashes and skips already-uploaded entries (unless --force)
- Uploads `image.jpg` with custom metadata `--custom-metadata="^:^prompt=..."`
- Uploads sibling `prompt.txt`
- Supports `--dry-run` to preview uploads without mutating GCS
- Supports `--sha <hash>` to upload a specific item
"""

import json
import subprocess
import sys
import os
import argparse
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
MANIFEST_FILE = BASE_DIR / "manifest.json"
DEFAULT_BUCKET = "gs://portugese-kids-image-cache"


def get_existing_gcs_shas(bucket: str) -> set:
    try:
        res = subprocess.run(
            ["gcloud", "storage", "objects", "list", f"{bucket}/image-cache/**/image.jpg"],
            capture_output=True, text=True, check=True
        )
        return {
            line.split("/")[1]
            for line in res.stdout.splitlines()
            if line.startswith("name:") and "/" in line
        }
    except Exception:
        return set()


def upload(bucket: str = DEFAULT_BUCKET, target_sha: str = None, dry_run: bool = False, force: bool = False):
    if not MANIFEST_FILE.exists():
        print(f"Error: {MANIFEST_FILE} not found. Run process_images.py first.")
        sys.exit(1)

    with open(MANIFEST_FILE, "r", encoding="utf-8") as f:
        manifest = json.load(f)

    if target_sha:
        manifest = [m for m in manifest if m["sha256"].startswith(target_sha)]
        if not manifest:
            print(f"No entry in manifest matches SHA prefix '{target_sha}'.")
            return
    elif not force:
        existing_shas = get_existing_gcs_shas(bucket)
        if existing_shas:
            before = len(manifest)
            manifest = [m for m in manifest if m["sha256"] not in existing_shas]
            skipped = before - len(manifest)
            if skipped:
                print(f"Skipping {skipped} already-uploaded entries in {bucket} (use --force to re-upload).")

    total = len(manifest)
    if total == 0:
        print("All manifest entries are already present in GCS. Nothing to upload.")
        return

    action = "[DRY RUN] Would upload" if dry_run else "Uploading"
    print(f"{action} {total} new entries to {bucket}...")

    success = 0
    errors = 0

    for i, item in enumerate(manifest, 1):
        item_id = item.get("id", "custom")
        sha = item["sha256"]
        prompt = item["prompt"]

        local_img = BASE_DIR / "processed" / sha / "image.jpg"
        local_txt = BASE_DIR / "processed" / sha / "prompt.txt"
        gcs_img = f"{bucket}/image-cache/{sha}/image.jpg"
        gcs_txt = f"{bucket}/image-cache/{sha}/prompt.txt"

        if not local_img.exists() or not local_txt.exists():
            print(f"[{i}/{total}] Skipping {item_id} ({sha[:8]}): Missing processed local files.")
            errors += 1
            continue

        print(f"\n[{i}/{total}] {item_id} ({sha[:8]}...) -> {gcs_img}")

        cmd_img = [
            "gcloud", "storage", "cp",
            str(local_img),
            gcs_img,
            f"--custom-metadata=^:^prompt={prompt}"
        ]
        cmd_txt = [
            "gcloud", "storage", "cp",
            str(local_txt),
            gcs_txt
        ]

        if dry_run:
            print(f"  CMD: {' '.join(cmd_img)}")
            print(f"  CMD: {' '.join(cmd_txt)}")
            success += 1
            continue

        try:
            subprocess.run(cmd_img, check=True, capture_output=True, text=True)
            subprocess.run(cmd_txt, check=True, capture_output=True, text=True)
            print(f"  ✓ Uploaded image.jpg & prompt.txt")
            success += 1
        except subprocess.CalledProcessError as e:
            err_msg = e.stderr.strip()
            print(f"  ✗ Failed: {err_msg}")
            errors += 1
            if "Operation not permitted" in err_msg or "PermissionError" in err_msg:
                print("Aborting early due to sandbox/permission restriction on gcloud credentials.")
                sys.exit(1)

    print(f"\n==========================================")
    print(f"Done. Successfully processed: {success}/{total}. Errors: {errors}")


def main():
    parser = argparse.ArgumentParser(description="Upload image cache to GCS")
    parser.add_argument("--bucket", default=DEFAULT_BUCKET, help="Target GCS bucket")
    parser.add_argument("--sha", help="Upload a specific hash only")
    parser.add_argument("--dry-run", action="store_true", help="Print commands without executing")
    parser.add_argument("--force", action="store_true", help="Re-upload even if SHA already exists in GCS")
    args = parser.parse_args()

    upload(bucket=args.bucket, target_sha=args.sha, dry_run=args.dry_run, force=args.force)


if __name__ == "__main__":
    main()
