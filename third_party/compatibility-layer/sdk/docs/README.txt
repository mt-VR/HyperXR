Compatibility-Layer SDK
===========

Набор для переноса и разработки Compatibility-Layer-игр под Compatibility-Layer — VR на обычном телефоне
(Cardboard + рантайм Monado + трекинг рук камерой + Joy-Con вместо контроллеров).

Состав:

  tools/compatibility_layer_port.py   Готовит чужой APK к запуску на Compatibility-Layer: правит манифест,
                          подменяет Compatibility-Layer loader, выравнивает и подписывает.
  src/                    Библиотека CompatibilityLayerInput (Kotlin/JVM): сырые данные рук и Joy-Con.
  runtime/compatibility_layer_input.h То же самое для игр на C/C++, один заголовок.
  docs/porting.txt        Как перенести игру и что перенести нельзя.
  docs/protocol.txt       Формат потока данных CompatibilityLayer.
  docs/manifest.txt       Что нужно в манифесте своей игры, чтобы не было чёрного экрана.
  docs/engines.txt        Подключение Unity, Godot 4 и Lua-движков.
  docs/api.txt            Compatibility-Layer Developer API: Compatibility-Layer, PH5, lifecycle и публикация.
  docs/ai-prompts.txt     Полные промты для генерации Compatibility-Layer-проектов.

Сборка библиотеки и тесты:

  ./gradlew :sdk:build

Что можно переносить
--------------------
Только сборки, которые вы вправе запускать: свои, открытые проекты, игры,
распространяемые вне магазина Meta, и купленные копии, полученные законно.

Игры из магазина Quest (Beat Saber, Job Simulator и подобные) перенести нельзя:
они проверяют покупку через сервисы Meta и работают на её закрытом рантайме.
Инструмент такие сборки распознаёт и отказывается их готовить.
