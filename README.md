# Auto World Backup

Minecraft Forge 1.7.10向けの、ワールドを定期的にZIPへ保存するサーバー専用MODです。

[ダウンロード](https://github.com/hachiko-tokkai/AutoWorldBackup-1.7.10/releases/latest) · [不具合報告](https://github.com/hachiko-tokkai/AutoWorldBackup-1.7.10/issues)

## 概要

サーバーのワールドを設定した間隔でバックアップし、古いバックアップを指定した世代数まで整理します。
サーバーだけに導入するため、参加するプレイヤーのクライアントへ入れる必要はありません。

## 対応環境

| 必要なもの | バージョン |
|---|---|
| Minecraft | 1.7.10 |
| Minecraft Forge | 10.13.4.1614 |

## 注意事項

- バックアップ先には、ZIPを保存するための十分な空き容量が必要です。
- バックアップ先をワールドフォルダーの内側に設定することはできません。
- 保持世代数を超えた古いZIPは削除されます。長期保存するZIPは別の場所へ退避してください。
- 対象は`server.properties`の`level-name`に対応するワールドフォルダー全体です。サーバー設定やMODのJARを含むサーバー全体のバックアップではありません。

## 導入方法

1. サーバーを停止します。
2. [リリースページ](https://github.com/hachiko-tokkai/AutoWorldBackup-1.7.10/releases/latest)から最新版のJARをダウンロードします。
3. サーバーの`mods`フォルダーへ入れます。
4. サーバーを起動します。

同じMODの古いJARがある場合は、取り除いてから新しいJARを入れてください。

## 主な機能

| 機能 | 内容 |
|---|---|
| 定期バックアップ | 設定した間隔でワールドをZIPへ保存 |
| 保存の制御 | 作成直前に全ワールドを保存し、ZIP作成中は保存を一時停止 |
| 保存の再開 | バックアップの完了または失敗後に保存を再開 |
| 世代管理 | 古いZIPを削除し、設定した世代数を保持 |
| 手動実行・状態確認 | コマンドからバックアップを要求し、状態を確認 |

## 設定

設定ファイルは`config/autoworldbackup.cfg`です。サーバーを停止してから編集してください。

| 項目 | 内容 | 初期値 |
|---|---|---|
| `backupDirectory` | バックアップ先。サーバールートからの相対パス、または絶対パス | `backups` |
| `intervalMinutes` | 実行間隔（分） | 60 |
| `initialDelayMinutes` | サーバー起動後、最初の実行までの時間（分） | 5 |
| `retentionCount` | 保持するZIPの最大数 | 24 |

## コマンド

サーバーコンソール、または権限レベル4のプレイヤーから実行できます。

| コマンド | 内容 |
|---|---|
| `/autobackup now` | 手動バックアップを要求 |
| `/autobackup status` | バックアップの状態を確認 |

## 復元方法

1. サーバーを停止します。
2. 現在のワールドフォルダーを別の場所へ退避します。
3. 復元するZIPをサーバールートへ展開します。
4. ワールドフォルダー名が`server.properties`の`level-name`と一致することを確認します。
5. サーバーを起動します。

## 無効化・削除

サーバーを停止し、`mods`から本MODのJARを取り除いてください。
作成済みのバックアップZIPは、必要に応じて保管してください。

## 不具合報告

[Issues](https://github.com/hachiko-tokkai/AutoWorldBackup-1.7.10/issues)に、Minecraft・Forgeのバージョン、設定内容、再現手順、サーバーログを記載してください。
ログや設定ファイルを添付する際は、公開したくない接続情報などを取り除いてください。

## 開発

Java 8を使用し、リポジトリのルートで実行します。

```powershell
.\gradlew.bat build
```

生成先は`build/libs/AutoWorldBackup-1.0.0.jar`です。

## 生成AIの利用

設計、コード生成・修正、文書作成にOpenAI Codexを使用しています。

## ライセンス・免責事項

本プロジェクトのソースコードとドキュメントには[MIT License](LICENSE)を適用しています。

Copyright (c) 2026 hachiko-tokkai

本MODは現状のまま提供します。動作・互換性・安全性を保証せず、使用に伴う不具合や損害について、作者は適用法令で認められる範囲において責任を負いません。詳細はLICENSEを確認してください。
