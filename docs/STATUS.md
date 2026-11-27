# Статус разработки Iron Frontiers

Последнее обновление: 2026-11-26

| Возможность | Статус | Как проверить |
|---|---|---|
| Инициализация Git, README, полный docs/PLAN.txt и отчётные файлы | готово | Проверить наличие файлов; docs/PLAN.txt — точная копия исходного ТЗ |
| Maven baseline в main | проверено CI | Main run #7: `mvn -B verify` завершился success |
| Maven multi-module: parent + API + plugin | проверено CI | Feature run #8: `mvn -B verify` на JDK 21 |
| Java 21 Enforcer и GitHub Actions CI | проверено | Feature run #8 завершился success |
| Публичные DTO и события API | проверено CI | API-модуль компилируется; consumer-плагин будет проверен на этапе 4 |
| plugin.yml, /if reload, TAB skeleton | готово (каркас) | Запустить Purpur 1.20.6 с PrivateMobsX; проверить aliases, permission и /if reload |
| Шесть YAML-файлов с русскими комментариями | готово (шаблоны) | Первый запуск создаёт config.yml, messages.yml, sectors.yml, mobs.yml, blueprints.yml, menus.yml |
| ConfigManager, валидаторы, restart-required reload | готово (исходный код) | Изменить database.host и transform.tick_budget_ms; /if reload удерживает первое значение и применяет второе |
| Messages: MiniMessage + legacy &-коды и prefix | готово (исходный код) | Проверить сообщение в обоих форматах на сервере |
| YamlTextEditor и JUnit 5 тесты | проверено CI | Feature run #8: тесты YamlTextEditor прошли в составе `mvn -B verify` |
| Центральный планировщик и именованные пулы | готово (исходный код) | Проверить одну Bukkit-задачу, freeze/resume; Redis executor отсутствует при multiserver.enabled=false |
| MySQL Hikari, if_schema, if_sector_state, StateStore | готово (исходный код; live DB не проверена) | Подключить тестовую MySQL; ожидается применение миграции и загрузка строк |
| Runtime DB outage: bounded write-behind и exponential retry | готово (исходный код; live outage не проверен) | Остановить MySQL после запуска; проверить очередь, восстановление и WARNING не чаще раза в минуту |
| Soft-dependency adapters | каркас готов | Запускать без каждого softdepend; ожидается WARNING, сам IronFrontiers не отключается |
| PrivateMobsX SPI | ожидает согласования | См. docs/QUESTIONS.md и docs/decisions/001-privatemobsx-integration.md |
| Геометрия, KPM и автомат состояний | не начато | Этап 2 |
| Выделение, спавн и трансформация территории | не начато | Этап 3 |
| PlaceholderAPI и consumer API tests | не начато | Этап 4 |
| Форпосты и меню | не начато | Этап 5 |
| Redis и многосерверность | не начато | Этап 6 |
| Итоговая Wiki | не начато | Этап 7 |

## Производительность

Замеры Spark пока не проводились: тестовый Purpur 1.20.6 и копия war world не предоставлены. Бюджет <0,5 мс/тик не считается подтверждённым до профилирования.

## Ограничения приёмки этапа 1

- CI-компиляция и JUnit подтверждены GitHub Actions.
- Фактический запуск на Purpur 1.20.6 и подключение к тестовой MySQL в этой среде не выполнялись.
- Runtime-адаптер PrivateMobsX нельзя завершить без согласованного публичного SPI.
- LICENSE не добавлен: лицензию должен выбрать владелец репозитория.
