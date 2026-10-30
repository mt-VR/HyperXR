<a id="top"></a>

<p align="center">
  <img src="branding/logo/hx_logo.png" alt="HyperMagic" width="360">
</p>

<h1 align="center">HyperMagic</h1>

<p align="center">
  <a href="https://github.com/mt-VR/HyperXR/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/mt-VR/HyperXR?label=release&color=14a5a0"></a>
  <a href="https://github.com/mt-VR/HyperXR/releases"><img alt="Downloads" src="https://img.shields.io/github/downloads/mt-VR/HyperXR/total?color=2f7cf6"></a>
  <a href="https://github.com/mt-VR/HyperXR/actions/workflows/release.yml"><img alt="Release build" src="https://img.shields.io/github/actions/workflow/status/mt-VR/HyperXR/release.yml?label=build"></a>
  <a href="LICENSE"><img alt="License: GPL-3.0" src="https://img.shields.io/badge/license-GPL--3.0-blue"></a>
  <br>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?logo=android&logoColor=white">
  <img alt="arm64 and armv7" src="https://img.shields.io/badge/ABI-arm64%20%7C%20armv7-555">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-7f52ff?logo=kotlin&logoColor=white">
  <img alt="OpenXR" src="https://img.shields.io/badge/OpenXR-own%20runtime-8a2be2">
  <img alt="LuaJIT SDK" src="https://img.shields.io/badge/SDK-LuaJIT-000080?logo=lua&logoColor=white">
  <img alt="Languages in the app" src="https://img.shields.io/badge/app-PT%20%7C%20EN%20%7C%20ES%20%7C%20RU-orange">
  <a href="https://github.com/mt-VR/HyperXR/stargazers"><img alt="Stars" src="https://img.shields.io/github/stars/mt-VR/HyperXR?style=social"></a>
</p>

<p align="center">
  <b><a href="#en">English</a></b> ·
  <b><a href="#ru">Русский</a></b> ·
  <b><a href="#pt">Português</a></b> ·
  <b><a href="#es">Español</a></b> ·
  <b><a href="#fr">Français</a></b> ·
  <b><a href="#de">Deutsch</a></b> ·
  <b><a href="#it">Italiano</a></b> ·
  <b><a href="#zh">中文</a></b> ·
  <b><a href="#ja">日本語</a></b> ·
  <b><a href="#ko">한국어</a></b>
</p>

<p align="center">
  <a href="https://github.com/mt-VR/HyperXR/releases/latest">Download</a> ·
  <a href="#build">Build</a> ·
  <a href="#structure">Structure</a> ·
  <a href="#docs">Docs</a> ·
  <a href="#guide-pt">Guia completo (PT)</a> ·
  <a href="#credits">Credits</a> ·
  <a href="#license">License</a> ·
  <a href="#third-party">Third-party</a>
</p>

> **HyperMagic is free and open source (GPLv3). If you paid for it, you were robbed.**

---

<a id="en"></a>

## English

Free and open-source XR platform for Android phones. Put your phone in a VR Box (or any phone viewer) and it becomes a mixed-reality and VR headset with a Quest-style interface, hand tracking through the camera, apps and games. Made in partnership with **Samrat Beketov**, creator of **MagicXR**.

**Download** ([latest release](https://github.com/mt-VR/HyperXR/releases/latest))
- `HyperMagic-…-arm64.apk`: most phones (64-bit Android).
- `HyperMagic-…-armv7.apk`: phones with a 32-bit Android (if the other one says "invalid package" or "App not installed").

**What it does**
- **Mixed reality** through the camera and **VR** with 360° environments; 6DoF with ARCore, 3DoF otherwise.
- **Hand tracking** with the camera, on the NPU or GPU: human hands with arms, pinch to click, palm menu, direct touch.
- **Two interfaces**: *Hyper* (HyperMagic's own, windows over a dock) and *Meta* (Horizon OS-style library and round-button bar).
- **Controllers**: Joy-Con, PlayStation, Xbox and other gamepads, a second phone (HyperConnect) and **colour-tracked 6DoF controllers** (stickers + calibrator, two at once).
- **Own OpenXR runtime** and an on-device patcher for Quest, OpenXR, Daydream and Cardboard APKs.
- **45 built-in games** (with Another Axiom's official Gorilla Locomotion), **Minecraft Java** (QuestCraft and GorillaCraft) and emulators (NES, Game Boy, GBA, DOS).
- GeckoView **browser**, Android apps in windows (Shizuku), YouTube, files, gallery, store, people and calls.
- **RealityLenses**: lens calibration for 46 viewers (Cardboard, Daydream, Gear VR, VR Box, Shinecon, BOBOVR...).
- App in Portuguese, English, Spanish and Russian.

**Quick start**
1. Install the APK and open HyperMagic.
2. Allow the camera, pick your viewer in **RealityLenses** and tap **Start XR experience**.
3. Put the phone in the viewer. Pinch to click; open your palm and pinch for the menu.

Full guide (Portuguese): [below](#guide-pt) · honest status of every feature: [docs/STATUS.md](docs/STATUS.md) · [back to top](#top)

---

<a id="ru"></a>

## Русский

Бесплатная платформа XR с открытым исходным кодом для телефонов Android. Вставьте телефон в VR Box (или любую гарнитуру для телефона), и он станет шлемом смешанной реальности и VR с интерфейсом в стиле Quest, отслеживанием рук через камеру, приложениями и играми. Сделано в партнёрстве с **Samrat Beketov**, автором **MagicXR**.

**Скачать** ([последний релиз](https://github.com/mt-VR/HyperXR/releases/latest))
- `HyperMagic-…-arm64.apk`: большинство телефонов (64-битный Android).
- `HyperMagic-…-armv7.apk`: телефоны с 32-битным Android (если первый APK пишет «недопустимый пакет» или «Приложение не установлено»).

**Возможности**
- **Смешанная реальность** через камеру и **VR** с 360°-окружениями; 6DoF с ARCore, иначе 3DoF.
- **Отслеживание рук** камерой на NPU или GPU: человеческие руки с предплечьями, щипок для нажатия, меню на ладони, прямое касание.
- **Два интерфейса**: *Hyper* (собственный, окна над док-панелью) и *Meta* (библиотека и панель круглых кнопок в стиле Horizon OS).
- **Контроллеры**: Joy-Con, PlayStation, Xbox и другие геймпады, второй телефон (HyperConnect) и **6DoF-контроллеры по цвету** (наклейки + калибровка, два одновременно).
- **Собственный runtime OpenXR** и патчер на устройстве для APK Quest, OpenXR, Daydream и Cardboard.
- **45 встроенных игр** (с официальным Gorilla Locomotion от Another Axiom), **Minecraft Java** (QuestCraft и GorillaCraft) и эмуляторы (NES, Game Boy, GBA, DOS).
- **Браузер** GeckoView, приложения Android в окнах (Shizuku), YouTube, файлы, галерея, магазин, люди и звонки.
- **RealityLenses**: калибровка линз для 46 гарнитур (Cardboard, Daydream, Gear VR, VR Box, Shinecon, BOBOVR...).
- Приложение на португальском, английском, испанском и русском.

**Быстрый старт**
1. Установите APK и откройте HyperMagic.
2. Разрешите камеру, выберите гарнитуру в **RealityLenses** и нажмите **Iniciar experiência XR** (Запустить XR).
3. Вставьте телефон в гарнитуру. Щипок — нажатие; раскрытая ладонь и щипок — меню.

Полное руководство (на португальском): [ниже](#guide-pt) · честный статус всех функций: [docs/STATUS.md](docs/STATUS.md) · [наверх](#top)

---

<a id="pt"></a>

## Português

Plataforma XR gratuita e de código aberto para celulares Android. Coloque o celular num VR Box (ou qualquer óculos de celular) e ele vira um headset de realidade mista e VR, com interface no estilo do Quest, rastreamento das mãos pela câmera, apps e jogos. Feito em parceria com **Samrat Beketov**, criador do **MagicXR**.

**Baixar** ([último release](https://github.com/mt-VR/HyperXR/releases/latest))
- `HyperMagic-…-arm64.apk`: a maioria dos celulares (Android de 64 bits).
- `HyperMagic-…-armv7.apk`: celulares com Android de 32 bits (se o outro der "pacote inválido" ou "App não instalado").

**O que ele faz**
- **Realidade mista** pela câmera e **VR** com ambientes 360°; 6DoF com ARCore, 3DoF sem.
- **Hand tracking** pela câmera, na NPU ou na GPU: mãos humanas com braço, pinça para clicar, menu na palma, toque direto.
- **Duas interfaces**: *Hyper* (a do HyperMagic, janelas sobre a dock) e *Meta* (Biblioteca e barra de botões redondos no estilo do Horizon OS).
- **Controles**: Joy-Con, PlayStation, Xbox e outros gamepads, outro celular (HyperConnect) e **controles 6DoF por cor** (adesivos + calibrador, dois ao mesmo tempo).
- **Runtime OpenXR próprio** e patcher no celular para APKs de Quest, OpenXR, Daydream e Cardboard.
- **45 jogos** embutidos (com o Gorilla Locomotion oficial da Another Axiom), **Minecraft Java** (QuestCraft e GorillaCraft) e emuladores (NES, Game Boy, GBA, DOS).
- **Navegador** GeckoView, apps Android em janelas (Shizuku), YouTube, arquivos, galeria, loja, pessoas e chamadas.
- **RealityLenses**: calibração das lentes de 46 óculos (Cardboard, Daydream, Gear VR, VR Box, Shinecon, BOBOVR...).
- App em português, inglês, espanhol e russo.

**Começo rápido**
1. Instale o APK e abra o HyperMagic.
2. Permita a câmera, escolha o seu óculos em **RealityLenses** e toque em **Iniciar experiência XR**.
3. Coloque o celular no óculos. Pinça clica; palma aberta e pinça abrem o menu.

Guia completo: [abaixo](#guide-pt) · estado honesto de cada recurso: [docs/STATUS.md](docs/STATUS.md) · [voltar ao topo](#top)

---

<a id="es"></a>

## Español

Plataforma XR gratuita y de código abierto para móviles Android. Pon el móvil en un VR Box (o cualquier visor para móvil) y se convierte en un casco de realidad mixta y VR, con interfaz al estilo de Quest, seguimiento de manos con la cámara, apps y juegos. Hecho en colaboración con **Samrat Beketov**, creador de **MagicXR**.

**Descargar** ([última versión](https://github.com/mt-VR/HyperXR/releases/latest))
- `HyperMagic-…-arm64.apk`: la mayoría de los móviles (Android de 64 bits).
- `HyperMagic-…-armv7.apk`: móviles con Android de 32 bits (si el otro dice "paquete no válido" o "App no instalada").

**Qué hace**
- **Realidad mixta** con la cámara y **VR** con entornos 360°; 6DoF con ARCore, 3DoF sin él.
- **Seguimiento de manos** con la cámara, en la NPU o la GPU: manos humanas con brazo, pellizco para hacer clic, menú en la palma, toque directo.
- **Dos interfaces**: *Hyper* (la de HyperMagic, ventanas sobre el dock) y *Meta* (biblioteca y barra de botones redondos al estilo de Horizon OS).
- **Mandos**: Joy-Con, PlayStation, Xbox y otros gamepads, otro móvil (HyperConnect) y **mandos 6DoF por color** (pegatinas + calibrador, dos a la vez).
- **Runtime OpenXR propio** y parcheador en el móvil para APK de Quest, OpenXR, Daydream y Cardboard.
- **45 juegos** incluidos (con el Gorilla Locomotion oficial de Another Axiom), **Minecraft Java** (QuestCraft y GorillaCraft) y emuladores (NES, Game Boy, GBA, DOS).
- **Navegador** GeckoView, apps Android en ventanas (Shizuku), YouTube, archivos, galería, tienda, personas y llamadas.
- **RealityLenses**: calibración de lentes para 46 visores (Cardboard, Daydream, Gear VR, VR Box, Shinecon, BOBOVR...).
- App en portugués, inglés, español y ruso.

**Inicio rápido**
1. Instala el APK y abre HyperMagic.
2. Permite la cámara, elige tu visor en **RealityLenses** y toca **Iniciar experiência XR** (Iniciar XR).
3. Pon el móvil en el visor. El pellizco hace clic; la palma abierta y un pellizco abren el menú.

Guía completa (en portugués): [abajo](#guide-pt) · estado honesto de cada función: [docs/STATUS.md](docs/STATUS.md) · [volver arriba](#top)

---

<a id="fr"></a>

## Français

Plateforme XR libre et gratuite pour téléphones Android. Glissez le téléphone dans un VR Box (ou tout casque pour téléphone) et il devient un casque de réalité mixte et de VR, avec une interface façon Quest, le suivi des mains par la caméra, des applis et des jeux. Réalisé en partenariat avec **Samrat Beketov**, créateur de **MagicXR**.

**Télécharger** ([dernière version](https://github.com/mt-VR/HyperXR/releases/latest))
- `HyperMagic-…-arm64.apk`: la plupart des téléphones (Android 64 bits).
- `HyperMagic-…-armv7.apk`: téléphones sous Android 32 bits (si l'autre affiche « paquet non valide » ou « Application non installée »).

**Ce qu'il fait**
- **Réalité mixte** par la caméra et **VR** avec des environnements 360° ; 6DoF avec ARCore, sinon 3DoF.
- **Suivi des mains** par la caméra, sur le NPU ou le GPU : mains humaines avec bras, pincement pour cliquer, menu dans la paume, toucher direct.
- **Deux interfaces** : *Hyper* (celle de HyperMagic, fenêtres au-dessus d'un dock) et *Meta* (bibliothèque et barre de boutons ronds façon Horizon OS).
- **Manettes** : Joy-Con, PlayStation, Xbox et autres, un second téléphone (HyperConnect) et des **manettes 6DoF suivies par couleur** (autocollants + calibrage, deux à la fois).
- **Runtime OpenXR maison** et un patcheur sur l'appareil pour les APK Quest, OpenXR, Daydream et Cardboard.
- **45 jeux intégrés** (avec le Gorilla Locomotion officiel d'Another Axiom), **Minecraft Java** (QuestCraft et GorillaCraft) et des émulateurs (NES, Game Boy, GBA, DOS).
- **Navigateur** GeckoView, applis Android en fenêtres (Shizuku), YouTube, fichiers, galerie, boutique, contacts et appels.
- **RealityLenses** : calibrage des lentilles de 46 casques (Cardboard, Daydream, Gear VR, VR Box, Shinecon, BOBOVR...).
- Appli en portugais, anglais, espagnol et russe.

**Démarrage rapide**
1. Installez l'APK et ouvrez HyperMagic.
2. Autorisez la caméra, choisissez votre casque dans **RealityLenses** et touchez **Iniciar experiência XR** (Démarrer XR).
3. Placez le téléphone dans le casque. Le pincement clique ; paume ouverte et pincement ouvrent le menu.

Guide complet (en portugais) : [plus bas](#guide-pt) · état honnête de chaque fonction : [docs/STATUS.md](docs/STATUS.md) · [retour en haut](#top)

---

<a id="de"></a>

## Deutsch

Freie, kostenlose XR-Plattform für Android-Handys. Steck das Handy in eine VR Box (oder eine andere Handy-Brille) und es wird zu einem Mixed-Reality- und VR-Headset mit einer Oberfläche im Quest-Stil, Hand-Tracking über die Kamera, Apps und Spielen. Entstanden in Partnerschaft mit **Samrat Beketov**, dem Entwickler von **MagicXR**.

**Download** ([neueste Version](https://github.com/mt-VR/HyperXR/releases/latest))
- `HyperMagic-…-arm64.apk`: die meisten Handys (64-Bit-Android).
- `HyperMagic-…-armv7.apk`: Handys mit 32-Bit-Android (wenn das andere „ungültiges Paket“ oder „App nicht installiert“ meldet).

**Was es kann**
- **Mixed Reality** über die Kamera und **VR** mit 360°-Umgebungen; 6DoF mit ARCore, sonst 3DoF.
- **Hand-Tracking** über die Kamera, auf NPU oder GPU: menschliche Hände mit Armen, Pinch zum Klicken, Handflächenmenü, direktes Berühren.
- **Zwei Oberflächen**: *Hyper* (die eigene, Fenster über einem Dock) und *Meta* (Bibliothek und Leiste mit runden Knöpfen im Stil von Horizon OS).
- **Controller**: Joy-Con, PlayStation, Xbox und andere Gamepads, ein zweites Handy (HyperConnect) und **per Farbe getrackte 6DoF-Controller** (Aufkleber + Kalibrierung, zwei gleichzeitig).
- **Eigene OpenXR-Runtime** und ein Patcher auf dem Gerät für Quest-, OpenXR-, Daydream- und Cardboard-APKs.
- **45 eingebaute Spiele** (mit Another Axioms offizieller Gorilla Locomotion), **Minecraft Java** (QuestCraft und GorillaCraft) und Emulatoren (NES, Game Boy, GBA, DOS).
- GeckoView-**Browser**, Android-Apps in Fenstern (Shizuku), YouTube, Dateien, Galerie, Store, Kontakte und Anrufe.
- **RealityLenses**: Linsenkalibrierung für 46 Brillen (Cardboard, Daydream, Gear VR, VR Box, Shinecon, BOBOVR...).
- App auf Portugiesisch, Englisch, Spanisch und Russisch.

**Schnellstart**
1. APK installieren und HyperMagic öffnen.
2. Kamera erlauben, deine Brille in **RealityLenses** wählen und **Iniciar experiência XR** (XR starten) tippen.
3. Handy in die Brille stecken. Pinch klickt; offene Handfläche und Pinch öffnen das Menü.

Vollständige Anleitung (Portugiesisch): [unten](#guide-pt) · ehrlicher Stand jeder Funktion: [docs/STATUS.md](docs/STATUS.md) · [nach oben](#top)

---

<a id="it"></a>

## Italiano

Piattaforma XR gratuita e open source per telefoni Android. Metti il telefono in un VR Box (o qualsiasi visore per telefono) e diventa un visore di realtà mista e VR, con interfaccia in stile Quest, tracciamento delle mani con la fotocamera, app e giochi. Realizzato in collaborazione con **Samrat Beketov**, creatore di **MagicXR**.

**Scarica** ([ultima versione](https://github.com/mt-VR/HyperXR/releases/latest))
- `HyperMagic-…-arm64.apk`: la maggior parte dei telefoni (Android a 64 bit).
- `HyperMagic-…-armv7.apk`: telefoni con Android a 32 bit (se l'altro dice «pacchetto non valido» o «App non installata»).

**Cosa fa**
- **Realtà mista** tramite la fotocamera e **VR** con ambienti a 360°; 6DoF con ARCore, altrimenti 3DoF.
- **Tracciamento delle mani** con la fotocamera, su NPU o GPU: mani umane con braccia, pizzico per cliccare, menu sul palmo, tocco diretto.
- **Due interfacce**: *Hyper* (quella di HyperMagic, finestre sopra un dock) e *Meta* (libreria e barra di pulsanti rotondi in stile Horizon OS).
- **Controller**: Joy-Con, PlayStation, Xbox e altri gamepad, un secondo telefono (HyperConnect) e **controller 6DoF tracciati dal colore** (adesivi + calibrazione, due insieme).
- **Runtime OpenXR proprio** e un patcher sul dispositivo per APK Quest, OpenXR, Daydream e Cardboard.
- **45 giochi inclusi** (con il Gorilla Locomotion ufficiale di Another Axiom), **Minecraft Java** (QuestCraft e GorillaCraft) ed emulatori (NES, Game Boy, GBA, DOS).
- **Browser** GeckoView, app Android in finestre (Shizuku), YouTube, file, galleria, store, persone e chiamate.
- **RealityLenses**: calibrazione delle lenti per 46 visori (Cardboard, Daydream, Gear VR, VR Box, Shinecon, BOBOVR...).
- App in portoghese, inglese, spagnolo e russo.

**Avvio rapido**
1. Installa l'APK e apri HyperMagic.
2. Consenti la fotocamera, scegli il tuo visore in **RealityLenses** e tocca **Iniciar experiência XR** (Avvia XR).
3. Metti il telefono nel visore. Il pizzico clicca; palmo aperto e pizzico aprono il menu.

Guida completa (in portoghese): [qui sotto](#guide-pt) · stato onesto di ogni funzione: [docs/STATUS.md](docs/STATUS.md) · [torna su](#top)

---

<a id="zh"></a>

## 中文

免费开源的 Android 手机 XR 平台。把手机放进 VR Box（或任何手机 VR 眼镜），它就变成一台混合现实和 VR 头显：Quest 风格的界面、用摄像头追踪双手、应用和游戏。与 **MagicXR** 的作者 **Samrat Beketov** 合作打造。

**下载** ([最新版本](https://github.com/mt-VR/HyperXR/releases/latest))
- `HyperMagic-…-arm64.apk`: 大多数手机（64 位 Android）。
- `HyperMagic-…-armv7.apk`: 32 位 Android 的手机（如果另一个提示“安装包无效”或“未安装应用”）。

**功能**
- 通过摄像头的**混合现实**，以及带 360° 环境的 **VR**；有 ARCore 时为 6DoF，否则 3DoF。
- 用摄像头进行**手部追踪**，运行在 NPU 或 GPU 上：带手臂的真实人手、捏合点击、掌心菜单、直接触摸。
- **两种界面**：*Hyper*（HyperMagic 自己的，窗口位于 Dock 之上）和 *Meta*（Horizon OS 风格的资源库和圆形按钮栏）。
- **控制器**：Joy-Con、PlayStation、Xbox 等手柄，另一部手机（HyperConnect），以及**颜色追踪的 6DoF 控制器**（贴纸 + 校准，可同时用两个）。
- **自研 OpenXR 运行时**，以及在手机上为 Quest、OpenXR、Daydream 和 Cardboard APK 打补丁的工具。
- **45 款内置游戏**（使用 Another Axiom 官方的 Gorilla Locomotion）、**Minecraft Java**（QuestCraft 和 GorillaCraft）以及模拟器（NES、Game Boy、GBA、DOS）。
- GeckoView **浏览器**、窗口中的 Android 应用（Shizuku）、YouTube、文件、相册、商店、好友和通话。
- **RealityLenses**：46 款眼镜的镜片校准（Cardboard、Daydream、Gear VR、VR Box、千幻魔镜、小宅 BOBOVR……）。
- 应用支持葡萄牙语、英语、西班牙语和俄语。

**快速开始**
1. 安装 APK 并打开 HyperMagic。
2. 允许使用摄像头，在 **RealityLenses** 中选择你的眼镜，然后点击 **Iniciar experiência XR**（启动 XR）。
3. 把手机放进眼镜。捏合即点击；张开手掌并捏合打开菜单。

完整指南（葡萄牙语）：[见下文](#guide-pt) · 每项功能的真实状态：[docs/STATUS.md](docs/STATUS.md) · [回到顶部](#top)

---

<a id="ja"></a>

## 日本語

Android スマートフォン向けの無料・オープンソースの XR プラットフォームです。スマホを VR Box（またはスマホ用ゴーグル）に入れると、Quest 風の UI、カメラによるハンドトラッキング、アプリやゲームを備えた MR / VR ヘッドセットになります。**MagicXR** の作者 **Samrat Beketov** とのパートナーシップで開発しています。

**ダウンロード** ([最新リリース](https://github.com/mt-VR/HyperXR/releases/latest))
- `HyperMagic-…-arm64.apk`: ほとんどのスマホ（64 ビット Android）。
- `HyperMagic-…-armv7.apk`: 32 ビット Android のスマホ（もう一方で「パッケージが無効です」や「アプリはインストールされていません」と出る場合）。

**できること**
- カメラによる**複合現実（MR）**と 360° 環境の **VR**。ARCore があれば 6DoF、なければ 3DoF。
- カメラによる**ハンドトラッキング**（NPU / GPU）：腕付きの人の手、ピンチでクリック、手のひらメニュー、ダイレクトタッチ。
- **2 つのインターフェース**：*Hyper*（HyperMagic 独自、ドックの上にウィンドウ）と *Meta*（Horizon OS 風のライブラリと丸ボタンのバー）。
- **コントローラー**：Joy-Con、PlayStation、Xbox などのゲームパッド、2 台目のスマホ（HyperConnect）、**色で追跡する 6DoF コントローラー**（シール + キャリブレーション、2 台同時）。
- **独自の OpenXR ランタイム**と、Quest・OpenXR・Daydream・Cardboard の APK を端末上でパッチするツール。
- **45 本の内蔵ゲーム**（Another Axiom 公式の Gorilla Locomotion を使用）、**Minecraft Java**（QuestCraft と GorillaCraft）、エミュレーター（NES、ゲームボーイ、GBA、DOS）。
- GeckoView **ブラウザ**、ウィンドウで動く Android アプリ（Shizuku）、YouTube、ファイル、ギャラリー、ストア、フレンドと通話。
- **RealityLenses**：46 種類のゴーグルのレンズ補正（Cardboard、Daydream、Gear VR、VR Box、Shinecon、BOBOVR など）。
- アプリはポルトガル語・英語・スペイン語・ロシア語に対応。

**クイックスタート**
1. APK をインストールして HyperMagic を開きます。
2. カメラを許可し、**RealityLenses** でゴーグルを選んで **Iniciar experiência XR**（XR を開始）をタップします。
3. スマホをゴーグルに入れます。ピンチでクリック、手のひらを開いてピンチするとメニューが開きます。

詳しいガイド（ポルトガル語）：[下記](#guide-pt) · 各機能の正直な状況：[docs/STATUS.md](docs/STATUS.md) · [トップへ戻る](#top)

---

<a id="ko"></a>

## 한국어

Android 휴대폰을 위한 무료 오픈 소스 XR 플랫폼입니다. 휴대폰을 VR Box(또는 다른 휴대폰용 VR 고글)에 넣으면 Quest 스타일 인터페이스, 카메라 손 추적, 앱과 게임을 갖춘 혼합 현실 및 VR 헤드셋이 됩니다. **MagicXR** 제작자 **Samrat Beketov**와 함께 만들었습니다.

**다운로드** ([최신 릴리스](https://github.com/mt-VR/HyperXR/releases/latest))
- `HyperMagic-…-arm64.apk`: 대부분의 휴대폰(64비트 Android).
- `HyperMagic-…-armv7.apk`: 32비트 Android 휴대폰(다른 파일에서 "잘못된 패키지" 또는 "앱이 설치되지 않았습니다"가 나올 때).

**기능**
- 카메라를 통한 **혼합 현실**과 360° 환경의 **VR**. ARCore가 있으면 6DoF, 없으면 3DoF.
- 카메라 **손 추적**(NPU 또는 GPU): 팔이 있는 사람 손, 핀치로 클릭, 손바닥 메뉴, 직접 터치.
- **두 가지 인터페이스**: *Hyper*(HyperMagic 고유, 독 위의 창)와 *Meta*(Horizon OS 스타일의 라이브러리와 둥근 버튼 바).
- **컨트롤러**: Joy-Con, PlayStation, Xbox 등 게임패드, 두 번째 휴대폰(HyperConnect), **색상으로 추적하는 6DoF 컨트롤러**(스티커 + 보정, 동시에 두 개).
- **자체 OpenXR 런타임**과 Quest, OpenXR, Daydream, Cardboard APK를 기기에서 패치하는 도구.
- **내장 게임 45개**(Another Axiom 공식 Gorilla Locomotion 사용), **Minecraft Java**(QuestCraft와 GorillaCraft), 에뮬레이터(NES, 게임보이, GBA, DOS).
- GeckoView **브라우저**, 창으로 실행되는 Android 앱(Shizuku), YouTube, 파일, 갤러리, 스토어, 친구와 통화.
- **RealityLenses**: 46가지 고글의 렌즈 보정(Cardboard, Daydream, Gear VR, VR Box, Shinecon, BOBOVR...).
- 앱은 포르투갈어, 영어, 스페인어, 러시아어를 지원합니다.

**빠른 시작**
1. APK를 설치하고 HyperMagic을 엽니다.
2. 카메라를 허용하고 **RealityLenses**에서 고글을 고른 뒤 **Iniciar experiência XR**(XR 시작)을 누릅니다.
3. 휴대폰을 고글에 넣습니다. 핀치는 클릭, 손바닥을 펴고 핀치하면 메뉴가 열립니다.

전체 가이드(포르투갈어): [아래](#guide-pt) · 각 기능의 솔직한 상태: [docs/STATUS.md](docs/STATUS.md) · [맨 위로](#top)

---

<a id="build"></a>

## Build

Requirements: JDK 17+, Android SDK (platform 36, build-tools 35), NDK 27.2.12479018, CMake 3.22.1.

```bash
./gradlew :core:test                                   # JVM tests of the core
./gradlew :app:assembleDebug -PhxAbi=arm64-v8a         # 64-bit APK (packs the .hyperx packages)
./gradlew :app:assembleDebug -PhxAbi=armeabi-v7a       # 32-bit APK
./gradlew :app:lintDebug
luajit sdk/tests/run_tests.lua                         # Lua SDK tests (desktop LuaJIT 2.1)
```

Every release is built by [.github/workflows/release.yml](.github/workflows/release.yml), which runs the tests and publishes both APKs. Host tests of the real JNI bridge: [docs/TESTING.md](docs/TESTING.md).

<a id="structure"></a>

## Structure

| Path | What it holds |
|---|---|
| `core/` | Pure Kotlin (JVM), testable without Android: maths, settings, launcher, capabilities, `.hyperx` format, permissions, glTF, hand pipeline, Direct Touch, Joy-Con fusion, gorilla locomotion, colour controller, game logic |
| `app/` | Android app: 2D UI (Compose), XR engine (GLES 3), ARCore/Camera2, hand tracking (RTMPose / MediaPipe), controllers, audio, browser, spatial launcher, games |
| `hybridmagic/` | HybridMagic PC: the PC's GPU tracks the hands for the phone (Python, ONNX Runtime + DirectML) |
| `app/src/main/cpp/` | JNI: LuaJIT bridge, OpenXR (Khronos loader), NNAPI, emulator front end |
| `app/src/main/assets/hxsdk/` | Trusted Lua SDK: `bootstrap.lua` (sandbox + `hx` API), `physics.lua`, `ui.lua`, `vmath.lua` |
| `native/` | LuaJIT 2.1 (arm64-v8a, armeabi-v7a, x86_64), the HyperMagic OpenXR runtime, libretro cores |
| `minecraft/` | Minecraft Java Edition launcher (QuestCraft and GorillaCraft), ported from QuestCraft's Pojlib; see [minecraft/README.md](minecraft/README.md) |
| `broker/`, `vrcore/`, `hyperconnect/` | OpenXR broker, Daydream controller service, the controller-phone app |
| `games/` | Sources of the built-in `.hyperx` packages: Clock, Calculator, Calendar |
| `sdk/tests/` | SDK tests running the real `bootstrap.lua` on desktop LuaJIT |
| `third_party/` | GorillaLocomotion (MIT), NewPipe Extractor, compatibility layer |
| `docs/` | Architecture, SDK, security, performance, hand tracking, icons, tests, status, release notes |

<a id="docs"></a>

## Docs

- [Architecture](docs/ARCHITECTURE.md) · [SDK and the .hyperx format](docs/SDK.md) · [SDK security model](docs/SECURITY.md)
- [Performance and latency](docs/PERFORMANCE.md) · [Hand tracking](docs/HAND_TRACKING.md) · [HyperMagic OpenXR runtime](docs/OPENXR_RUNTIME.md)
- [Games inspired by Quest games](docs/QUEST_GAMES.md) · [Hand Physics Lab](docs/HAND_PHYSICS_LAB.md) · [Emulators](docs/EMULATORS.md)
- [Controllers](docs/CONTROLLERS.md) · [HyperConnect](docs/HYPERCONNECT.md) · [Daydream](docs/DAYDREAM.md) · [HybridMagic](hybridmagic/README.md)
- [Icons](docs/ICONS.md) · [Tests](docs/TESTING.md) · [Feature status](docs/STATUS.md) · [Release notes](docs/releases/)

<a id="guide-pt"></a>

## Guia completo (Português)

<details>
<summary>Abrir o guia: uso, navegador, arquivos e galeria, jogos de Quest e OpenXR, Minecraft, apps Android</summary>

##### Fluxo de uso

1. Abra o HyperMagic: aparece a **interface 2D em paisagem** (somente configurações e gerenciamento).
2. Conceda a câmera, ajuste o que quiser e toque em **Iniciar experiência XR**.
3. O HyperMagic verifica as capacidades: com ARCore inicia **realidade mista 6DoF**; sem ARCore mas com
   câmera, MR com imagem mono e 3DoF; caso contrário **VR SBS** com ambiente 3D. O motivo aparece na tela inicial.
4. Coloque o celular no VR Box. Para clicar: olhe para o botão por ~1 s (anel de progresso no
   retículo), toque na tela/botão do visor, pinçe com a mão apontada para o botão, toque o botão com
   o dedo (Direct Touch) ou use o R do Joy-Con.
5. Mãos: uma **bolinha** fica entre o polegar e o indicador (sem feixe) e se preenche conforme você
   pinça; **pinçar é tocar** (segurar e mover arrasta/rola a página). Joy-Con: o **analógico** move
   o cursor, **A** clica, **B** volta/fecha (segurar B fecha a janela), R/ZR + analógico rola.
6. Gestos: pinçar e segurar longe de botões abre o **menu radial** (Voltar, Início, Teclado,
   Captura); deslizar o polegar sobre o indicador **rola**; dois toques do polegar **voltam**;
   pinça longa num link do navegador **arrasta** o link para outra janela.
7. Em VR com ARCore Depth, a câmera aparece aos poucos ao se aproximar de obstáculos (**alerta de
   limite**; Configurações > Gráficos).

#### Navegador

O navegador usa o **GeckoView** (motor do Firefox), o mesmo motor do [Wolvic](https://wolvic.com),
e, como o Wolvic, desenha a página direto na textura da janela. Tem abas, barra de endereço
(digite um endereço ou uma pesquisa), favoritos, histórico, modo desktop, downloads e tela de
cinema para vídeos em tela cheia. O GeckoView vem no APK de 64 bits (arm64) e no de 32 bits
(armv7); em outras arquiteturas o HyperMagic usa o WebView do sistema. Licença: MPL 2.0.

#### Arquivos e Galeria

- **Arquivos**: gerenciador real do armazenamento (peça *Permitir acesso* uma vez: a tela de
  permissão aparece no celular). Copiar, recortar, colar, renomear, nova pasta, apagar (com
  confirmação), ordenar por nome/data/tamanho/tipo. Abre texto, fotos, PDF, vídeo e áudio dentro
  do XR; instala `.hyperx` e APKs.
- **Galeria**: fotos e vídeos do celular e as capturas do HyperMagic. Fotos e vídeos **360** são
  detectados pelos metadados (Photo Sphere, Spherical Video) ou pela proporção 2:1 e abrem ao seu
  redor, inclusive 3D cima/baixo ou lado a lado. Uma foto 360 pode ser **salva como ambiente**.

#### Jogos de Quest e apps OpenXR

Abra o APK em **Arquivos**. **Corrigir e instalar** prepara o jogo no próprio celular (sem PC):
targetSdk 29, recursos de headset opcionais e o loader OpenXR da Meta trocado pelo da Khronos;
depois assina com uma chave criada no Keystore do celular. Se o original já estiver instalado com
outra assinatura, o painel oferece desinstalá-lo (os dados do jogo são apagados). O HyperMagic lê o APK antes de instalar e diz, sem rodeios, se ele pode
rodar no runtime HyperMagic: motor (Unity/Unreal/Godot), loader OpenXR (o da Khronos funciona; o da
Meta não), VrApi (não roda), Meta Platform SDK (a checagem de licença pode fechar o jogo), Vulkan
ou OpenGL ES e extensões Meta citadas. Com o Shizuku ativo a instalação é direta e o OBB que estiver
ao lado do APK é copiado para `Android/obb`; sem ele, o instalador do Android pergunta na tela.

Para apps OpenXR acharem o runtime HyperMagic instale também o APK pequeno **HyperMagic OpenXR Broker**
(`broker/`); se ele não instalar por conflito, o celular já tem um broker OpenXR: escolha "HyperMagic
Runtime" nele. Os
Joy-Cons/gamepad e o toque na tela viram os controles do jogo (perfil Touch); a mira segue o olhar.
Jogos feitos só para o Quest geralmente exigem recursos que um celular não tem, então espere
muitos "não vai funcionar" — o veredito existe para não desperdiçar seu tempo.

#### Minecraft: QuestCraft e GorillaCraft

O Minecraft Java Edition de verdade dentro do HyperMagic. O launcher do QuestCraft (Pojlib) foi
portado para o app, sem Unity: o jogo roda num processo próprio e abre direto no runtime OpenXR do
HyperMagic com o Vivecraft. Em **Configurações 2D > Minecraft**: entre com a conta Microsoft (código
em microsoft.com/link), escolha a versão (1.19.2 a 1.21.11), instale e jogue; depois os dois
aparecem na Biblioteca do XR.

- **QuestCraft**: como o QuestCraft entrega, com Sodium e os mods de desempenho dele.
- **GorillaCraft**: com o ViveMonkeCraft (movimento de Gorilla Tag) ajustado para VR Box: sem
  pernas, sem voar (impulso limitado, sem prancha nem teleporte) e tolerante ao tremor das mãos.

Precisa de um celular ARM 64 bits e de uma conta que tenha o Minecraft Java (sem o jogo, modo
demonstração). Detalhes e créditos em [minecraft/README.md](minecraft/README.md).

#### Apps Android em janelas (Shizuku)

1. Instale e inicie o [Shizuku](https://shizuku.rikka.app/) (depuração sem fio ou root).
2. No XR, abra **Apps Android** e toque em *Conceder permissão*; escolha um app e se a janela é
   plana ou curva.
3. O app roda num display virtual criado pelo processo shell do Shizuku; toques, teclas e texto
   (somente ASCII) são injetados nesse display. Alguns apps bloqueiam displays secundários ou
   conteúdo protegido (DRM) e aparecem pretos — limitação do Android, não do HyperMagic.

</details>

<a id="credits"></a>

## Credits / Créditos

HyperMagic (formerly HyperXR) is made in partnership with **Samrat Beketov**, author of **MagicXR**, who allowed the use of MagicXR's code in this project under the GPLv3. Parts that come from MagicXR are marked in the code with "MagicXR (Samrat Beketov)": so far, the pinch lock (a pinch only lets go after two open frames in a row and never counts with a closed hand). The logo was made together with Samrat.

Samrat also brought his **PhoneXR** (github.com/samrat1games/phonexr) into HyperMagic. Ported from it, marked in the code with "PhoneXR (Samrat Beketov)": leaning and short steps from the accelerometer on phones without 6DoF (`core/.../headtracking/LeanTracker.kt`, from SensorSixDof) and 3D photos from ordinary photos (`core/.../media/StereoFromDepth.kt`, from SpatialPhoto).

<a id="license"></a>

## License

Copyright (C) 2026 AkunDiscoRemake

HyperMagic is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License, version 3, as published by the Free Software Foundation. It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY. See [LICENSE](LICENSE). Selling it without the source code violates the licence.

<a id="third-party"></a>

## Third-party licences

- LuaJIT: MIT (`native/luajit/COPYRIGHT`)
- OpenXR headers (`native/openxr-runtime/include`): Apache-2.0 OR MIT, Khronos Group
- Khronos OpenXR loader: Apache-2.0 (Maven `org.khronos.openxr:openxr_loader_for_android`)
- ARCore SDK, MediaPipe Tasks, LiteRT (TensorFlow Lite), AndroidX, Jetpack Compose: Apache-2.0
- GorillaLocomotion (Another Axiom), ported in `core/.../questgames/GorillaRig.kt`: MIT (`third_party/GorillaLocomotion/LICENSE`)
- Pojlib / QuestCraft (`minecraft/`): LGPL-3.0 (`minecraft/LICENSE-LGPL3-Pojlib.txt`); at run time the app downloads QuestCraft's Java 22, LWJGL (BSD-3), MobileGlues (LGPL-2.1), OpenAL Soft (LGPL), JNA and SQLite JDBC
- ViveMonkeCraft (downloaded from Modrinth by the GorillaCraft profile): MIT
- GeckoView (Mozilla): MPL-2.0 (Maven `geckoview-arm64-v8a` and `geckoview-armeabi-v7a`)
- Shizuku API: Apache-2.0
- Real-ESRGAN general x4v3 (Xintao Wang et al.), passthrough enhancement: BSD-3-Clause; int8 TFLite export by Qualcomm AI Hub (`app/src/main/assets/models/README.md`)
- Depth Anything V2 Small (AI depth, 3D photos; downloaded once by the app): Apache-2.0, Qualcomm AI Hub release
- Open Saber (leandrodreamer, port of NeoSpark314's Beep Saber), built from source for the HyperMagic runtime: MIT (`ports/opensaber`)
- HybridMagic PC (`hybridmagic/`): ONNX Runtime (MIT), OpenCV (Apache-2.0), NumPy (BSD-3-Clause), PyInstaller (GPL-2.0 with bootloader exception)
- MediaPipe Tasks GenAI: Apache-2.0; Qwen 2.5 models (downloaded by the user in the app): Apache-2.0
- Hand models: MediaPipe Hand Landmarker, RTMDet-nano and RTMPose-m (OpenMMLab), MediaPipe selfie multiclass: Apache-2.0 (`app/src/main/assets/models/README.md`)
- Game 3D models (`app/src/main/assets/games`): Kenney kits (www.kenney.nl), CC0, licence next to each kit
- Poly Haven environments: CC0 (recorded per downloaded environment, with `ATTRIBUTION.txt`)
- Emulator cores (libretro, built from source by `tools/build_libretro_cores.sh`): QuickNES GPL-2.0, Gambatte GPL-2.0, mGBA MPL-2.0, DOSBox Pure GPL-2.0; `libretro.h` MIT. Sources and commits in `native/libretro/CORES` and [docs/EMULATORS.md](docs/EMULATORS.md). No games included.
- Material Symbols (Google), the panels' glyphs: Apache-2.0
- Browser icons: Wolvic's original icons (Igalia), MPL-2.0 (`native/WOLVIC_ICONS.md`)
- Built-in 360 environments (`app/src/main/assets/environments`): images provided by the project owner
- App icons: original art by HyperMagic's author. HyperMagic is not affiliated with Meta, Nintendo, Mojang, Valve or Another Axiom.

<p align="center"><a href="#top">↑</a></p>
