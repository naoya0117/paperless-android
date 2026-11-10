# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- README in English and Japanese, including acknowledgement of [paperless-mobile](https://github.com/astubenbord/paperless-mobile)
- License: GNU General Public License v3.0 or later
- This changelog
- Gradle project skeleton: `:app`, `:core:model`, `:core:network`, `:core:data`, and shared build logic
- Network security config: cleartext HTTP permitted, user-installed CA certificates trusted
- App data excluded from cloud backup and device-to-device transfer
- Network layer (`:core:network`) for the Paperless-ngx REST API v10
  - Sign in with username/password (including two-factor authentication codes) or an existing token
  - API version negotiation based on the server's `X-Api-Version` header
  - Server URL normalization, including installations under a sub path
  - Custom request headers and client certificates (mutual TLS); credentials are only sent to the configured server
  - Typed errors for HTTP, login, and network failures
- Label APIs: tags (with hierarchy), correspondents, document types, storage paths and custom fields
  - List (paged or all), get, create, update and delete
- Documents API: list with filters (tags, correspondent, document type, storage path, dates, ASN, inbox,
  custom field query), full-text search (text, title, advanced query, similar documents), get, update, delete
- Document files (thumbnail, preview, download, original) streamed with file name and type; notes;
  metadata, suggestions, history and the next archive serial number
- Document upload with metadata and progress; task list, lookup by task ID, waiting for completion, acknowledging
- Saved views: CRUD, conversion of their filter rules to a document query like the web UI, dashboard/sidebar visibility
- Bulk operations (correspondent, document type, storage path, tags, custom fields, delete, reprocess) and the trash
- Accounts: several accounts per device, switching, sign-out; tokens and custom header values encrypted with the
  Android Keystore
- Per-account TLS settings: a client certificate from the Android KeyChain (mutual TLS) and certificates
  (PEM or DER) trusted in addition to the device's trust store, e.g. a private CA or a self-signed certificate
