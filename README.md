# Delhivery AI Route Assistant — Phase 1 Prototype (Unofficial)

**Disclaimer:** This is an unofficial prototype. It is NOT affiliated with, endorsed by, or connected to Delhivery. Do not use Delhivery branding, logos, or trademarks in a public release. The name is used only to describe the user's own workflow (optimising their own assigned parcels).

## What Phase 1 does

- Parcel add / edit / delete + bulk paste (one address per line)
- Screenshot OCR (on-device ML Kit) with **user confirmation before saving** — nothing is saved from OCR without checking boxes + Confirm
- Geocoding via Nominatim (OpenStreetMap) behind a `GeocoderProvider` interface
- Route optimisation: Nearest-Neighbour initial tour + 2-opt improvement, using OSRM road distances when available, haversine fallback otherwise
- Ordered stops, total distance / time, Navigate Next (Google Maps intent → geo: URI → browser fallback)
- Delivered / Skipped status, Recalculate over remaining stops, local Room persistence
- No Accessibility Service (postponed to Phase 4), no WhatsApp (out of scope)

## Stack

- Android Kotlin + Jetpack Compose + Navigation Compose
- Room (local only), Coroutines + StateFlow, MVVM
- ML Kit Text Recognition (on-device, `com.google.mlkit:text-recognition:16.0.0`)
- OkHttp for Nominatim / OSRM
- Play Services Location (Fused Location Provider)

## Setup — build in Android Studio (NOT tested in this VM)

> **Explicit statement:** This VM has **NO JDK, NO Gradle, NO Android SDK** installed.
> The code in this folder was written as complete, buildable source files but the
> build was **NOT run here**. You MUST build it in Android Studio on a machine
> with JDK + SDK. Do not treat this as "tested / verified working" until you run it.

Requirements:
- Android Studio Ladybug (2024.2.1) or newer
- JDK 17
- Android SDK 34 (compileSdk 34, targetSdk 34, minSdk 26)
- A device or emulator with Google Play Services (ML Kit + Fused Location need it)
- Internet (Nominatim / OSRM are network APIs)

Steps:
1. Open this folder in Android Studio: `File → Open → delhivery-ai-route-assistant/`
2. Let Gradle sync. If it asks, install SDK 34 + accept licences.
3. Run: press Run in Android Studio, or from the project root run `./gradlew assembleDebug` after generating the wrapper (`gradle wrapper` on a machine with Gradle installed, or let Android Studio generate `gradlew` / `gradlew.bat` + wrapper jar on first sync — those wrapper script files are not committed here).
4. Install the `app-debug.apk` on a device / emulator.
5. Unit tests: `./gradlew :app:testDebugUnitTest`

No API keys are needed for Phase 1. No credentials are fabricated or stored in this repo.
If you later swap to Google Maps / Mapbox, add keys via `local.properties` / Secrets — never commit them.

## OSM usage policy + rate limits (read before testing)

This prototype uses two FREE OpenStreetMap-based services. They are **not for production / heavy use**.

### Nominatim (geocoding) — https://operations.osmfoundation.org/policies/nominatim/
- **Max 1 request / second.** Enforced in `NominatimGeocoder` with a Mutex + 1100 ms spacing.
- Must send a valid, identifying `User-Agent`. The code ships with a placeholder
  `RouteAssistant-Prototype/1.0 (contact: user-provided)`. Replace it with a real
  contact (e.g. your email) before any sustained use — see `ParcelViewModel` /
  `NominatimGeocoder(userAgent = ...)`.
- Results are cached in-memory. HTTP 429 / 403 are surfaced as errors with a Retry button — the code does NOT retry in a loop.
- For production: use your own Nominatim instance or a paid geocoder.

### OSRM public demo server (routing) — https://router.project-osrm.org
- Demo server only. Do not hammer it. The code throttles calls, caches matrices, and never blocks on failure.
- Matrix capped at 100 points for the demo endpoint. Larger sets fall back to haversine.
- On ANY OSRM failure the optimizer falls back to straight-line (haversine) distances and labels the result as such in the UI.
- For production: self-host OSRM or swap to a paid provider.

## How to swap the provider

Geocoding and routing are behind interfaces — the ViewModel and optimizer never call Nominatim / OSRM directly:

- Geocoding: implement `data/geocoding/GeocoderProvider.kt` (`suspend fun geocode(address): AppResult<GeocodeResult>`), then pass your implementation into `ParcelViewModel(geocoder = ...)`.
- Routing: implement `data/routing/RoutingProvider.kt` (`distanceMatrix()` + `route()`), then pass it into `ParcelViewModel(routing = ...)`.
- To swap, change only the provider construction — no changes needed in the optimizer, parser, or screens.

## How to test with 5 / 20 / 50 / 100 stops

1. On the Parcels screen, tap **Bulk Paste** and paste N addresses (one per line, with area + pincode, e.g. `Sector 62, Noida 201309`).
   - Generate test data locally: 5 / 20 / 50 / 100 distinct addresses in one city. Do NOT use real customer data for testing.
2. Tap **Geocode All** and wait — Nominatim is 1 req/sec, so 100 stops takes ~110 seconds. Failed rows show `Geo: FAILED` + Retry.
3. Tap **Optimize Route**. Check: ordered list, total km/min, "road" vs "straight-line" label, excluded count.
4. On the Route screen: tap **Navigate Next** (opens Google Maps if installed, else geo: / browser), mark a stop **Delivered**, tap **Recalculate** — the remaining stops re-optimise.
5. Test edge cases: GPS denied (deny permission → banner, route still works from first stop), airplane mode (haversine fallback + error), duplicate paste (DUPLICATE badge), empty OCR image ("No text found"), empty route (Optimize disabled / error).

Expected graceful behaviour: no artificial parcel limit; 100 stops optimise with a 2-opt iteration cap (500) so it finishes quickly. Performance on a low-end device may still be slow for 100 stops with a road matrix — that is expected for a prototype.

## Error handling (by design)

| Case | Behaviour |
|---|---|
| Geocoding fails / address not found | Row becomes `Geo: FAILED` + error text + Retry button. Excluded from optimisation with a reason. |
| Duplicate address | Flagged `DUPLICATE of #id`, not silently dropped. |
| GPS denied / no location | Banner + snackbar, route starts from first stop. No crash. |
| Network error / OSRM down | Snackbar + haversine fallback + "straight-line" label. Retry via Recalculate. |
| OCR empty / unreadable image | "No text found" message. No parcels saved. |
| Empty route | Optimize disabled when list is empty; error when no geocoded stops. |
| OCR candidates | Nothing saved until user checks + taps Confirm. Candidates are editable/removable. |

## Privacy

- Parcel / customer addresses stay in on-device Room storage. They are **not** uploaded to any AI service, analytics, or our server by default.
- OCR runs on-device via ML Kit. No image leaves the device for OCR.
- Addresses ARE sent to Nominatim / OSRM over HTTPS for geocoding / routing — that is inherent to those providers. If you do not want addresses sent to OSM services, swap the providers for a self-hosted / on-device alternative and do not use Geocode All.
- CSV export is local-only (returns a string for the user to save/share themselves).

## Known limitations

- **Build NOT tested in this VM** (no JDK / SDK / Gradle here). Build in Android Studio.
- ML Kit needs Google Play Services; it may fail on emulators without Play Services.
- OSRM demo server + Nominatim are prototype-only, rate-limited, and can be slow / unavailable.
- Geocoding quality depends on address completeness — always include area + pincode.
- Navigation is via external Maps intent, not in-app turn-by-turn.
- `fallbackToDestructiveMigration()` is used for the prototype Room DB — schema changes wipe local data. Replace with migrations for production.
- No Accessibility Service in Phase 1 (postponed to Phase 4 by user decision).
- No WhatsApp feature (out of scope).
- No authentication, no cloud sync, no backup.

## Project structure

```
settings.gradle.kts / build.gradle.kts / gradle.properties
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/com/routeassistant/app/
  MainActivity.kt
  data/entity/ParcelEntity.kt
  data/dao/ParcelDao.kt
  data/db/AppDatabase.kt
  data/repository/ParcelRepository.kt
  data/geocoding/GeocoderProvider.kt, NominatimGeocoder.kt
  data/routing/RoutingProvider.kt, OsrmRoutingProvider.kt, Haversine.kt
  domain/optimizer/RouteOptimizer.kt
  domain/parser/AddressParser.kt, OcrTextParser.kt
  ocr/OcrHelper.kt
  ui/viewmodel/ParcelViewModel.kt
  ui/screens/ParcelListScreen.kt, OcrConfirmScreen.kt, RouteScreen.kt
  ui/components/StatusBadge.kt
  util/Result.kt, LocationHelper.kt
app/src/test/java/com/routeassistant/app/RouteOptimizerTest.kt
```

## Next steps (not in Phase 1)

- Phase 2: progress polish, ETA, manual start-address picker.
- Phase 3: OCR accuracy tuning, batch screenshot handling.
- Phase 4: Accessibility Service — only after explicit Play Store policy review.
- Production: provider swap (paid geocoder / self-hosted OSRM), Room migrations, signed release build.
