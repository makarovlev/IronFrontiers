# Changelog

## [0.1.0] — 2026-11-26

### Добавлено
- Инициализирован Maven multi-module проект: parent, API и plugin.
- Добавлены обязательные файлы документации и полная копия docs/PLAN.txt.
- Добавлен CI workflow с JDK 21 и Maven verify.
- Добавлены публичные API DTO/события, базовые интерфейсы сервисов, YAML-шаблоны, ConfigManager, сообщения, DB bootstrap и текстовый редактор sectors.yml.
- Добавлен JUnit 5 набор для YamlTextEditor.

### Известные ограничения
- Сборка и тесты требуют проверки в окружении с JDK 21 и доступом к Maven Central/PaperMC repository.
- Реальная проверка Purpur/MySQL не проводилась в этом окружении.
- Интеграция PrivateMobsX ожидает согласования публичного SPI (см. docs/QUESTIONS.md).
