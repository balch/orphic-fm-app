# Store publishing secrets

Holds the Google Play service-account key and the App Store Connect `.p8` API keys
(`AuthKey_<KEYID>.p8`, used by the `release-djapp` skill). Everything here except this
README and `.gitignore` is ignored — **never commit a key**.

Drop the Play key as `play-service-account.json` in EITHER location (the build
checks the repo-root one first):

    .secrets/play-service-account.json                               (repo root)
    apps/djapp/store-listings/.secrets/play-service-account.json     (here)

This is the key for `owner-830@orphic-dj.iam.gserviceaccount.com` (GCP project
`orphic-dj`).

Used by Gradle Play Publisher (configured in
`apps/djapp/androidApp/build.gradle.kts`). To publish:

    ./gradlew :apps:djapp:androidApp:publishReleaseBundle

CI alternative: set the `ANDROID_PUBLISHER_CREDENTIALS` env var to the key's
contents instead of placing the file here.

## Prerequisites (one-time, in the account that owns the `orphic-dj` GCP project)

1. Enable the **Google Play Android Developer API** for project `orphic-dj`.
2. Create + download a **JSON key** for the service account → save as
   `play-service-account.json` in this folder.
3. In the Play Console (owner `balch61@gmail.com`): **Users & permissions →
   Invite new users →** add the service-account email → grant **Release to
   testing tracks** (and *Release to production* if desired). *(Done.)*
