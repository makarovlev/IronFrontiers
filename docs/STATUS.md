# Статус разработки Iron Frontiers

Последнее обновление: 2026-11-26

| Возможность | Статус | Как проверить |
|---|---|---|
| Инициализация Git, README, полный docs/PLAN.txt, отчётные файлы | готово | Сверить наличие файлов; docs/PLAN.txt — точная копия исходного ТЗ |
| Maven multi-module: parent + API + plugin | в работе | Выполнить `mvn -B verify` на JDK 21; ожидается два модуля |
| Java 21 Enforcer и GitHub Actions CI | проверено для коммита 2db66ce; текущий коммит проверяется | Проверить workflow Maven verify; JDK, отличный от 21, должен отклоняться Enforcer |
| Публичные DTO и события API | в работе | Скомпилировать API-модуль; consumer-плагин — этап 4 |
| plugin.yml, /if reload, TAB skeleton | в работе | Запустить Purpur 1.20.6 с PrivateMobsX; проверить permission и /if reload |
| Шесть YAML-файлов с русскими комментариями | готово (шаблоны) | Первый запуск создаёт config.yml, messages.yml, sectors.yml, mobs.yml, blueprints.yml, menus.yml |
| ConfigManager, валидаторы, restart-required reload | в работе | Изменить database.host и transform.tick_budget_ms; /if reload удерживает первое значение и применяет второе |
| Messages: MiniMessage + legacy &-коды и prefix | в работе | Проверить сообщение в обоих форматах |
| YamlTextEditor и JUnit 5 тесты | готово (код, CI-проверка ожидается) | Выполнить `mvn -B -pl ironfrontiers-plugin -am test`; проверить сохранение комментариев и удаление одного блока |
| Центральный планировщик и именованные пулы | в работе | Проверить одну Bukkit-задачу; Redis executor отсутствует при multiserver.enabled=false |
| MySQL Hikari, if_schema, if_sector_state, StateStore | в работе | Подключить тестовую MySQL; ожидается создание схемы и загрузка строк |
| Недоступная БД и безопасный degraded mode | в работе | Остановить тестовую БД; проверить SEVERE и повторные подключения с backoff |
| Soft-dependency adapters | каркас готов | Запускать без каждого softdepend; ожидается WARNING и отключение только связанной функции |
| PrivateMobsX SPI | ожидает согласования | См. docs/QUESTIONS.md и docs/decisions/001-privatemobsx-integration.md |
| Геометрия, KPM и автомат состояний | не начато | Этап 2 |
| Выделение, спавн и трансформация территории | не начато | Этап 3 |
| PlaceholderAPI и consumer API tests | не начато | Этап 4 |
| Форпосты и меню | не начато | Этап 5 |
| Redis и многосерверность | не начато | Этап 6 |
| Итоговая Wiki | не начато | Этап 7 |

## Производительность

Замеры Spark пока не проводились: тестовый Purpur 1.20.6 и копия war world не предоставлены. Бюджет <0,5 мс/тик не считается подтверждённым до профилирования.
