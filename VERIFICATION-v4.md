# Verification — v4 repack (2026-10-15)

This file records what was ACTUALLY checked for the v4 ZIP.
It does NOT claim a Gradle build or APK. See the table.

## What changed vs v3
- Source code: UNCHANGED (no blocking compile bug found in audit).
- Workflow `.github/workflows/android.yml`: UNCHANGED (already correct).
- Packaging: FIXED. v3 ZIP had all files nested under
  `delhivery-ai-route-assistant/` — easy to upload the wrong level on a phone.
  v4 ZIP is FLAT: files at the ZIP root, ready to select-all + upload.
- Added: `UPLOAD-GUIDE-PHONE.md` (minimum-steps phone upload, incl. `.github`
  fallback) and this file.

## Verification done 2026-10-15 (fresh checks, not copied from old report)
| Check | Result | Evidence |
|---|---|---|
| ZIP contains all source files | PASS | `unzip -l` lists 46 files incl. `app/`, `gradle/`, `.github/workflows/android.yml` |
| Gradle wrapper present | PASS | `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle-wrapper.properties` (Gradle 8.7) |
| Workflow present + correct | PASS (static) | `android.yml` runs JDK 17 → `testDebugUnitTest` → `assembleDebug` → uploads `app-debug-apk` artifact |
| `gradlew` executable bit | PASS | `-rwxr-xr-x` preserved in staging; workflow also runs `chmod +x gradlew` |
| Source audit (all 21 Kotlin files + Manifest + Gradle files read) | PASS (static) | No missing import / undefined symbol found. `collectAsStateWithLifecycle` is covered by `lifecycle-runtime-compose:2.8.4`. Compose compiler 1.5.14 matches Kotlin 1.9.24. AGP 8.5.2 + Gradle 8.7 pairing is valid. |
| Pure-Kotlin unit test via kotlinc + JUnit (alternative check) | PASS | `OK (5 tests)`, 0.059s, `RouteOptimizerTest` — same 5 tests as the Gradle task would run |
| Gradle build in this VM | NOT TESTED | Reproduced with fresh Temurin JDK 17.0.20.1 + Gradle 8.7: every Gradle invocation fails with `Could not receive a message from the daemon` / `Unexpected type tag 109` even with `--no-daemon`. A trivial Gradle project would fail the same way. This is a VM daemon-IPC limitation, NOT a project failure. The real Gradle run must happen in GitHub Actions. |
| `./gradlew testDebugUnitTest` via Gradle | NOT TESTED | Same daemon blocker. The 5/5 above is the kotlinc+JUnit alternative, NOT the Gradle task. |
| `./gradlew assembleDebug` + APK | NOT TESTED | No APK produced here. No APK path / size / SHA-256 exists yet. |
| Direct upload to `suraj-bypass/delhivery-ai-route-assistant` by assistant | NOT POSSIBLE | `gh` CLI is installed but `gh auth status` = "not logged into any GitHub hosts". No GitHub token / connector exists in this environment. Password / OTP / PAT were NOT asked for and will NOT be asked for. Upload must be done by the user via phone browser (see `UPLOAD-GUIDE-PHONE.md`). |
| Device tests (Room, OCR, Maps, GPS, install) | NOT TESTED | Require the GitHub-built APK + real phone. |

## APK rule (standing)
APK is successful ONLY when GitHub Actions shows a green run AND the
`app-debug-apk` artifact exists. Until then: APK = NOT TESTED, no link,
no size, no checksum. Do not accept any other claim.

## Known non-blocking notes (unchanged from TEST-REPORT.md)
- Nominatim/OSRM User-Agent still contains placeholder contact
  `user-provided` — replace with a real contact before sustained use.
- `fallbackToDestructiveMigration()` is prototype-only.
- Nominatim + OSRM demo server are prototype-only.
