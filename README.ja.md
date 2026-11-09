# paperless-mobile (Kotlin)

[English](README.md) | 日本語

Kotlin で実装した [Paperless-ngx](https://github.com/paperless-ngx/paperless-ngx) の Android クライアントです。

## 目標

- **最新の Paperless-ngx REST API**（API バージョン 10）への対応
- コア機能を先行して実装：認証、ドキュメント、タグ／担当者（correspondent）／文書タイプ／保存パス／カスタムフィールド、保存済みビュー、アップロード、タスク
- 今後の拡張予定：
  - オフライン利用のためのローカルキャッシュ
  - カスタムリクエストヘッダー（リバースプロキシやアクセスゲートウェイ向けなど）
  - クライアント証明書認証（mTLS）
  - 複数アカウント

## 謝辞

本プロジェクトは、Anton Stubenbord 氏およびコントリビューターによるコミュニティ製アプリ
[paperless-mobile](https://github.com/astubenbord/paperless-mobile)（Flutter 製の Paperless-ngx クライアント）を参考にしています。
機能構成や API の利用方法を、この Kotlin 実装のリファレンスとしています。

オリジナルとは異なり、本プロジェクトは現行の Paperless-ngx API
（タスクのページング、タグ階層、ドキュメントのバージョン管理、ゴミ箱、`custom_field_query` など）に対応することを目指しています。

本プロジェクトは独立したプロジェクトであり、paperless-mobile および Paperless-ngx プロジェクトとは提携・公認の関係にありません。

## ライセンス

Copyright (C) 2026 Naoya Matsuhashi

本プログラムはフリーソフトウェアです。フリーソフトウェア財団によって発行された
GNU 一般公衆利用許諾書（GNU General Public License）バージョン 3、
または（あなたの選択により）それ以降のバージョンが定める条件の下で、
再頒布および改変することができます。

本プログラムは有用であることを願って頒布されますが、**全くの無保証**です。
商品性や特定の目的への適合性に関する暗黙の保証もありません。
詳しくは GNU 一般公衆利用許諾書をご覧ください。

あなたは本プログラムと共に GNU 一般公衆利用許諾書の写しを受け取っているはずです。
受け取っていない場合は <https://www.gnu.org/licenses/> をご覧ください。

ライセンス全文は [LICENSE](LICENSE) を参照してください（法的に有効なのは英語の原文です）。

### サードパーティ

- [paperless-mobile](https://github.com/astubenbord/paperless-mobile) — GPL-3.0
- [Paperless-ngx](https://github.com/paperless-ngx/paperless-ngx) — GPL-3.0
