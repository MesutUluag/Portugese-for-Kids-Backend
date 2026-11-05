#!/usr/bin/env python3
"""
Audits GCS cache coverage against all canonical story prompts.

Usage:
  python3 seed-images/audit_cache.py
"""

import os
import re
import glob
import hashlib
import subprocess
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
def find_story_dir():
    candidates = [
        BASE_DIR.parent / "src" / "main" / "resources" / "prompts",
        BASE_DIR.parent / "Portugese-for-Kids-Backend" / "src" / "main" / "resources" / "prompts"
    ]
    for c in candidates:
        if c.exists():
            return c
    return candidates[0]

STORY_DIR = find_story_dir()
BUCKET = "gs://portugese-kids-image-cache"


def run():
    print("Fetching existing cache entries from GCS...")
    try:
        raw_list = subprocess.check_output(
            ["gcloud", "storage", "objects", "list", f"{BUCKET}/image-cache/**/image.jpg"],
            text=True
        )
        # Matches both gs://bucket/image-cache/<sha>/image.jpg and name: image-cache/<sha>/image.jpg
        cached_shas = set(re.findall(r'image-cache/([a-f0-9]{64})/image\.jpg', raw_list))
    except Exception as e:
        print(f"Error querying GCS: {e}")
        return

    print(f"Found {len(cached_shas)} cached images in {BUCKET}.\n")

    # Collect all canonical prompts
    canonical_prompts = []
    for md_file in sorted(glob.glob(str(STORY_DIR / "story-system-prompt-*.md"))):
        theme = Path(md_file).stem.replace("story-system-prompt-", "")
        with open(md_file, "r", encoding="utf-8") as f:
            content = f.read()
        matches = [p for p in re.findall(r'\"([^\"]*colorful[^\"]*no text)\"', content) if "<" not in p]
        for idx, p in enumerate(matches, 1):
            sha = hashlib.sha256(p.strip().encode("utf-8")).hexdigest()
            canonical_prompts.append({
                "theme": theme,
                "index": idx,
                "prompt": p.strip(),
                "sha256": sha,
                "cached": sha in cached_shas
            })

    total = len(canonical_prompts)
    cached_count = sum(1 for p in canonical_prompts if p["cached"])
    missing = [p for p in canonical_prompts if not p["cached"]]

    print("=" * 60)
    print(f"STORY CACHE COVERAGE AUDIT")
    print("=" * 60)
    print(f"Total Canonical Prompts across all stories: {total}")
    print(f"Cached in GCS:                             {cached_count} / {total} ({cached_count/total*100:.1f}%)")
    print(f"Missing from GCS:                          {len(missing)}")
    print("=" * 60)

    if missing:
        print("\nMissing Prompts:")
        for idx, item in enumerate(missing, 1):
            print(f"\n[{idx}] Theme: {item['theme']} (Scene #{item['index']})")
            print(f"    SHA-256: {item['sha256']}")
            print(f"    Prompt:  \"{item['prompt']}\"")
    else:
        print("\n🎉 100% of all story prompts are cached in Google Cloud Storage!")


if __name__ == "__main__":
    run()
