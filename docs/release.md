# リリース手順

paperless-android を公開するときの手順です。開発は Gitea で行い、GitHub
（[naoya0117/paperless-android](https://github.com/naoya0117/paperless-android)）はミラーです。
F-Droid は GitHub のミラーからソースを取得してビルドします。

1. [署名鍵](#1-署名鍵)（初回のみ）
2. [バージョンと変更履歴](#2-バージョンと変更履歴)
3. [リリースビルド](#3-リリースビルド)
4. [実機での確認](#4-実機での確認)
5. [スクリーンショット](#5-スクリーンショット)（画面が大きく変わったとき）
6. [タグと公開](#6-タグと公開)
7. [F-Droid への申請](#7-f-droid-への申請)（初回のみ。以降はタグから自動で更新）

## 1. 署名鍵

GitHub のリリースに添付する APK に、自分の鍵で署名するために使います。F-Droid で配布する APK は
F-Droid の鍵で署名されます（[7](#7-f-droid-への申請) を参照）。

**鍵をなくすと、同じアプリとして更新を配布できなくなります。** 漏れると第三者が更新を偽装できます。

### 作成

```bash
mkdir -p ~/keys && chmod 700 ~/keys
keytool -genkeypair -v \
  -keystore ~/keys/paperless-android-release.jks \
  -alias paperless-android \
  -keyalg RSA -keysize 4096 -validity 36500 \
  -dname "CN=Naoya Matsuhashi"
chmod 600 ~/keys/paperless-android-release.jks
```

- パスワードは長くランダムなものにし、パスワードマネージャーに保存します。
- 鍵ストアの形式は PKCS12 になる（JDK 9 以降の既定。拡張子が `.jks` でも同じ）ため、鍵のパスワードは
  ストアのパスワードと同じになります（keytool も別に尋ねません）。
- 鍵ファイルは**リポジトリの外**に置きます（`.jks` / `.keystore` をコミットしない）。

### バックアップ

- 鍵ファイルとパスワードを、別々の場所に保存します（例：鍵ファイルは暗号化した USB メモリとクラウドの暗号化領域の 2 か所、
  パスワードはパスワードマネージャー）。
- バックアップから復元できることを、一度 `keytool -list` で確認します。

### 証明書のフィンガープリント

```bash
keytool -list -v -keystore ~/keys/paperless-android-release.jks -alias paperless-android | grep SHA256
```

利用者が APK を確認できるよう、SHA-256 のフィンガープリントを GitHub のリリースノートに記載します。

### Gradle への設定

署名の設定は、すべて揃っているときだけ有効になります（`app/build.gradle.kts`）。揃っていなければ、
署名なしの APK になります（F-Droid のビルドはこちら）。`~/.gradle/gradle.properties`（リポジトリの外）に書きます。

```properties
paperless.signing.storeFile=/Users/naoya/keys/paperless-android-release.jks
paperless.signing.storePassword=...
paperless.signing.keyAlias=paperless-android
paperless.signing.keyPassword=...   # storePassword と同じ値
```

ファイルに書かない場合は、環境変数でも渡せます。名前にドットを含むため `export` では設定できず、`env` を使います。

```bash
env 'ORG_GRADLE_PROJECT_paperless.signing.storePassword=…' 'ORG_GRADLE_PROJECT_paperless.signing.keyPassword=…' \
  ./gradlew :app:assembleRelease
```

`-Ppaperless.signing.storePassword=…` でも渡せますが、コマンドの履歴やプロセスの一覧にパスワードが残ります。

## 2. バージョンと変更履歴

- バージョンは `gradle.properties` の `paperless.versionName`（SemVer の `MAJOR.MINOR.PATCH`）と
  `paperless.versionCode` です。versionCode は `major * 10000 + minor * 100 + patch`（0.1.0 → 100、1.2.3 → 10203）
  にします。両方を書き換え、一致しなければビルドが失敗します。
- `CHANGELOG.md` の `## [Unreleased]` を `## [x.y.z] - YYYY-MM-DD` にし、空の `## [Unreleased]` を上に追加します。
  末尾に比較リンク（`[x.y.z]: https://…/compare/…`）を置く場合はそれも更新します。
- F-Droid 用の変更履歴 `fastlane/metadata/android/{en-US,ja-JP}/changelogs/<versionCode>.txt` を追加します
  （500 文字以内）。
- 以上を Issue → PR で main にマージします。

## 3. リリースビルド

```bash
./gradlew clean :app:assembleRelease
```

| 署名の設定 | 出力 |
|---|---|
| あり | `app/build/outputs/apk/release/app-release.apk` |
| なし | `app/build/outputs/apk/release/app-release-unsigned.apk` |

確認：

```bash
BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
# 署名とフィンガープリント（1 で控えたものと一致すること）
"$BT"/apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
# バージョン
"$BT"/aapt2 dump badging app/build/outputs/apk/release/app-release.apk | head -1
```

- `package: name='com.naoya0117.paperless' versionCode='…' versionName='…'` で、debug の接尾辞（`.debug` / `-debug`）がないこと。
- apksigner はフィンガープリントを小文字・コロンなしで、keytool は大文字・コロン区切りで表示します。比べるときは書式を揃えます。
- 依存関係の情報（Google の鍵で暗号化されたブロック）は含めない設定です（`dependenciesInfo`）。F-Droid がこの無効化を求めています。

## 4. 実機での確認

リリースビルドは R8 で縮小・難読化されるため、debug では動くのにリリースで壊れることがあります
（シリアライズ、Retrofit、Room、Hilt、WorkManager など）。**リリースの APK** で、少なくとも
Android 10（API 29）の端末と最新の Android の端末で確認します。

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb logcat -s AndroidRuntime:E   # クラッシュの監視
```

debug 版（`com.naoya0117.paperless.debug`）とは別のアプリとして入ります。

### ログインとアカウント

- [ ] ユーザー名とパスワードでログインできる
- [ ] 2 要素認証のコードでログインできる
- [ ] トークンでログインできる
- [ ] サブパスに置いたサーバー（`https://example.com/paperless`）にログインできる
- [ ] HTTP のサーバーで警告が出る
- [ ] カスタムヘッダー（例：リバースプロキシの認証）付きで接続できる
- [ ] クライアント証明書（KeyChain から選択、mTLS）で接続できる
- [ ] 追加の信頼する証明書（自己署名・プライベート CA）で接続できる
- [ ] 2 つ目のアカウントを追加し、切り替えとログアウトができる
- [ ] アプリを再起動してもログイン状態が残る

### ホームと一覧

- [ ] ホームにダッシュボードの保存済みビューが出る
- [ ] 文書の一覧がスクロールで読み込まれ、サムネイルが出る
- [ ] 検索欄をタップしたときだけキーボードが出る
- [ ] 全文検索・並び替え・絞り込み（タグ・取引先・文書の種類・保存先・日付）ができる
- [ ] 行の長押しでメニューが出て、共有・開く・ダウンロード・削除ができる
- [ ] 機内モードにしても、読み込み済みの一覧が表示される

### ビューアーと編集

- [ ] PDF と画像が開き、ピンチで拡大できる
- [ ] タップで全画面表示になり、下にスクロールするとバーが隠れる
- [ ] ⋮ メニューから文書内を検索でき、該当箇所が強調される
- [ ] 情報の表示、タイトル・タグ・カスタムフィールドなどの編集と保存ができる
- [ ] メモの追加・削除ができる
- [ ] 共有・他のアプリで開く・ダウンロード（通知が出る）ができる
- [ ] 削除した文書がごみ箱に入り、復元・完全に削除ができる

### アップロード

- [ ] ファイルを選んでアップロードでき、進行状況とその完了が表示される
- [ ] 他のアプリの共有メニューから送ってアップロードできる
- [ ] カメラの権限を求められ、拒否しても落ちない
- [ ] カメラで複数ページを撮影し、回転・4 隅のトリミングをして 1 つの PDF としてアップロードできる
- [ ] アップロード中にアプリを閉じても、バックグラウンドで完了する（通知）

### ラベルとその他

- [ ] タグ・取引先・文書の種類・保存先の一覧、作成・編集・削除ができる
- [ ] 「このアプリについて」にバージョン（`-debug` なし）とライブラリのライセンスが表示される
- [ ] 戻るボタンと端からのスワイプで、同じアニメーションで戻る
- [ ] 画面の回転、ダークテーマ、文字サイズを大きくしたときに崩れない
- [ ] 古いバージョンを入れた状態から更新して、データが残る（2 回目以降のリリース）

## 5. スクリーンショット

F-Droid とリポジトリで使うスクリーンショットです。個人の文書が写らないよう、
[`tools/paperless-dev`](../tools/paperless-dev/README.md) のテスト用サーバーにサンプルの文書を入れて撮影します。

保存先：`fastlane/metadata/android/en-US/images/phoneScreenshots/1.png`、`2.png` …（番号順に表示されます。
日本語の画面は `ja-JP/images/phoneScreenshots/` に置きます。置かなければ英語のものが使われます）。

| # | 画面 | 内容 |
|---|---|---|
| 1 | ホーム | 保存済みビューが 2〜3 個並んだ状態 |
| 2 | 文書の一覧 | 検索語とフィルターのチップが入った状態 |
| 3 | ビューアー | PDF を表示し、文書内検索で強調した状態 |
| 4 | 情報・編集 | タグとカスタムフィールドが入った文書 |
| 5 | スキャン | 4 隅のトリミングの画面 |
| 6 | ラベル | タグの一覧（色つき） |

ステータスバーをデモモードにして時刻・電池などを揃えます。

```bash
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1000
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false

adb exec-out screencap -p > fastlane/metadata/android/en-US/images/phoneScreenshots/1.png

adb shell am broadcast -a com.android.systemui.demo -e command exit
```

- 端末の言語を英語にして撮影します（日本語版は日本語で撮影）。
- ライトテーマで、同じ端末・同じ解像度で揃えます。

## 6. タグと公開

[4](#4-実機での確認) の確認が済んでから行います。その前に、次を確かめます。

- [ ] main で `./gradlew build`（テスト・Lint・ktlint・ライセンス表示の検査）が通る（CI の main の結果でもよい）
- [ ] `gradle.properties` の `paperless.versionName` がタグ名（`v` を除く）と一致する
- [ ] `gradle/gradle-daemon-jvm.properties` がリポジトリにない（ホストの Android Studio が書き込むことがある。
  JDK 25 を要求するため、F-Droid のビルドサーバーで失敗する）

タグはミラーにも公開され、F-Droid の更新のきっかけになるため、
**付けたタグは動かしません**（間違えたら次のパッチバージョンを出します）。

```bash
git switch main && git pull --ff-only
git tag -a v0.1.0 -m "paperless-android 0.1.0"
git push origin v0.1.0
```

- GitHub のミラーへ、main とタグを手動で push します（`git push <github のリモート> main v0.1.0`）。
- GitHub でリリースを作り、署名した APK（`paperless-android-0.1.0.apk` に名前を変える）、
  `CHANGELOG.md` の該当部分、証明書の SHA-256 フィンガープリント、APK ファイルの SHA-256（`sha256sum`）を載せます。

## 7. F-Droid への申請

F-Droid は自前のサーバーでソースからビルドします。署名は、**初回の申請時に**次のどちらかに決めます
（後から変えると、利用者にアンインストールと入れ直しを強いることになります）。

- **F-Droid の鍵で署名する**（既定）：申請が簡単です。GitHub のリリースの APK とは署名が異なり、
  互いに上書き更新できません（README に記載する）。
- **作者の鍵で署名した APK をそのまま配布する**（再現可能なビルド）：F-Droid がビルドした APK と、作者が署名した APK
  の中身が一致することを F-Droid が検証し、作者の APK を配布します。レシピに `Binaries:`（GitHub のリリースの APK の URL）
  と `AllowedAPKSigningKeys:`（証明書の SHA-256、小文字・コロンなし）を書きます。GitHub 版と F-Droid 版の署名が揃います。
  このプロジェクトは再現可能なビルドのための設定（`vcsInfo.include = false` など）をしてありますが、
  JDK やビルド環境の違いで一致しないことがあるため、事前に `fdroid build` の結果と比べて確かめます。

### 事前の確認

- [ ] 依存関係がすべて自由なソフトウェアで、Google Play Services / Firebase などを含まない
- [ ] `./gradlew :app:assembleRelease` が署名の設定なしで通る
- [ ] `fastlane/metadata/android/` のタイトル・説明・アイコン・スクリーンショット・変更履歴が揃っている
- [ ] 反機能（Anti-Features）：なし。ユーザーが指定したサーバーとだけ通信し、追跡はない
- [ ] versionCode を `gradle.properties` から読み取れる（下記）

### versionCode について

F-Droid の更新チェック（`UpdateCheckMode: Tags`）は、タグの時点の `build.gradle(.kts)` から versionCode と
versionName を読み取ろうとします。このプロジェクトは versionCode を versionName から計算しているため、そのままでは
読み取れません。そのため `gradle.properties` に `paperless.versionCode` も書き（ビルドで計算した値と一致することを検証する）、
更新チェックは `UpdateCheckData` で `gradle.properties` から両方を読みます（形式は `ファイル|正規表現|ファイル|正規表現`。
3 つ目の `.` は 1 つ目と同じファイルの意味。正規表現に `|` は使えません）。

自動で更新しない場合は `AutoUpdateMode: None` とし、リリースごとに fdroiddata にマージリクエストを出します。

### レシピの下書き

[fdroiddata](https://gitlab.com/fdroid/fdroiddata) を fork し、`metadata/com.naoya0117.paperless.yml` を追加して
マージリクエストを出します（`fdroid readmeta` / `fdroid lint` / `fdroid build -v -l com.naoya0117.paperless` で
事前に確認できます）。

```yaml
Categories:
  - Cloud Storage & File Sync
License: GPL-3.0-or-later
AuthorName: Naoya Matsuhashi
SourceCode: https://github.com/naoya0117/paperless-android
IssueTracker: https://github.com/naoya0117/paperless-android/issues
Changelog: https://github.com/naoya0117/paperless-android/blob/HEAD/CHANGELOG.md

AutoName: paperless-android

RepoType: git
Repo: https://github.com/naoya0117/paperless-android.git

Builds:
  - versionName: 0.1.0
    versionCode: 100
    commit: v0.1.0
    subdir: app
    gradle:
      - yes

AutoUpdateMode: Version
UpdateCheckMode: Tags
UpdateCheckData: gradle.properties|paperless\.versionCode=(\d+)|.|paperless\.versionName=(.+)
CurrentVersion: 0.1.0
CurrentVersionCode: 100
```

- 説明・スクリーンショットはリポジトリの `fastlane/metadata` から自動で取り込まれるため、レシピには書きません。
- `IssueTracker` は GitHub の Issue を受け付ける場合に記載します（受け付けない場合は省略）。
- ビルドに JDK 17 以上が必要です（Android SDK の platform はビルド時に自動で入ります）。ビルドサーバーの JDK が
  合わない場合は、マージリクエストのレビューで `sudo` による追加を相談します。
