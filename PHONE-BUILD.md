# Phone-Only Build Guide (No Laptop / PC Needed)

The APK is built in the cloud with GitHub Actions — free for public repos.
You only need your phone browser. No app installs needed.

## Option A — GitHub Actions (recommended, free, 5 minutes of tapping)

### One-time setup (phone browser, github.com)
1. github.com par jao, sign in karo (free account banao agar nahi hai).
2. **New repository** banao: naam `delhivery-ai-route-assistant`, **Public** rakho
   (private repo par bhi Actions free minutes milte hain, par public par
   sabse aasaan hai). "Add a README" ko **OFF** rakho.
3. Repo ke andar **"uploading an existing file"** link par tap karo.
4. Is project ka ZIP (jisme `gradlew` + `.github/workflows/android.yml`
   shamil hai) upload karo. GitHub web par poora ZIP ek saath upload
   nahi hota — isliye neeche diye tareeke se karo:
   - Phone me ZIP ko extract karo (Files app / ZArchiver jaisi free app se).
   - GitHub web uploader me saari files + folders select karke upload karo.
   - Ya: phone me **Termux** nahi hai to yehi web upload tareeka use karo.
   - Sabse aasaan: GitHub mobile app / web se ek-ek karke main folders
     (`app/`, `gradle/`, `.github/`) upload karo, phir root files
     (`gradlew`, `gradlew.bat`, `settings.gradle.kts`, `build.gradle.kts`,
     `gradle.properties`, `README.md`, `PHONE-BUILD.md`, `.gitignore`).
5. Commit karo. Pehla commit khud hi workflow trigger kar dega
   (kyunki `push` par bhi run hota hai).

### Har build ke liye
1. Repo kholo -> **Actions** tab -> **"Android Build (APK + Unit Tests)"** -> **Run workflow**.
2. 3-6 minute wait karo. Green tick = success.
3. Usi run page par neeche **Artifacts** me `app-debug-apk` milega.
   Us par tap karke ZIP download karo, extract karo, `app-debug.apk` milega.
4. Phone me APK par tap karo -> **Install** -> agar "Unknown sources"
   puche to Allow karo. Ye debug APK hai, Play Store ka nahi.

### Agar build fail ho
1. Usi run me failed step par tap karo, error ka screenshot / text copy karo.
2. Mujhe wo error bhejo — main source fix karke naya ZIP dunga.

## Option B — Agar GitHub use nahi karna
Kisi dost / cyber cafe ke PC par Android Studio me ye ZIP kholo aur
`Run` dabao. Iske alawa phone par seedha APK banana practical nahi hai:
AIDE / Termux jaise phone tools is project (Compose + Room + ML Kit +
Gradle 8.7 + SDK 34) ko reliably build nahi kar paate.

## Important notes
- Ye debug APK hai — testing ke liye. Play Store par dalne ke liye
  signed release build chahiye (wo alag step hai).
- Pehli baar APK install karne par Location permission maangega — Allow karo,
  warna route start location ke liye manual start use hoga.
- Nominatim ka User-Agent placeholder (`NominatimGeocoder.kt` me) apne
  real contact se badlo, warna sustained use par block ho sakta hai.
