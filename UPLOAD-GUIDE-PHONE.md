# Phone-only upload — minimum steps (no Codespaces, no Termux, no PC)

Repo: `suraj-bypass/delhivery-ai-route-assistant` (Public)
This ZIP is FLAT: files are at the ZIP root, NOT inside an extra folder.
Extract it, then upload the CONTENTS (not the ZIP file itself).

## Step 1 — Create the repo (1 minute, once only)
1. Phone browser → github.com → sign in.
2. Top-right `+` → New repository.
3. Name: `delhivery-ai-route-assistant`
4. Public → **Add a README: OFF** → Create repository.
5. Leave the empty-repo page open.

## Step 2 — Upload files (the only tricky part)
1. On the empty-repo page, tap **"uploading an existing file"**.
2. Tap **"choose your files"**.
3. In the file picker, select ALL extracted files + folders at once:
   - Folders: `app`, `gradle`, `.github`
   - Files: `gradlew`, `gradlew.bat`, `build.gradle.kts`, `settings.gradle.kts`,
     `gradle.properties`, `.gitignore`, `README.md`, `PHONE-BUILD.md`,
     `TEST-REPORT.md`, `UPLOAD-GUIDE-PHONE.md`
   - IMPORTANT: `.github` is a hidden folder. If your file picker does not
     show it, do Step 2b below instead of skipping it. Without `.github`,
     Actions will NOT run.
4. Wait for upload to finish → tap **Commit changes** (green button).
5. First commit already triggers a build (workflow runs on push).

## Step 2b — If `.github` does not appear in the picker (common on Android)
Do this AFTER Step 2, do NOT skip the workflow:
1. In the repo, tap **Add file → Create new file**.
2. In the filename box type exactly:
   `.github/workflows/android.yml`
3. Paste the full contents of the `android.yml` file from this ZIP
   (open it in any text viewer, copy all).
4. Tap **Commit changes**.
5. Now go to Actions — the workflow will appear.

## Step 3 — Run the build
1. Repo → **Actions** tab → **"Android Build (APK + Unit Tests)"**.
2. Tap **Run workflow** → **Run workflow** (green button).
3. Wait 3–6 minutes. Do NOT close the tab until it finishes.
4. Green tick = build + unit tests BOTH passed.
   Red cross = tap the failed run → tap the failed step → copy the error
   text / screenshot and send it for a fix. Do NOT guess.

## Step 4 — Download the APK (only after green tick)
1. Same run page → scroll down → **Artifacts** → `app-debug-apk`.
2. Download the ZIP → extract → `app-debug.apk` inside.
3. Tap the APK → Install → if asked, Allow "Install unknown apps".
4. This is a DEBUG APK for testing, not a Play Store release.

## What NOT to do
- Do NOT upload the ZIP file itself to the repo — GitHub will NOT unzip it.
- Do NOT use Codespaces for this — it is not needed.
- Do NOT skip `.github/workflows/android.yml`.
- Do NOT call the build successful until the Actions run is green AND
  the `app-debug-apk` artifact exists.

## If upload keeps failing
- Upload in 2 batches: first `app/` + `gradle/` + root files, commit.
  Then `.github/` alone (or via Step 2b), commit.
- GitHub web upload allows ~100 files per batch. This project has ~45 files,
  so one batch is enough if the picker allows multi-select.
