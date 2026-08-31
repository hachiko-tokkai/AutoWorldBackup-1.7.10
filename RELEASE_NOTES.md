# Auto World Backup v1.0.0

Minecraft Forge 1.7.10専用サーバー向けの初回公開版です。

## 主な機能

- RCONや外部アプリを使わないサーバー内蔵型バックアップ
- バックアップ直前の全Dimension保存
- ZIP作成中のワールド保存一時停止と自動復帰
- バックアップ間隔、初回実行時間、保持世代、保存先の設定
- `/autobackup now` と `/autobackup status` コマンド
- クライアント側へのMod導入不要

## 動作環境

- Minecraft 1.7.10
- Forge 10.13.4.1614
- Java 8

## AI生成について

このリリースのソースコード、テスト、ドキュメントは OpenAI Codex によって生成されました。Java 8環境でのForgeビルド、ForgeサーバーでのMod読み込み、ZIP作成と世代管理の統合テストを実施しています。

Minecraftの実ワールドを使用した長期運用テストは実施していません。導入前に既存ワールドの別バックアップを作成してください。
