# Test Report — Delhivery AI Route Assistant (Phase 1)

Date: 2026-10-15. Tested in this VM (Linux, Temurin JDK 17.0.20.1, kotlinc 1.9.24).
**No real Android device / emulator was available, so everything that needs
Android runtime is honestly marked NOT TESTED. No result here is fabricated.**

## How tests were run (read this before trusting the table)

- **Gradle builds could NOT run in this VM.** Every Gradle invocation
  (`gradle wrapper`, trivial `gradle hi`, `assembleDebug`, `testDebugUnitTest`)
  failed with `Could not receive a message from the daemon` even though the
  daemon process itself started (`Daemon server started` in its log).
  A trivial empty Gradle project failed identically, proving this is an
  environment / daemon-IPC limitation, NOT a project code failure.
  The real `./gradlew assembleDebug` + `./gradlew testDebugUnitTest` must
  still be run in GitHub Actions or Android Studio — the workflow for that
  is in `.github/workflows/android.yml`.
- **Pure-Kotlin modules were REALLY compiled and executed** with kotlinc
  1.9.24 (same version as the project) + JUnit 4.13.2:
  `RouteOptimizer`, `Haversine`, `AddressParser`, `OcrTextParser`,
  `RoutingProvider` (types only), `Result`.
- **Live OSM services were REALLY called** with curl: Nominatim geocode +
  OSRM table + OSRM route returned `code: Ok` with the exact JSON shapes
  the providers parse.
- **Android-only code** (Room, Compose, ML Kit, Fused Location, Maps
  intent execution) was verified by source inspection + cross-file symbol
  checks only — NOT executed.

## Results table

| # | Test | Result | Evidence |
|---|------|--------|----------|
| 1 | JDK / Gradle / Android SDK setup | **PASS (with workaround)** | JDK 17 Temurin 17.0.20.1 `java -version` OK; Gradle 8.7 `--version` OK; SDK 34: `platforms/android-34/android.jar` + `build-tools/34.0.0/aapt2` + `platform-tools` present. sdkmanager failed on egress proxy; SDK was installed via direct ZIP download instead. |
| 2 | `./gradlew assembleDebug` + APK | **NOT TESTED** | Blocked by Gradle daemon IPC failure in this VM (see above). No APK was produced here. No APK link is given. Run via GitHub Actions workflow. |
| 3 | `./gradlew testDebugUnitTest` | **NOT TESTED (via Gradle)** | Same blocker. BUT the same test file was run directly: see #3b. |
| 3b | Project's `RouteOptimizerTest` via kotlinc + JUnit | **PASS** | `OK (5 tests)`, 0.102s. Tests: empty, single, 2-opt vs NN, ungeocoded exclusion, 100 stops. |
| 4a | Optimizer: 0 stops | **PASS** | Harness: empty route, 0 km. |
| 4b | Optimizer: 1 stop | **PASS** | 1 stop, exact haversine distance; null-start single = 0 km. |
| 4c | Optimizer: 5 / 20 / 100 stops | **PASS** | No missing, no duplicates, totals match independent recomputation, 2-opt <= NN cost, 100 stops in 129ms. |
| 4d | Optimizer: road-matrix path | **PASS** | Synthetic road matrix -> `usedRoadDistances=true`; mismatched matrix -> haversine fallback; null start + road matrix -> haversine fallback. |
| 4e | Optimizer: missing coordinates | **PASS** | Ungeocoded excluded with reasons + ids; duplicate coordinates both kept. |
| 4f | Optimizer: null-start NPE | **FAIL then FIXED, re-tested PASS** | First harness run crashed: `NullPointerException` at `RouteOptimizer.kt` `cost()` when start == null (GPS denied) + multi-stop route. Real bug, caught by testing. Fixed with a null-start guard (free start leg). Re-ran: 44/44 PASS. |
| 5a | Address parsing (bulk) | **PASS** | Duplicates flagged case+space-insensitive, short lines + blanks dropped, whitespace normalised, existing-address dedupe, all-blank -> 0. |
| 5b | Duplicate handling inconsistency | **FAIL then FIXED** | `ParcelRepository.add/updateAddress` used case-SENSITIVE compare while `parseBulk` used case-insensitive. Fixed to `normalizeForCompare` in both. (Logic fix, verified by inspection; Room runtime still NOT TESTED.) |
| 5c | OCR text parsing | **PASS** | Junk (time / battery / app chrome / bare numbers / punctuation) removed; short digit-free fragment merges; long lines stay separate for user correction; empty -> empty. |
| 5d | Network failure (provider code) | **PASS (static) / NOT TESTED (executed)** | Provider code returns `Err` on every failure path and callers fall back to haversine (inspected). Live OSRM/Nominatim reachable via curl (see #5e). The Kotlin provider HTTP code itself was NOT executed (needs OkHttp + Android runtime). |
| 5e | Live Nominatim + OSRM services | **PASS** | Nominatim: Connaught Place -> 28.6317695, 77.2193804. OSRM table 3 points -> `code: Ok`, CP->Noida 22.3 km road (vs ~16 km straight-line). OSRM route -> `code: Ok`, 22.3 km / 26.2 min. |
| 6 | Room: save / restart / status | **NOT TESTED (executed)** | Entity/Dao/Repository inspected: consistent, `Room.databaseBuilder` with file `route_assistant.db` survives restart by design, `saveOrder` persists order, `setDeliveryStatus` updates status. Actual on-device persistence NOT verified — must be tested on phone (add -> kill app -> reopen -> data present; mark Delivered -> reopen -> still Delivered). |
| 7 | Google Maps intent / next-stop | **PASS (static) / NOT TESTED (executed)** | Code inspected: null-coords guard, 3-level fallback (Maps intent -> geo: -> browser), next-stop = first PENDING ordered stop. **Bug found + fixed:** Manifest had no `<queries>`, so on Android 11+ `resolveActivity()` would return null even with Maps installed. Added `<queries>` for Maps + geo + https. Tapping Navigate on a real phone is still NOT TESTED. |
| 8a | Permissions / Manifest | **PASS (static)** | INTERNET, NETWORK_STATE, FINE/COARSE location, FOREGROUND_SERVICE, POST_NOTIFICATIONS, READ_MEDIA_IMAGES (+ legacy READ_EXTERNAL_STORAGE maxSdk 32). No Accessibility. `FOREGROUND_SERVICE` + `POST_NOTIFICATIONS` are declared but no service/notification code uses them yet — harmless, noted. |
| 8b | ML Kit OCR | **PASS (static) / NOT TESTED (executed)** | On-device, no upload, confirm-before-save flow inspected. **Bug found + fixed:** failure listener swallowed ML Kit errors as `""` (misreported as "No text found"). Now propagates the real error. Real OCR on a screenshot NOT TESTED — must be tested on phone. |
| 8c | Compose UI | **PASS (static) / NOT TESTED (executed)** | Screens reference only defined ViewModel functions (cross-file check: 20/20 defined; `markFailed`/`exportCsv` defined but unused in screens — fine). Layout/rendering NOT TESTED — must be tested on phone. |
| 8d | SDK config | **PASS (static)** | compileSdk 34, targetSdk 34, minSdk 26, Kotlin 1.9.24, Compose BOM 2024.06.00, Compose compiler 1.5.14 (correct pairing — earlier 1.7.3 mismatch fixed), AGP 8.5.2, Gradle 8.7 wrapper. Gradle sync itself NOT TESTED (daemon blocker). |
| 9 | Fix + re-run | **PASS** | 4 real bugs fixed (NPE, dedupe case, OCR error swallow, Manifest queries). Full harness re-run after fixes: **44/44 PASS**. JUnit re-run: **5/5 PASS**. |
| 10 | APK path / size / SHA-256 | **NOT TESTED — no APK** | No APK exists because `assembleDebug` could not run here. Do NOT accept any APK link for this build until GitHub Actions / Android Studio produces it. |

## Harness totals (real runs, after fixes)
- Custom harness (`Harness.kt`, 44 assertions): **44 passed, 0 failed.**
- Project JUnit (`RouteOptimizerTest`): **5 passed, 0 failed.**

## Remaining issues — must be covered by phone testing
1. Full Gradle build + APK (GitHub Actions) — not yet run anywhere.
2. Room persistence across app restart; Delivered/Skipped status after reopen.
3. OCR on a real Delhivery screenshot (accuracy, confirmation flow).
4. Geocode All on real addresses (Nominatim 1 req/sec; 100 stops ~110s).
5. Navigate Next on a real phone with Google Maps installed.
6. GPS denied / no internet / airplane-mode behaviour on device.
7. Compose UI on small screens; bulk paste with 100 addresses.
8. Placeholder Nominatim/OSRM User-Agent still needs a real contact before sustained use.
9. `fallbackToDestructiveMigration()` is prototype-only; production needs migrations.
10. OSRM demo server + Nominatim are prototype-only, not for production traffic.

## Files changed by this testing round
- `RouteOptimizer.kt` — null-start NPE guard.
- `ParcelRepository.kt` — case-insensitive dedupe.
- `OcrHelper.kt` — propagate ML Kit errors.
- `AndroidManifest.xml` — added `<queries>` for Maps/geo.
- Added: `TEST-REPORT.md` (this file), `.github/workflows/android.yml`, `PHONE-BUILD.md`, Gradle wrapper files (`gradlew`, `gradlew.bat`, `gradle-wrapper.jar`).
