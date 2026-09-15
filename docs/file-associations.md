# ファイルの関連付け

SettingsScreen最下部の「ファイルの関連付け」→「設定」から実行する。
表示・実行中状態・結果ダイアログはcommonMainで共有し、OSへの登録はdesktopApp側に置く。
Androidホストは端末での選択手順を返す。

## OSごとの動作

- Windows: 現在のユーザーのHKCUに`.mid` / `.midi`のProgID、OpenWithProgids、Capabilities、RegisteredApplicationsを登録する。引用符付きの起動コマンドでファイルを既存の起動引数処理へ渡す。Shellに関連付けの変更を通知してキャッシュを更新し、その後`ms-settings:defaultapps?registeredAppUser=...`を開く。Windowsの画面でユーザーが既定に設定する必要がある。アプリ固有画面への直接遷移は更新済みWindows 11で対応し、Windows 10では既定のアプリ画面から拡張子を選ぶ。UserChoiceや既存アプリのProgIDは書き換えない。
- Linux: `$XDG_DATA_HOME/applications/project2by2-midiplayer.desktop`（未指定時は`~/.local/share/applications`）に現在のランチャーを登録する。`Exec`の引数はシェルを使わず引用・エスケープし、`%F`でファイルを渡す。`xdg-mime default`でMIDIのMIME型を関連付け、GNOME/Cinnamonではファイル管理アプリと同じGIOの`gio mime`で全対象の結果を確認する。その他の環境やgioが未導入の場合は`xdg-mime query default`を使用する。xdg-utilsとデスクトップセッションが必要で、環境のポリシーによって失敗する場合はエラーを表示する。
- Android: 既存のMIDI用ACTION_VIEWフィルターを使用する。ファイル管理アプリでMIDIを開き、このプレイヤーと「常時」を選ぶ手順、および既存の既定設定を解除する手順をダイアログで案内する。「常時」の提供や表示は端末・呼び出し元による。URL用の「対応リンクを開く」設定をMIDIファイルの既定設定として扱わない。

デスクトップではjpackageの`jpackage.app-path`が示す実ファイルのみ登録する。
Gradleのrun等からの起動では配布版からの実行を案内し、Javaランチャーを登録しない。
ポータブル版は登録後に移動すると関連付けが無効になるため、移動先から再設定する。
WindowsのEXEには隣接するapp/runtimeディレクトリが必要。

GNOME/Cinnamonで`xdg-mime query default`だけに依存しないのは、xdg-utils 1.1.3が引用符付きExecの実行ファイルを正しく認識できず、既定設定を読み飛ばすため。GIOの出力は英語に固定して既定アプリの行のみを判定し、候補一覧にプレイヤーがあるだけでは成功にしない。

## 検証

`DesktopFileAssociationsTest`ではOSを変更せず、ユーザー単位のWindows登録、起動コマンドの引用、登録失敗時の中断、Linuxの登録・結果確認・パスのエスケープ、開発起動時の無変更を確認する。

実機確認:

1. 各OSで設定の最下部、左右の文言・ボタン、結果ダイアログを確認する。
2. Windowsでは配布版から登録し、OS画面で既定に設定した後、空白・日本語を含むパスの.midをダブルクリックする。
3. Linuxでは設定後に.midをファイル管理アプリから開く。既に起動中の場合も既存ウィンドウで再生することを確認する。
4. Androidではファイル管理アプリから開き、端末が提供する場合は「常時」を選び、次回の起動を確認する。

## 仕様の参照先

- [Microsoft: Default Apps設定画面の起動](https://learn.microsoft.com/en-us/windows/apps/develop/launch/launch-default-apps-settings)
- [Microsoft: Default Programsの登録](https://learn.microsoft.com/en-us/windows/win32/shell/default-programs)
- [freedesktop.org: xdg-mime](https://portland.freedesktop.org/doc/xdg-mime.html)
- [freedesktop.org: Desktop EntryのExec構文](https://specifications.freedesktop.org/desktop-entry/latest/exec-variables.html)
- [Android: Intents and intent filters](https://developer.android.com/guide/components/intents-filters)
- [Microsoft: ファイル種別の登録とShellへの通知](https://learn.microsoft.com/en-us/windows/win32/shell/fa-file-types)
- [GNOME: ユーザーの既定アプリ設定とgioによる確認](https://help.gnome.org/system-admin-guide/mime-types-application-user.html)
