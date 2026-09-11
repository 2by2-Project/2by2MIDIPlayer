# MIDIファイルの関連付け

DEB/RPMのインストールでは「プログラムから開く」の候補へ登録します。
既定のアプリはユーザーごとの設定なので、パッケージのインストール時には変更しません。

インストール後、アプリケーションメニューのproject2by2-midiplayerを右クリックし、
「MIDIの既定のプレーヤーにする」を選択してください。
デスクトップ環境がこのメニューに対応していない場合は、MIDIファイルの
「プロパティ」→「プログラムから開く」でproject2by2-midiplayerを既定に設定できます。

端末から設定する場合（sudo不要）：

```sh
sh /opt/project2by2-midiplayer/lib/app/resources/integration/set-default-midi.sh
```

`xdg-utils`が必要です。変更を戻す場合はファイルマネージャーで別のプレーヤーを選択してください。
