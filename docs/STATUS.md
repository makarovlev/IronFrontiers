# Статус разработки Iron Frontiers

Последнее обновление: 2026-11-27

| Возможность | Статус | Как проверить |
|---|---|---|
| Maven multi-module, Java 21, CI | принято на этапе 1 | GitHub Actions `mvn -B verify`; владелец подтвердил запуск плагина на сервере |
| Полная копия исходного ТЗ | проверено | Git blob SHA исходного файла и `docs/PLAN.txt` совпадают |
| Конфиги, reload, Messages, интеграционные адаптеры | принято на этапе 1 | Ручной запуск Purpur 1.20.6 подтверждён владельцем |
| MySQL StateStore, HikariCP, миграция v1, retry queue | принято на этапе 1 | Проверка live DB outage остаётся отдельным эксплуатационным тестом |
| Геометрия секторов, осевые контуры, растеризация строками | реализовано; CI success | 36-клеточный пример, линии, L/T/зубчатая/карманная формы, невалидные контуры |
| Прямоугольники, ChunkIndex, sectorAt/sectorsNear/distance/area | реализовано; CI success | Тесты отрицательных чанков, пересечений, отрицательных/крайних координат и больших радиусов |
| Загрузка sectors.yml и SectorRegistry | реализовано; CI success | Проверка ID, мира, WorldBorder, координат, порогов, таймеров, независимого отключения битого сектора |
| SurfaceResolver | реализовано; CI success | Возвращает верх collision shape только в уже загруженном чанке; ручная проверка блоков с частичной коллизией ожидает Purpur |
| KpmWindow/KpmTracker | реализовано; CI success | Скользящие бакеты, пропуски времени, десятые доли, личные/секторные снимки, idle cleanup, resize окна |
| Автомат ENEMY/SAFE/HELD/DESTRUCTION | реализовано; CI success | Таблица переходов, capture, safe expiry, held, destruction, anti-flap, loss, occupation, infinite_safe |
| Холостой старт и freeze_when_empty | реализовано; CI success | Unit-тесты; фактическое поведение на пустом тестовом сервере ожидает ручной проверки |
| События Bukkit и пакетные сообщения | реализовано; CI success | События на переходах; объединение потерь/разрушений по batch window ожидает проверки в игре |
| MySQL if_sector_state + if_sector_override | реализовано; CI success | Миграция v2, загрузка, новые ENEMY rows, orphan warnings, override persistence |
| Периодическое сохранение и остановка сервера | реализовано; CI success | Отдельный timer от KPM tick; финальный flush ожидает live-проверки с MySQL |
| /if debug kill, /if debug state, /if sectors set | реализовано; CI success | Включить `debug: true`, проверить debug и сохранение override в MySQL |
| Фактическая приёмка Stage 2 на Purpur 1.20.6 | ожидает владельца | См. чек-лист в docs/TESTING.md |
| Spark: 100 фиктивных секторов, тик автомата <0,3 мс | не измерено | Требуется замер на тестовом сервере с Spark; CI не заменяет профилирование |
| Интеграция PrivateMobsX API | запланирована на 3B | Публичный контракт находится в `makarovlev/PrivateMobsX:feature/privatemobsx-api` |
| Выделение палкой, /if sectors list/info/tp/show/create/delete | не начато | Этап 3A |
| FormationCalculator, SpawnDirector, PrivateMobsX spawn/death | не начато | Этап 3B |
| Трансформация мира, откат секторов и сирот | не начато | Этап 3C |
| PlaceholderAPI и consumer API tests | не начато | Этап 4 |
| Форпосты и меню | не начато | Этап 5 |
| Redis и многосерверность | не начато | Этап 6 |
| Итоговая Wiki | не начато | Этап 7 |

## Проверки Stage 2

- Последний подтверждённый GitHub Actions run #36: `mvn -B verify` — success.
- Unit-тесты выполняются на чистой Java-логике; Bukkit/Purpur интеграционные сценарии остаются в ручном чек-листе.
- Не считать Spark-бюджет <0,3 мс/тик подтверждённым до профилирования.

## Ограничения Stage 2

- `MobSpawn` пока является заглушкой по плану; реальное управление мобами подключается на 3B через публичный API PrivateMobsX.
- `/if sectors list/info/tp/show/create/delete` относятся к этапу 3A.
- Трансформация мира, автоматическое восстановление и откат сирот относятся к 3C и не реализуются на этапе 2.
