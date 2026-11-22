# ファイルの関連付け

## 役割分担

| 役割 | 担当 |
|---|---|
| MIDIを開けるアプリの候補として登録する | インストーラー（MSI / DEB / RPM） |
| 既定のアプリにする | ユーザー（OSの画面で選ぶ） |
| 設定画面の「ファイルの関連付け」→「設定」 | インストール済みか確認し、旧版の登録を掃除してから、OSの既定アプリ選択を開く／適用する |

インストーラーはインストール先のパスで登録し、アンインストールで消し、修復で直す。
アプリは自分のパスを書かないので、インストーラーを何回実行し直しても古い場所の登録が残らない。
インストーラーを使わずにapp imageを持ち運ぶ使い方（ポータブル）は、関連付けの対象外。設定ボタンは「インストーラーでインストールしたアプリから設定してください」と表示する。

## Windows

- MSIは`desktopApp/installer/windows/midi-associations.xml`の内容を常に入れる。チェックボックスや機能選択画面は無い。
  - 既定のアプリを奪わず、候補として並ぶだけなので、オプションにする必要がないため。
  - 登録先はHKLM（全ユーザー向けインストール）。
  - 登録するもの：ProgID `Project2by2.MIDI`、`.mid` / `.midi`の`OpenWithProgids`、Capabilities、`RegisteredApplications`（`2by2 MIDI Player`）、`Applications\2by2MIDIPlayer.exe`（表示名とSupportedTypes）。
  - スタートメニュー直下とデスクトップの`2by2 MIDI Player`ショートカットも、この断片で作る。jpackageに任せると、ショートカット名がEXE名の「2by2MIDIPlayer」になり、スタートメニューでは「Unknown」グループの下に入るため。
- インストーラーやアプリから既定のアプリにはできない。Windows 8以降、既定の選択（UserChoice）はハッシュで保護されていて、ユーザーがWindowsの画面で選ぶ必要がある。UserChoiceや拡張子自体の既定値は書かない。
- 設定ボタンの動き：
  1. HKLMの開くコマンドを読む。登録が無い、または起動中のEXEと違う場所を指している場合は、インストーラー版からの設定を案内する。
     - 読み取りはJNAで`RegGetValueW`を直接呼ぶ。`reg query`の出力はOEMコードページなので、日本語を含むパスが化けるため。
  2. 旧版がHKCUに書いた登録（ProgID、Capabilities、`RegisteredApplications`、`OpenWithProgids`の値）を消す。HKCUはHKLMより優先されるので、古いパスが残ると壊れるため。
  3. Shellに関連付けの変更を通知し、`ms-settings:defaultapps?registeredAppMachine=2by2%20MIDI%20Player`を開く。Windows 10では既定のアプリ画面から拡張子を選ぶ。
- ProgID名`Project2by2.MIDI`は変えない。既にWindowsで既定に選んでいるユーザーの設定は、ProgID名で結び付いているため。

## Linux

- DEB/RPMは`MimeType`付きのdesktopエントリを`/usr/share/applications`に置き、削除時に外す。IDは`project2by2-midiplayer-project2by2-midiplayer.desktop`。
  - jpackage標準の`xdg-desktop-menu install`は使わない。メニュー定義ディレクトリ（`/etc/xdg/menus`）が無い環境では失敗し、パッケージのインストール全体が止まるため。
  - DEBは`postinst`で置き、`prerm`で外す。RPMは更新時に古いパッケージの`%preun`が新しい`%post`の後に走るため、`%posttrans`で置き、`%preun`ではアンインストール時（`$1 = 0`）だけ外す。
  - いずれも`desktopApp/build.gradle.kts`の`packageInstallers`が、jpackageのテンプレートを書き換えて作る。
- 設定ボタンの動き：
  1. `XDG_DATA_DIRS`（未指定時は`/usr/local/share:/usr/share`）の`applications`からこのエントリを探し、`Exec`が起動中のランチャーを指すことを確認する。
  2. 旧版が書いた`~/.local/share/applications/project2by2-midiplayer.desktop`とアイコンを消す。メニューへの二重登録を解消するため。
  3. `xdg-mime default`でインストール済みIDをMIDIのMIME型の既定にする。
  4. GNOME/Cinnamonではファイル管理アプリと同じGIOの`gio mime`で、全対象の結果を確認する。その他の環境やgioが未導入の場合は`xdg-mime query default`を使う。
  - xdg-utilsとデスクトップセッションが必要。環境のポリシーで失敗した場合はエラーを表示する。
- GNOME/Cinnamonで`xdg-mime query default`だけに頼らないのは、xdg-utils 1.1.3が引用符付きExecの実行ファイルを正しく認識できず、既定設定を読み飛ばすため。GIOの出力は英語に固定して既定アプリの行だけを判定し、候補一覧にプレイヤーがあるだけでは成功にしない。

## Android

設定画面には出さない。
ファイル管理アプリでMIDIを開き、2by2 MIDI Playerと「常時」を選んでもらう。
アプリから既定にする手段は無く、AOSPの「前回選んだアプリ」の記録が更新されない不具合のため、選択画面を出して誘導する方式も安定しない。

## 名前

表示名はすべて「2by2 MIDI Player」、発行元は「2by2 Project」。
EXE名（`2by2MIDIPlayer.exe`）、インストール先フォルダ、Linuxのパッケージ名（`project2by2-midiplayer`）、ProgIDなどの内部の識別名は、更新時の置き換えを崩さないよう変えない。

## インストーラーの再実行

更新・修復・入れ直しは、どれもインストーラーをもう一回実行するだけで行える形にする。

- 関連付けとショートカットのコンポーネントは`DefaultFeature`に入れ、新しいFeatureは作らない。
- コンポーネントGUIDは固定値で、変更しない。
- 値は固定文字列と`[INSTALLDIR]`だけにし、カスタムアクションは使わない。
- CIでは、同じMSIを2回インストールして修復したあと、各回でレジストリとショートカットを確認する。最後にアンインストールし、すべて消えることを確認する。
- DEBも、2回インストールしてから削除まで確認する。

## 検証

`DesktopFileAssociationsTest`はOSを変更せずに確認する。
- 開発起動時に何もしないこと。
- 未登録や別の場所の登録を「利用不可」とすること。
- HKCUの旧登録を掃除してから既定アプリ画面を開くこと。
- Linuxでインストール済みエントリを使い、旧ユーザーエントリを消すこと。
- Execの解析と、既定設定の確認方法。

実機確認（ユーザー）：

1. Windowsで旧版を入れた状態から新MSIで置き換え、同じMSIをもう一度実行する。
   - アプリ一覧・スタートメニュー・デスクトップが「2by2 MIDI Player」1つずつになること。発行元が「2by2 Project」であること。
2. 設定ボタンで既定アプリ画面が開き、2by2 MIDI Playerを選べること。空白・日本語を含むパスの.midをダブルクリックして開くこと。
3. Windowsでアンインストール後、「プログラムから開く」から消えること。
4. LinuxでDEBを入れ、同じDEBをもう一度入れる。設定ボタンで既定になり、メニューに1つだけ表示されること。既に起動中の場合も、既存ウィンドウで再生すること。

## 仕様の参照先

- [Microsoft: Default Apps設定画面の起動](https://learn.microsoft.com/en-us/windows/apps/develop/launch/launch-default-apps-settings)
- [Microsoft: Default Programsの登録](https://learn.microsoft.com/en-us/windows/win32/shell/default-programs)
- [Microsoft: ファイル種別の登録とShellへの通知](https://learn.microsoft.com/en-us/windows/win32/shell/fa-file-types)
- [freedesktop.org: xdg-mime](https://portland.freedesktop.org/doc/xdg-mime.html)
- [freedesktop.org: Desktop EntryのExec構文](https://specifications.freedesktop.org/desktop-entry/latest/exec-variables.html)
- [GNOME: ユーザーの既定アプリ設定とgioによる確認](https://help.gnome.org/system-admin-guide/mime-types-application-user.html)
