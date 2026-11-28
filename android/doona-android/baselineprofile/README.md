# baselineprofile

Records the app's baseline profile on a phone (API 33 or later, unlocked, screen on, gesture navigation) from the
profile build's journeys in `BaselineProfileGenerator`, and writes it to `app/src/main/generated/baselineProfiles/`,
which release and profile both ship. Commit the changed files.

    ./gradlew :app:generateBaselineProfile -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true

The property keeps the profile build installed after the run, with its data.

The generator uses Traditional Chinese for the profile build and restores its prior app language after each test.
