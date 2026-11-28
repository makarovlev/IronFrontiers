# Changelog

## [0.2.0-stage2] — 2026-11-27

### Добавлено
- Геометрия XZ без Bukkit: проверка осевых контуров, растеризация scanline с включённой границей, линии, вогнутые фигуры, интервалы строк и объединение в прямоугольники.
- Primitive-long ChunkIndex с четырьмя long-масками на чанк, O(1) `sectorAt`, `sectorsNear`, площадь и дистанция до сектора.
- Загрузчик `sectors.yml`, независимая валидация секторов и spawn anchors, immutable SectorRegistry, SurfaceResolver без принудительной загрузки чанков.
- Скользящие окна KPM в десятых долях, персональные окна, immutable snapshots, idle cleanup и безопасное изменение размера окна.
- Чистый Java автомат ENEMY/SAFE/HELD/DESTRUCTION с остатками таймеров, startup grace, freeze_when_empty, infinite_safe, occupation, anti-flap и resume window.
- Bukkit events и batch сообщения о начале разрушения/потере нескольких секторов.
- Миграция v2: `spawn_pause_left_s` и таблица `if_sector_override`; загрузка и сохранение состояния сектора/override.
- Независимый от частоты KPM-тиков периодический persist; финальная запись состояния ожидается до закрытия MySQL при выключении.
- Debug-команды `/if debug kill`, `/if debug state` и персистентные overrides `/if sectors set`.
- JUnit 5 тесты геометрии, KPM и автомата состояний.

### Проверка
- GitHub Actions run #36: `mvn -B verify` — success.
- Ручная приёмка Stage 2 на Purpur 1.20.6 и Spark-профилирование ещё не выполнены.

### Границы этапа
- `MobSpawn` остаётся заглушкой; настоящие кластеры, FormationCalculator, SpawnDirector и интеграция PrivateMobsX API будут на этапе 3B.
- Административные команды выделения/просмотра/удаления и трансформация мира остаются на этапах 3A/3C.
