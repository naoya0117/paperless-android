# paperless-android

English | [日本語](README.ja.md)

An Android client for [Paperless-ngx](https://github.com/paperless-ngx/paperless-ngx), written in Kotlin.

> **About this repository:** the repository on GitHub
> ([naoya0117/paperless-android](https://github.com/naoya0117/paperless-android)) is a **mirror**.
> Development (issues, reviews and CI) takes place on the author's own Gitea server, and the changes are pushed to
> GitHub from there.

## Requirements

- **Android 10 (API level 29) or later**
- **Paperless-ngx 3.0.0 or later** (REST API version 10)
- The Paperless-ngx user needs the **UI settings view permission** (`view_uisettings`), which the app uses to validate the sign-in
- To connect over HTTPS, the server certificate must be trusted by Android.
  To use a certificate issued by a private CA, install the CA certificate on the device.

## Features

- **Documents**: home screen with the saved views of the dashboard; document lists with large thumbnails, available
  offline; inbox; trash with restore; full-text search with sorting and filters (tags, correspondent, document type, storage path, created date)
- **Viewer**: PDFs and images, full screen, pinch to zoom, search within the document; details with notes and
  custom fields; editing; share, open in another app, download, delete
- **Adding documents**: scanning with the camera (several pages, cropped to their corners and enhanced, as one PDF);
  uploading files or sharing them from other apps, several images as one document; uploads queued and sent in the
  background
- **Organizing**: tags, correspondents, document types and storage paths; saved views
- **Connections**: several accounts; password (with two-factor authentication) or API token; custom request
  headers, client certificates (mTLS) and private CA certificates

The app supports the current Paperless-ngx REST API (version 10) and talks only to your server.

## Building

Requires JDK 17 or later and the Android SDK (`ANDROID_HOME` or `local.properties`).

```bash
./gradlew assembleDebug   # debug APK (application ID suffix: .debug)
./gradlew build           # compile, unit tests, Android Lint, ktlint
```

The release APK is unsigned unless all of the following Gradle properties are set
(e.g. in `~/.gradle/gradle.properties`):

```properties
paperless.signing.storeFile=/path/to/release.jks
paperless.signing.storePassword=...
paperless.signing.keyAlias=...
paperless.signing.keyPassword=...
```

## Acknowledgements

This project is based on and inspired by the community app
[paperless-mobile](https://github.com/astubenbord/paperless-mobile) by Anton Stubenbord and contributors,
a Flutter client for Paperless-ngx. Its feature set and API usage are used as a reference for this Kotlin implementation.

Unlike the original, this project targets the current Paperless-ngx API
(e.g. paginated tasks, tag hierarchy, document versions, trash, and `custom_field_query`).

This is an independent project and is not affiliated with, or endorsed by, the paperless-mobile or Paperless-ngx projects.

## License

Copyright (C) 2026 Naoya Matsuhashi

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.

See [LICENSE](LICENSE) for the full text.

### Third-party notices

- [paperless-mobile](https://github.com/astubenbord/paperless-mobile) — GPL-3.0
- [Paperless-ngx](https://github.com/paperless-ngx/paperless-ngx) — GPL-3.0

The open source libraries in the app (Apache License 2.0; MIT and BSD 3-Clause for two of them), their licenses and notices are
listed in the app under **About** in the menu. The build fails if a dependency's license is not on the allowed list
(`aboutLibraries` in `app/build.gradle.kts`), or if a library with a NOTICE file is missing from
`app/src/main/res/raw/third_party_notices.txt`.
