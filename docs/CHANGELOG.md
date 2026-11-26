# Changelog

## [0.1.0] — 2026-11-26

### Добавлено
- Инициализирован Maven multi-module проект: parent, API и plugin.
- Добавлены обязательные отчётные файлы и полная копия docs/PLAN.txt.
- Добавлен GitHub Actions CI для JDK 21 и Maven verify.
- Добавлены API DTO/события, базовые интерфейсы сервисов, YAML-шаблоны, ConfigManager, сообщения, DB bootstrap, versioned migration и text editor sectors.yml.
- Добавлены HikariCP, асинхронный JdbcStateStore, bounded write-behind и последовательное сохранение обновлений сектора с exponential retry при временной потере MySQL.
- Добавлены JUnit 5 тесты YamlTextEditor.

### Проверка
- GitHub Actions run #5 для коммита ee303fa завершился успешно: `mvn -B verify`, включая JUnit 5.
- Для main отдельно выполняется проверка Maven baseline.
- Live-запуск на Purpur 1.20.6 и реальная MySQL-проверка не выполнены в этой среде.

### Известные ограничения
- Runtime-интеграция PrivateMobsX ожидает согласования API из раздела 18.1.
- Лицензия репозитория ожидает решения владельца.
