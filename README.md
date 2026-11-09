# paperless-mobile (Kotlin)

English | [日本語](README.ja.md)

An Android client for [Paperless-ngx](https://github.com/paperless-ngx/paperless-ngx), written in Kotlin.

## Requirements

- **Android 10 (API level 29) or later**
- **Paperless-ngx 3.0.0 or later** (REST API version 10)
- To connect over HTTPS, the server certificate must be trusted by Android.
  To use a certificate issued by a private CA, install the CA certificate on the device.

## Goals

- Support the **latest Paperless-ngx REST API** (API version 10)
- Implement the core features first: authentication, documents, tags / correspondents / document types / storage paths / custom fields, saved views, upload, and tasks
- Planned extensions:
  - Local caching for offline use
  - Custom request headers (e.g. for reverse proxies or access gateways)
  - Client certificate authentication (mTLS)
  - Multiple accounts

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
