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
- Offline cache (Room) of tags, correspondents, document types, storage paths, custom fields and saved views per
  account, refreshed from the server; changes are applied to the cache after the server accepted them, and the
  cache of an account is deleted when signing out
- Document lists paged from the offline cache (Paging 3): shown at once, refreshed from the server and loaded
  further while scrolling; edited documents are updated and deleted ones removed in every cached list
- Upload queue (WorkManager): files are copied into app storage and sent when a network is available, with
  progress, the server's processing result (document, duplicate or error), retry and removal
- Disk cache of document files (thumbnails, previews, downloads) per account, refreshed when a document changes,
  limited in size (least recently used first) and deleted when signing out
- Sign-in logic for the login screen (`:feature:login`): server URL check with a warning for plain HTTP,
  password or token, two-factor authentication codes, custom headers, client certificate and trusted
  certificates, errors by kind, and signing in again with an account's settings
- Theme like the Paperless-ngx web interface: white or black surfaces with its green accent
- Login screen with advanced settings for custom
  headers, a client certificate from the KeyChain and trusted certificate files; English and Japanese
- The app shows the login screen without an account (or when the active account must sign in again)
- Main screen: bottom tabs (documents, search), an inbox button at the top right of the documents (with the
  number of documents to check; hidden when the inbox is empty) and a menu (account, sign-out); document lists
  with large thumbnails, tags, pull to refresh, infinite scrolling and offline use
- In-app viewer: PDF pages scrolled vertically (images too); tapping shows the document's details with an edit
  button
- Editing a document: title, date, correspondent, document type, storage path, tags and archive serial number,
  with the server's errors per field and a confirmation before discarding changes
- Uploads screen (from the menu): each upload's state, progress, errors, retry and removal; a finished upload
  opens its document. Files are added with a button on the documents tab or shared from other apps
- Viewer actions: share the document's file with another app, open it in another app, save it to Downloads,
  and delete the document (moved to the trash, after a confirmation)
- Label management (from the menu): tags, correspondents, document types and storage paths; search, create,
  edit (name, automatic matching, tag color, inbox tag and parent, storage path template) and delete
- Saved views in the menu: each opens its documents with the view's filter and sort order
- Several accounts: add one from the menu (a login that can be cancelled) and switch between them; an account
  that must sign in again can be left for another one
- Document details: notes (add and delete) and custom field values, shown by type (options, dates, money, ...)
