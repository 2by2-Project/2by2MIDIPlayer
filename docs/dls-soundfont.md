# DLS サウンドフォントの読み込み

サウンドフォント選択で `.dls` を選ぶと、DLS → SF2変換 → 一時ファイル保存 →
BASSMIDI読み込みを行う。拡張子に加えてRIFFの `DLS ` ヘッダーも確認する。
通常のSF2/SF3（デスクトップではSFZも）の経路は引き続き利用できる。

## 構成

- `shared/src/jvmMain/.../soundfont/DlsConverter.kt`: Android/Windows/Linuxで同じKotlin変換コード。
  波形データは64KiBずつ読み書きする。OS API、外部プロセス、追加ネイティブライブラリは不要。
- `SoundFontFiles.kt`: 変換が必要な場合だけ一意の `.sf2` 一時ファイルを作成。
  失敗・キャンセル時には途中ファイルを削除し、入力ファイルは変更しない。
- `shared/src/commonMain/.../ui/settings/SoundFontLoadingDialog.kt`: 300msを超えた処理にだけ共通モーダルを表示。
  ファイルコピー・変換・フォント検証中はUIスレッドを塞がず、読み込みの重複操作を防止する。

AndroidではSAFの入力をアプリのcacheDirへコピーし、変換後のフォントをBASSで検証してから
既存の `cacheDir/soundfont.sf2` を原子的に置き換える。再生中の2つのデコーダーにも新しい音源を設定する。
途中で失敗した場合は旧フォントに戻す。新しいフォント名は成功後に既存DataStoreへ保存する。
Androidの従来のキャッシュ消去時の挙動は同じで、消去された場合はフォントの再選択が必要。

Windows/LinuxではBASSが使用中の一時ファイルを保持し、フォント交換・アプリ終了で解放後に削除する。
設定には元のDLSパスを保存するので、次回起動時に再変換できる。一時ファイルの削除だけでは設定を失わない。
元のDLSを移動・削除した場合は再選択が必要。

## 対応範囲と参照元

foo_midiの `Infrastructure/InputDecoderSoundfonts.cpp` と、そこから使われる
[stuerp/libsf](https://github.com/stuerp/libsf) の変換規則を参照。
参照コミットは `3f86f15723d363c191e50ce6d0abe70fc902abd1`。
MITライセンス表記は `THIRD_PARTY_NOTICES.md` に収録。

libsfのC++コードはWindowsヘッダー、libmsc、Windows日時APIなどに依存しているため、
そのままサブモジュール追加して呼ぶ構成にはしていない。移植した変換処理をJVM共通ソースで共有する。

対応する波形はモノラルPCM 8/16bitとA-law 8bit。プリセット、ドラムバンク128、MSB優先/LSB代替バンク、
鍵盤・ベロシティ範囲、排他グループ、ループ/リリースループ、ルートキー、チューニング、ゲイン、
主要なDLS1/DLS2アーティキュレーション（音量/変調エンベロープ、LFO、フィルター、パン、エフェクト、MIDIコントローラー）を変換する。
SF2で直接表現できない接続はlibsf同様、完全再現を保証しない。
ステレオ・マルチチャンネルリンク・PCM24・ADPCM等の波形や複数ループは明示的なエラーにする。

libsfからの調整点として、wave poolのcueを波形の順序と見なさず実際のオフセットで解決し、
regionのwsmpが無ければwaveの設定を継承する。region独自ループにも対応し、
SF2が要求する46サンプルのゼロガードを各波形の後に出力する。

## 検証

```powershell
.\gradlew.bat :shared:desktopTest :desktopApp:test :app:testDebugUnitTest :app:assembleDebug
```

`DlsConverterTest` は生成したDLSでcue順序、ドラム、範囲、ループ上書き/継承、チューニング、
PCM8/A-law、破損/未対応データ、キャンセル、一時ファイル所有権を確認する。
`NativeDlsTest` は一時SF2を同梱BASSMIDIに読み込ませ、MIDIから非ゼロのPCMが出ることを検証する。
Windowsでは既存の `System32/drivers/gm.dls` があれば追加検証する（音源自体は配布しない）。
`DesktopDlsImportTest` は選択から読み込み、失敗時の旧フォント保持、終了時の一時SF2削除、
再起動時の再変換までを実際のコントローラーで確認する。
GUI操作と実際の聞こえ方はユーザーによる実機確認の対象。
