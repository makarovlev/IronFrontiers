# Сценарии тестирования

## Этап 1 — основа проекта

### Автоматические тесты
1. На JDK 21 выполнить `mvn -B verify`. Последняя подтверждённая проверка Stage 2: GitHub Actions run #36 — success.
2. YamlTextEditor проверяет сохранение комментариев при append, запрет дублирующего ID без учёта регистра, удаление одного блока и проверку ID.

### Ручные тесты на Purpur 1.20.6
1. Установить PrivateMobsX и собрать IronFrontiers. Ожидание: плагин создаёт шесть YAML-файлов и подключает MySQL.
2. Отключить MySQL после успешного старта; проверить retry queue и восстановление соединения.
3. Проверить `/if reload`, restart-required ключи, Messages, aliases и permission.
4. На выключении сервера проверить, что финальные состояния успели записаться в MySQL до закрытия пула.

## Этап 2 — сектора, геометрия, KPM, состояния

### Автоматические тесты
1. Контрольный контур `(0,0),(3,0),(3,2),(5,2),(5,-4),(0,-4)` даёт 36 клеток.
2. Геометрия: одна клетка, горизонтальная/вертикальная линия, многоузловая монотонная линия, прямоугольник, L, T, зубчатая и карманная формы.
3. Валидация: диагональ, нулевое ребро, разворот на 180°, самопересечение и касание несмежных рёбер отклоняются.
4. ChunkIndex: отрицательные координаты, точечный поиск, overlap rejection, большие радиусы, расстояние до прямоугольников, разные миры.
5. KPM: weighted tenths, скользящее окно, пропуск секунд, монотонное время, idle cleanup, личная сумма, debug kills, immutable snapshots и reconfigure размера окна.
6. State machine: ENEMY capture, occupation, SAFE→HELD/DESTRUCTION, destruction loss, anti-flap, сохранённый остаток и resume window, startup grace, freeze_when_empty, infinite_safe, capture reset.
7. Registry: ID без учёта регистра, поиск по чанку и duplicate rejection.

### Ручная приёмка на тестовом Purpur 1.20.6

Подготовка:
- Использовать отдельный тестовый сервер и резервную копию БД.
- В `config.yml` включить `debug: true`, временно поставить `sector_defaults.startup_safe_seconds: 0`, `kpm.update_interval_seconds: 1`, `kpm.window_seconds: 60`, `state_persist_interval_seconds: 10`.
- Для 2–3 тестовых секторов в `sectors.yml` задать низкие понятные пороги, например `kpm_capture: 3.0`, `kpm_held: 2.0`, короткие safe/destruction/occupation timers.

Основные проверки:
1. После старта в БД должна быть строка для каждого настроенного сектора; новый сектор создаётся в ENEMY. Записи БД без сектора в YAML дают WARNING и автоматически не удаляются.
2. Выполнить `/if debug kill aa01 3 1.0`. После следующего KPM snapshot ожидается переход ENEMY→SAFE, сообщение и событие `SectorCapturedEvent`.
3. При KPM выше held порога по окончании safe_time сектор переходит в HELD; ниже порога — в DESTRUCTION.
4. В DESTRUCTION удерживать KPM выше held порога в течение `destruction_cancel_hold_seconds`: переход обратно в HELD должен сохранить остаток таймера. Повторное падение в пределах `destruction_resume_window_seconds` использует этот остаток, а не новый полный таймер.
5. Дождаться окончания DESTRUCTION: сектор становится ENEMY и получает occupation timer; debug kills в период оккупации не должны захватить сектор раньше времени.
6. Создать почти одновременно DESTRUCTION/LOSS у двух секторов. Сообщения должны объединиться в одно multi-сообщение в рамках `messages.batch_window_seconds`; разные остатки destruction time должны формировать отдельные группы.
7. При `freeze_when_empty: true` и нуле игроков SAFE/DESTRUCTION/occupation/resume timers должны стоять; после входа игрока отсчёт продолжится. Startup grace должен замораживать пути потери, но не мешать захвату ENEMY при достаточном KPM.
8. Для `infinite_safe: true` сектор остаётся SAFE без обратного отсчёта.
9. Выполнить `/if sectors set aa01 capture 5.0`; override должен сохраниться в `if_sector_override` и восстановиться после рестарта.
10. Изменить `kpm.window_seconds`, `kpm.update_interval_seconds` и `state_persist_interval_seconds`, затем выполнить `/if reload`. Новое окно должно сохранить ещё актуальные бакеты; persistence должен продолжать записывать состояния с заданным интервалом независимо от KPM tick.
11. Проверить restart-only ключи: для не-ENEMY сектора изменения `world/coords/replaces/stronghold.*` удерживаются и сообщаются как требующие рестарта; для ENEMY сектора допустимые изменения применяются после reload.
12. Оставить сектор в SAFE/DESTRUCTION, перезапустить сервер и проверить, что остаток `state_left_s`, `occupation_left_s`, `destruction_saved_s`, `resume_window_left_s` и `spawn_pause_left_s` восстанавливается без учёта времени, пока сервер был выключен.
13. Поставить `kpm.update_interval_seconds: 60` и `state_persist_interval_seconds: 10`; в MySQL проверить изменение `updated_at` примерно каждые 10 секунд, не раз в минуту.
14. `MobSpawn` на этом этапе является заглушкой: фактический спавн/пауза мобов проверяется после реализации SpawnDirector на этапе 3B.

### Spark — обязательный замер
На тестовом стенде загрузить 100 фиктивных секторов, запустить Spark profiler и измерить тик автомата. Критерий ТЗ: менее 0,3 мс на тик. Результат и конфигурацию стенда записать в docs/STATUS.md; пока замер не выполнен, производительность не считается принятой.

## Этапы 3–7
Сценарии будут добавляться в соответствующих PR по разделам 21–22 docs/PLAN.txt.
