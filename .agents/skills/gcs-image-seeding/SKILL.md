---
name: gcs-image-seeding
description: >-
  Standard operating procedure and reusable toolchain for formatting, processing,
  and syncing illustration images to the Google Cloud Storage semantic image cache
  (`gs://portugese-kids-image-cache/image-cache/`).
license: Apache-2.0
metadata:
  version: 2.0.0
---

# GCS Image Seeding Skill

Use this skill whenever you need to add, inspect, format, or upload story illustration images to the Portuguese for Kids semantic image cache in Google Cloud Storage (`gs://portugese-kids-image-cache/`).

> [!TIP]
> **Need to analyze missing cache prompts or cache misses?**
> If you are diagnosing why natural story prompts or entries in `non-showing-pictures-prompts.md` failed to hit cached images, use the companion skill:
> **[`missing-cache-prompt-analysis`](file:///Users/mesutuluag/Documents/codingProjects/practice-portuguese/Portugese-for-Kids-Backend/.agents/skills/missing-cache-prompt-analysis/SKILL.md)**.
> It diagnoses whether the gap is in `SemanticImageCache.java` (vocabulary/domain stems) or requires new illustrations.

---

## 1. Quick Start Workflow

To add new images to the cache:

### Step 1: Provide the Image & Prompt
Choose one of these convenient methods to drop files into [`seed-images/incoming/`](file:///Users/mesutuluag/Documents/codingProjects/practice-portuguese/seed-images/incoming/):

* **Method A (Sidecar `.txt` file - Recommended for custom images):**  
  Drop `scene_name.png` (or `.jpg` / `.webp`) and a companion `scene_name.txt` containing the exact prompt text.
* **Method B (Story ID / Code):**  
  Name the file with a known story code (e.g. `M-01.jpg`, `school-02.png`, `cafe-03.webp`).
* **Method C (CLI single file):**  
  ```bash
  python3 seed-images/process_images.py --image path/to/my_image.png --prompt "exact prompt string" --id "my-scene"
  ```

### Step 2: Process & Validate Locally
Run the automated image processing pipeline:
```bash
python3 seed-images/process_images.py
```
This automatically:
1. Flattens transparency onto a solid white background (`#FFFFFF`).
2. Resizes the image to the standard desktop/mobile ratio: **`768 x 336`** pixels.
3. Compresses to baseline **JPEG at 60% quality** (`~40–50 KB`).
4. Generates the exact SHA-256 hash matching the prompt UTF-8 bytes.
5. Stages `image.jpg` and `prompt.txt` in [`seed-images/processed/<sha256>/`](file:///Users/mesutuluag/Documents/codingProjects/practice-portuguese/seed-images/processed/).
6. Updates [`seed-images/manifest.json`](file:///Users/mesutuluag/Documents/codingProjects/practice-portuguese/seed-images/manifest.json).

### Step 3: Audit Cache Status (Optional)
Check current GCS coverage against all story prompt templates:
```bash
python3 seed-images/audit_cache.py
```

### Step 4: Upload to Google Cloud Storage
Preview changes first with dry-run:
```bash
python3 seed-images/upload_to_gcs.py --dry-run
```

Once confirmed, upload to GCS:
```bash
python3 seed-images/upload_to_gcs.py
```
*(Or upload a specific hash only: `python3 seed-images/upload_to_gcs.py --sha <hash>`)*

---

## 2. Technical Standards (`GcsImageStore` Contract)

All cached images must conform strictly to the backend expectations in [`GcsImageStore.java`](file:///Users/mesutuluag/Documents/codingProjects/practice-portuguese/Portugese-for-Kids-Backend/src/main/java/com/mesutuluag/portugeseforkidsbackend/image/GcsImageStore.java) and [`SemanticImageCache.java`](file:///Users/mesutuluag/Documents/codingProjects/practice-portuguese/Portugese-for-Kids-Backend/src/main/java/com/mesutuluag/portugeseforkidsbackend/image/SemanticImageCache.java):

| Parameter | Specification | Notes |
| :--- | :--- | :--- |
| **Dimensions** | `768 x 336` px | 16:7 ratio optimized for story banners on mobile & web |
| **Format** | Baseline JPEG (`image/jpeg`) | Alpha flattened to white background |
| **Quality** | `0.60` (60%) | Targets ~30–50 KB per asset |
| **Hash Key** | `sha256(prompt.strip())` | Lowercase hexadecimal string |
| **Primary Blob** | `image-cache/<sha256>/image.jpg` | Includes custom metadata: `prompt: "<prompt>"` |
| **Companion Blob** | `image-cache/<sha256>/prompt.txt` | UTF-8 plain text for fallback & audits |

---

## 3. Directory Layout

```
.agents/skills/
├── gcs-image-seeding/
│   └── SKILL.md                  # This skill definition
└── missing-cache-prompt-analysis/
    └── SKILL.md                  # Gap analysis, SemanticImageCache fix, and seeding workflow

seed-images/
├── incoming/                 # Drop incoming raw files here (.png, .jpg, .webp, .txt)
├── processed/                # Auto-generated 768x336 JPEG & prompt.txt folders
│   └── <sha256>/
│       ├── image.jpg
│       └── prompt.txt
├── manifest.json             # Registry of processed images & their targets
├── prompts.json              # Optional custom ID-to-prompt overrides
├── process_images.py         # Automated resizing, conversion & hash staging script
├── simulate_cache.py         # TF-IDF cosine similarity simulator matching SemanticImageCache
├── upload_to_gcs.py          # GCS upload script (supports --dry-run and --sha)
└── audit_cache.py            # Audit tool verifying coverage against story prompts
```

---

## 4. Safety & Authorization Guardrails

> [!CAUTION]
> 1. **NEVER PUSH TO GCS WITHOUT EXPLICIT USER CONFIRMATION.**
>    Always display the list or manifest of staged files and verify `gcloud` authentication before initiating any upload to `gs://portugese-kids-image-cache/`.
> 2. **NEVER USE POLLINATIONS TO GENERATE SEED IMAGES.**
>    Always use the official `image-generator` subagent (Gemini Imagen) for producing story seed illustrations.
> 3. **NEVER COMMIT `non-showing-pictures-prompts.md` TO GIT.**
>    Story prompt test dumps and audit scratchpads must remain untracked.
