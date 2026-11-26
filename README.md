# Iron Frontiers

Плагин захвата территорий для RPG-сервера GachiCraft.

## Технический стек

- Java 21, Maven 3.9+
- Paper API 1.20.6, целевая серверная платформа Purpur 1.20.6
- MySQL/MariaDB для глобального состояния
- Redis запускается только при `multiserver.enabled: true`

## Структура

- `ironfrontiers-api` — публичные интерфейсы, DTO и события.
- `ironfrontiers-plugin` — серверная реализация.

## Сборка

Требуется JDK 21 и Maven 3.9 или новее:

```bash
mvn -B verify
```

Готовый плагин: `ironfrontiers-plugin/target/`.

Работа ведётся в ветках `feature/stageN-*`, слияние в `main` — только через Pull Request. Не коммитьте пароли, локальные настройки и файлы миров.
