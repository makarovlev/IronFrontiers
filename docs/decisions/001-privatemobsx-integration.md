# Решение 001 — граница интеграции PrivateMobsX

Дата: 2026-11-26

## Наблюдение

В доступной ветке main PrivateMobsX объекты PrivateMob содержат реальные Bukkit Entity; видимость фильтруется ProtocolLib EntityHider. Публичного сервиса PrivateMobsApi, неизменяемых DTO и событий PrivateMobSpawn/Death/Despawn из раздела 18.1 в исходниках не обнаружено.

## Временное решение

Iron Frontiers определяет собственный внутренний PrivateMobsBridge и тестовый FakePrivateMobsBridge. Runtime-код не обращается к PrivateMobManager, EntityHider и другим деталям реализации PrivateMobsX. Полноценный мост подключается после согласования/публикации privatemobsx-api.

## Причина

Прямая зависимость от внутренних классов нарушила бы расширяемость и привязала основной плагин к незафиксированному контракту. Fake bridge не используется как production-путь.
