# ADR-0001: PackControl Update Format

- Статус: принято для MVP
- Дата: 2026-07-29
- Область: формат обновлений, установка, экспорт и публикация сборок

## Контекст и зафиксированное состояние

На момент принятия решения проект находится на ветке `main`, коммит
`507210c` (`add a modpack compressing system into snapshots`), на один
локальный коммит впереди `origin/main`.

Проект является Java 21/Architectury multi-loader сборкой с модулями
`common`, `fabric`, `forge` и `neoforge`. В `gradle.properties` зафиксированы
Minecraft `1.21.1` и NeoForge `21.1.233`. Наличие остальных loader-модулей не
означает их поддержку новым updater: первым и единственным целевым загрузчиком
MVP является NeoForge.

До добавления этого ADR в рабочем дереве уже находились пользовательские
незакоммиченные изменения:

- изменены `PackControlScreen`, `PackControlConfig`, `ModMetadataResolver`,
  `PackSnapshotManifest`, `PackSnapshotService`, `SnapshotDownloadService` и
  `en_us.json`;
- добавлены `SnapshotProgress` и `SnapshotSaveOptions`;
- изменения развивают выбор, сохранение и загрузку snapshot, прогресс операций,
  метаданные версии/автора и поддержку выключенных модов.

Эти изменения являются исходным состоянием, а не частью данного архитектурного
решения. ADR не требует их отката или переписывания.

### Что уже есть

- `PackFileSelectionService`, `PackFileTreeService` и
  `PackControlPresetService` выбирают файлы instance.
- `ModMetadataResolver` считает SHA-1/SHA-256/SHA-512, пытается найти файл в
  Modrinth по SHA-512 и допускает ручной URL.
- `PackSnapshotService` формирует `snapshot.json` и `snapshot-files.zip`.
- `SnapshotArchiveService` создаёт и извлекает ZIP, проверяет относительные
  пути и делает копии заменяемых файлов.
- `SnapshotDownloadService` строит preview, загружает моды, проверяет SHA-256
  и пишет файлы в instance.
- `PackwizGenerator` создаёт `pack.toml` и `index.toml` как отдельный
  необязательный экспорт.
- игровой UI напрямую вызывает операции создания snapshot, установки и
  Packwiz-экспорта.

### Чего ещё нет

- стабильного контракта релиза, отдельного от Java DTO;
- строгой валидации схемы и совместимости до загрузки;
- pinned-идентификаторов Modrinth и GitHub Release Asset;
- staging, журнала транзакции, автоматического rollback и восстановления после
  падения;
- учёта ранее управляемых файлов и безопасного удаления устаревших модов;
- проверки целого архива и каждого override до изменения instance;
- полноценного `.mrpack`-экспорта;
- отдельного инструмента публикации;
- автоматических тестов формата и установки.

Текущий backup уменьшает риск потери заменяемых файлов, но установка не является
транзакционной: после ошибки часть модов уже может быть заменена, новые файлы не
удаляются автоматически, а rollback не выполняется.

## Решение

Вводится PackControl Update Format (PCUF) версии 1. Канонический релиз состоит
из JSON manifest и отдельного `overrides.zip`. Моды не включаются в
`overrides.zip`: они загружаются только из закреплённых версий Modrinth или из
публичных GitHub Release Assets.

Игровой клиент отвечает только за обнаружение, проверку, планирование и
транзакционную установку уже опубликованного релиза. Сканирование авторской
сборки, разрешение источников, создание артефактов, `.mrpack` и публикация
выполняются отдельным CLI-инструментом `packcontrol-publisher`.

### Границы MVP

- Minecraft: строго `1.21.1`.
- Loader: строго `neoforge`; версия должна точно совпадать с установленной.
- Источники модов: `modrinth` и `github-release-asset`.
- GitHub-репозитории и Release Assets: только публичные.
- Транспорт: HTTPS.
- Manifest: JSON UTF-8.
- Overrides: только `config/**`, `defaultconfigs/**` и `kubejs/**`.
- Установка требует preview, полного staging, backup и rollback.
- Изменения начинают действовать после перезапуска Minecraft.
- Самообновление мода PackControl, серверная установка, подписи релизов,
  дельта-патчи, приватные источники и автоматическое слияние конфигов не входят
  в MVP.

## Компоненты и ответственность

| Компонент | Ответственность | Не отвечает за |
| --- | --- | --- |
| `ReleaseCatalog` | Получить список/manifest опубликованных релизов из настроенного публичного репозитория, выбрать канал | Публикацию и установку |
| `ManifestParser` | Десериализовать JSON без побочных эффектов | Сетевые загрузки |
| `ManifestValidator` | Проверить schema, совместимость, пути, размеры, хеши, уникальность и source-specific ограничения | Изменение instance |
| `VersionPolicy` | Сравнить pack/client versions, каналы, запретить неявный downgrade | Файловые операции |
| `SourceResolver` | Преобразовать типизированный source в допустимые download request; реализации `ModrinthSource` и `GitHubReleaseAssetSource` | Выбор «latest» во время установки |
| `ArtifactCache` | Скачать во временный файл, ограничить размер/redirects, проверить хеш и только затем сделать доступным staging | Доверие manifest |
| `InstallPlanner` | Сравнить manifest, `.packcontrol/installed-state.json` и instance; показать add/replace/remove/conflict | Применение плана |
| `TransactionManager` | Lock, staging, backup, journal, apply, verify, commit, rollback и crash recovery | Получение токенов публикации |
| `InstalledStateStore` | Атомарно хранить последний committed release и хеши управляемых путей | Сканирование произвольных пользовательских файлов |
| `UpdateFacade` | Оркестрировать use cases и отдавать immutable progress/result в UI | Minecraft-виджеты |
| NeoForge adapter | Инициализация, путь instance, lifecycle/restart notification | Логика формата |
| Игровой UI | Preview, подтверждение, прогресс, результат и предложение перезапуска | Сканирование, экспорт, публикация |
| `packcontrol-publisher` | Scan, source pinning, manifest/ZIP generation, валидация, `.mrpack` export, публикация | Установка в пользовательский instance |

Доменные классы формата и транзакции должны находиться в `common` и не
зависеть от Minecraft UI. Сетевые источники реализуют один интерфейс, но
проверяют разные обязательные идентификаторы. Loader-specific код остаётся
тонким адаптером.

## Формат релиза

Рекомендуемое содержимое публичного GitHub Release:

```text
packcontrol-update.json
overrides.zip
<pack-id>-<pack-version>.mrpack
```

Первые два файла образуют PCUF-релиз. `.mrpack` является производным экспортом
тех же входных данных и не используется как внутренний формат установки
PackControl.

Manifest имеет следующий логический контракт. Пример сокращён, но является
валидным JSON:

```json
{
  "schemaVersion": 1,
  "pack": {
    "id": "example-pack",
    "name": "Example Pack",
    "version": "1.2.0",
    "channel": "stable",
    "releaseId": "8d03e13d-94dd-4dc4-a490-2b31df884274",
    "publishedAt": "2026-07-29T12:00:00Z",
    "minimumClientVersion": "0.2.0",
    "summary": "Example release"
  },
  "game": {
    "minecraft": "1.21.1",
    "loader": {
      "id": "neoforge",
      "version": "21.1.233"
    }
  },
  "files": [
    {
      "path": "mods/example-mod.jar",
      "size": 123456,
      "hashes": {
        "sha1": "1111111111111111111111111111111111111111",
        "sha256": "2222222222222222222222222222222222222222222222222222222222222222",
        "sha512": "33333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333"
      },
      "required": true,
      "environment": {
        "client": "required",
        "server": "required"
      },
      "source": {
        "type": "modrinth",
        "projectId": "project-id",
        "versionId": "version-id",
        "fileName": "example-mod.jar",
        "downloads": [
          "https://cdn.modrinth.com/data/project-id/versions/version-id/example-mod.jar"
        ]
      }
    },
    {
      "path": "mods/github-mod.jar",
      "size": 654321,
      "hashes": {
        "sha1": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        "sha512": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
      },
      "required": true,
      "environment": {
        "client": "required",
        "server": "required"
      },
      "source": {
        "type": "github-release-asset",
        "repository": "owner/repository",
        "releaseId": 123456,
        "tag": "v2.0.0",
        "assetId": 987654,
        "assetName": "github-mod.jar",
        "downloads": [
          "https://github.com/owner/repository/releases/download/v2.0.0/github-mod.jar"
        ]
      }
    }
  ],
  "overrides": {
    "asset": "overrides.zip",
    "size": 1234,
    "hashes": {
      "sha256": "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    },
    "entries": [
      {
        "path": "config/example.toml",
        "size": 42,
        "sha256": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
        "policy": "replace"
      },
      {
        "path": "kubejs/server_scripts/example.js",
        "size": 84,
        "sha256": "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        "policy": "replace"
      }
    ]
  }
}
```

### Инварианты manifest

- `schemaVersion` для MVP равен `1`; неизвестная версия отклоняется до любых
  изменений instance.
- `pack.id`, `pack.version` и `pack.releaseId` обязательны. Пара
  `pack.id + pack.version` не переиспользуется с другим содержимым, а
  `releaseId` уникален и неизменяем.
- Все версии файлов закреплены при публикации. У manifest нет семантики
  «latest».
- `files[].path` уникален после нормализации и для MVP имеет вид
  `mods/<filename>.jar`.
- SHA-256 обязателен для проверки PackControl; SHA-1 и SHA-512 обязательны для
  детерминированного `.mrpack`-экспорта.
- `size` неотрицателен и проверяется до хеша.
- Modrinth source требует `projectId`, `versionId`, `fileName` и HTTPS URL на
  разрешённом домене Modrinth.
- GitHub source требует `owner/repository`, numeric release/asset IDs, tag,
  точное имя asset и публичный HTTPS `browser_download_url`.
- `overrides.zip` содержит только перечисленные `entries`. Лишние, дублирующие,
  симлинк-подобные, абсолютные или выходящие за instance записи делают релиз
  недействительным.
- Разрешённые override roots: `config`, `defaultconfigs`, `kubejs`.
- Разделители путей в manifest — `/`; пустые сегменты, `.`, `..`, drive prefix,
  UNC, NUL, Windows alternate data streams, зарезервированные имена и
  case-insensitive collisions запрещены.
- Неизвестные необязательные JSON-поля игнорируются для forward compatibility;
  неизвестный source type, loader или обязательное поле отклоняются.

JSON Schema должна храниться рядом с кодом формата и проверяться тестами, но
источником истины остаются эти инварианты и versioned validator.

## Экспорт `.mrpack`

Publisher строит `.mrpack` из уже провалидированного PCUF release:

- создаёт ZIP с расширением `.mrpack`;
- пишет UTF-8 `modrinth.index.json` в корень;
- отображает `pack.version` в `versionId`, `pack.name` в `name`;
- отображает `game.minecraft` и NeoForge в `dependencies.minecraft` и
  `dependencies.neoforge`;
- отображает каждый mod в `files` с `path`, `hashes.sha1`,
  `hashes.sha512`, `downloads`, `fileSize` и `env`;
- распаковывает содержимое `overrides.zip` под каталогом `overrides/`.

Если URL не разрешён спецификацией Modrinth, отсутствуют SHA-1/SHA-512 или
получается неоднозначный путь, экспорт завершается ошибкой, а неполный
`.mrpack` не публикуется. Официальная спецификация требует ZIP,
`modrinth.index.json`, SHA-1/SHA-512 для загружаемых файлов и описывает
`overrides/`: <https://support.modrinth.com/en/articles/8802351-modrinth-modpack-format-mrpack>.

## Транзакционная установка

PackControl управляет только путями, записанными в committed
`.packcontrol/installed-state.json`. Неизвестные файлы в `mods`, `config` и `kubejs`
не удаляются.

Для одной установки создаются:

```text
.packcontrol/
  installed-state.json
  update.lock
  staging/<transaction-id>/
  transactions/<transaction-id>/journal.json
  backups/<transaction-id>/
```

Алгоритм:

1. Получить exclusive lock и убедиться, что нет незавершённой транзакции.
2. Скачать manifest, проверить его хеш/идентичность релиза, schema, версии,
   источники, пути, лимиты и совместимость.
3. Построить preview относительно `installed-state.json` и фактических хешей.
   Операции: `ADD`, `REPLACE`, `REMOVE_MANAGED`, `PRESERVE`, `CONFLICT`.
4. Скачать все моды и `overrides.zip` в staging. Проверить размер и хеши до
   распаковки. Распаковать в отдельное staging-дерево и сверить точный список
   entries и их SHA-256.
5. Проверить свободное место. Создать durable journal со списком операций и
   состоянием `PREPARED`.
6. Скопировать все существующие затрагиваемые файлы в backup. Для отсутствующих
   ранее путей записать маркер `absentBefore=true`. После синхронизации backup
   перевести journal в `BACKED_UP`.
7. Применять только файлы из staging через временный sibling и atomic move там,
   где его поддерживает файловая система. Устаревшие ранее managed-файлы
   перемещать в backup, а не удалять безвозвратно. После каждой операции
   обновлять journal.
8. Повторно проверить хеши всех managed-файлов и точное отсутствие удаляемых
   managed-путей.
9. Атомарно заменить `installed-state.json`, записать `COMMITTED`, затем очистить
   staging. Backup сохраняется согласно retention policy.
10. При любой ошибке выполнить операции journal в обратном порядке:
    восстановить backup и удалить только файлы с `absentBefore=true`. После
    проверки исходного состояния записать `ROLLED_BACK`.

При старте новый update сначала обрабатывает journal без terminal state.
Состояния `PREPARED`, `BACKED_UP` и `APPLYING` откатываются; `COMMITTED`
проверяется и только затем очищается. Неудачный rollback блокирует новые
обновления и показывает путь к backup, не маскируя исходную ошибку.

Локально изменённый managed-файл определяется сравнением с хешем в
`installed-state.json`. Политика MVP по умолчанию — `CONFLICT` и отмена установки до
явного подтверждения замены. Автоматическое трёхстороннее слияние конфигов и
KubeJS не выполняется.

## Версии

Используются независимые пространства версий:

1. `schemaVersion` — целое число версии PCUF. Клиент MVP читает только `1`.
2. `pack.version` — SemVer 2.0.0 версии содержимого сборки. Pre-release
   разрешён только в явно выбранном нестабильном канале.
3. Версия клиента PackControl — SemVer; `minimumClientVersion` запрещает
   установку формата/возможностей, которые старый клиент не понимает.
4. `minecraft` и loader version — строки совместимости, а не SemVer pack.
   Для MVP требуется точное совпадение `1.21.1`, `neoforge` и версии NeoForge.

Каналы `stable`, `beta` и `development` фильтруют доступные версии, но не
заменяют SemVer. Клиент не выполняет автоматический downgrade и не устанавливает
другой `pack.id`. Повторный `releaseId` с другим SHA-256 manifest считается
атакой или ошибкой публикации. Ручной downgrade требует отдельного
подтверждения и всё равно проходит полную транзакцию.

`installed-state.json` хранит как минимум `packId`, `packVersion`, `releaseId`,
`schemaVersion`, SHA-256 manifest, время commit и список managed path/hash.
Rollback меняет локальное installed state, но не создаёт новую pack version.

## Модель угроз

Manifest, ZIP, ответы API, redirect targets и имена файлов считаются
недоверенными. Доверенными являются код PackControl, явно настроенный
публичный release repository и локальное подтверждение пользователя.

| Угроза | Контроль MVP | Остаточный риск |
| --- | --- | --- |
| Подмена или повреждение загрузки | HTTPS, pinned IDs/URLs, size и SHA-256; для Modrinth также SHA-1/SHA-512 | Компрометация publisher/repository позволяет выпустить вредоносный, но внутренне согласованный релиз |
| Path/ZIP traversal | Нормализация, allowlist roots, запрет absolute/drive/UNC/`..`, проверка resolved path, duplicate/case collision check | Ошибки платформенных path semantics должны покрываться Windows/Linux тестами |
| Symlink/reparse escape и TOCTOU | Не следовать ссылкам, проверять каждый parent непосредственно перед записью, staging и atomic move на том же volume | Враждебный локальный процесс с правами пользователя остаётся вне полного контроля |
| SSRF и опасные redirects | Только HTTPS, allowlist по source type, ограничение redirects и повторная проверка каждого target | Разрешённый upstream может быть скомпрометирован |
| ZIP bomb/исчерпание диска | Лимиты manifest/archive/file/count, streamed hashing, preflight свободного места, запрет неизвестных ZIP entries | Ошибка оценки свободного места из-за параллельной записи |
| Частичная установка/падение JVM | Полный staging, durable journal, backup, обратимые операции, startup recovery | Сбой диска может повредить и instance, и backup |
| Replay/downgrade | SemVer policy, releaseId + manifest hash, каналы, явное подтверждение downgrade | Без подписей владелец/злоумышленник репозитория контролирует историю |
| Потеря локальных изменений | Сравнение с installed state, preview, `CONFLICT` по умолчанию, backup | Пользователь может явно подтвердить замену |
| Удаление чужих файлов | Удаляются только ранее managed paths | Переименование вручную превращает файл в unmanaged и может оставить дубликат |
| Одновременные обновления | Exclusive lock и один active journal | Несколько клиентов на сетевой файловой системе могут иметь слабые lock guarantees |
| Утечка publish credentials | В игровом клиенте нет токенов и publish API; секрет доступен только publisher process | Защита окружения publisher остаётся ответственностью автора сборки |
| Выполнение вредоносного мода/KubeJS | Только проверка происхождения и целостности, preview и доверенный publisher | Хеш не доказывает безопасность кода; sandbox модов не входит в MVP |

Криптографические подписи manifest не входят в MVP. Это осознанный остаточный
риск: HTTPS и хеши защищают транспорт и целостность, но не защищают от
компрометации учётной записи/репозитория publisher. Формат должен допускать
последующее добавление блока signatures без изменения смысла существующих
полей.

## Публикация

`packcontrol-publisher` является отдельным CLI/Gradle application, не
Minecraft-модом. Его pipeline:

1. Прочитать declarative pack project и выбранные override roots.
2. Разрешить каждый мод до конкретной версии/asset; запретить unresolved,
   `custom` и CurseForge для MVP.
3. Скачать или прочитать исходные файлы, вычислить все хеши и размеры.
4. Создать детерминированный `overrides.zip` и JSON manifest.
5. Выполнить schema, source, path и reproducibility validation.
6. Экспортировать и проверить `.mrpack`.
7. Опубликовать все артефакты в новый GitHub Release и после upload повторно
   прочитать asset metadata.

Публикация использует GitHub token только из process environment или внешнего
credential helper. Токен не пишется в pack project, manifest, game config или
логи. Игровой клиент использует публичные endpoints без аутентификации. GitHub
документирует публичное чтение release assets без токена и
`browser_download_url` для загрузки:
<https://docs.github.com/en/rest/releases/assets>.

Релиз не считается опубликованным, если хотя бы один обязательный asset
отсутствует или его server-side metadata не совпадает. Перезапись артефактов той
же версии запрещена; исправление создаёт новую pack version и `releaseId`.

## Миграция от snapshot и Packwiz-классов

Миграция выполняется поэтапно, без big-bang rewrite.

### Сопоставление

| Текущий код/поле | Целевое место |
| --- | --- |
| `PackSnapshotManifest.schemaVersion` | `schemaVersion`; старый schema не объявляется PCUF автоматически |
| `name`, `version`, `author`, `commitMessage`, `createdAt` | `pack` metadata; author/changelog могут остаться publisher metadata |
| `minecraftVersion`, `loader`, `loaderVersion` | `game.minecraft` и `game.loader` |
| `ModEntry.filename`, hashes, size, required | `files[].path`, `hashes`, `size`, `required` |
| `ModEntry.source/downloadUrl` | типизированный `source` с pinned IDs и `downloads` |
| `configs` и `kubejs` | `overrides.entries`; содержимое переупаковывается в `overrides.zip` |
| `files` вне config/defaultconfigs/kubejs | ошибка миграции MVP с явным отчётом |
| `snapshot-files.zip` | вход legacy importer, никогда не устанавливается новым engine напрямую |
| `PackFileSelectionService`/tree/presets | переиспользуемая scan/selection часть publisher |
| `ModMetadataResolver` | publisher-side source resolver; сетевое разрешение удаляется из игрового export flow |
| `SnapshotArchiveService` | основа ZIP utility после усиления path/limit/symlink validation |
| `SnapshotDownloadService` | заменяется use cases `Validate -> Plan -> Stage -> Transaction` |
| `SnapshotInstallPlan`/`SnapshotProgress` | сохраняются как UI-neutral result/event types после переименования |
| `PackwizGenerator` | legacy optional exporter; целевой exporter — `MrpackExporter` в publisher |
| `PackControlScreen` | вызывает только `UpdateFacade`; кнопки publish/export уходят из клиента |

### Этапы

1. Добавить рядом с существующим кодом package `updateformat` с immutable DTO,
   JSON Schema, parser/validator и golden fixtures. Runtime не переключать.
2. Выделить pure utilities для path validation, hashing и file selection,
   сохранив совместимые обёртки для snapshot/Packwiz.
3. Создать отдельный `publisher` module. Сначала реализовать локальную сборку
   manifest, `overrides.zip` и `.mrpack`, затем GitHub upload.
4. Добавить legacy importer как явную publisher-команду. Он читает, но не
   изменяет старый snapshot и создаёт новый output directory.
5. Добавить клиентские source adapters, validator и planner в shadow/read-only
   режиме; сравнивать новый plan с существующим preview.
6. Реализовать `InstalledStateStore`, journal, staging, backup, rollback и crash
   recovery с fault-injection tests.
7. Переключить NeoForge UI на `UpdateFacade`. Старые snapshot и Packwiz entry
   points пометить deprecated, но оставить доступными на один переходный релиз.
8. Удаление legacy-кода рассматривать отдельным ADR только после успешного
   импорта реальных snapshots и подтверждения функционального паритета.

Legacy importer:

- не изменяет и не удаляет `snapshot.json`/`snapshot-files.zip`;
- принимает Modrinth entry только при наличии хешей и конкретного URL/версии;
- принимает GitHub entry только если URL однозначно соответствует публичному
  `/releases/download/<tag>/<asset>` и publisher смог получить numeric
  release/asset IDs;
- помечает `custom`, CurseForge, отсутствующие URL и неоднозначные записи как
  blocking diagnostics;
- фильтрует ZIP до `config`, `defaultconfigs`, `kubejs`, сверяет каждый старый
  hash и создаёт новый `overrides.zip`;
- никогда не пропускает unresolved required mod молча.

## Последствия

Положительные:

- один строгий формат обслуживает preview, установку и `.mrpack`-экспорт;
- игровой клиент не содержит publish credentials и не создаёт релизы;
- установка становится восстанавливаемой после обычной ошибки и падения;
- старые snapshot можно мигрировать без их перезаписи;
- NeoForge MVP не связывает domain model с конкретным loader API.

Ограничения:

- два формата артефактов (`PCUF` и `.mrpack`) требуют cross-format fixtures;
- publisher становится обязательной частью author workflow;
- полный staging и backup требуют дополнительного места;
- exact loader matching ограничивает обновления, пока не появится явная
  compatibility policy;
- без подписей остаётся доверие к владельцу release repository;
- текущие snapshot/Packwiz-классы некоторое время существуют параллельно с
  новой архитектурой.

## Следующие небольшие задачи

1. Добавить `docs/schema/packcontrol-update-v1.schema.json` и два fixture:
   минимальный валидный релиз и набор невалидных путей.
2. Создать immutable DTO и parser без подключения к UI или сети.
3. Написать unit tests для нормализации путей на Windows/Linux, duplicate и
   case-collision detection.
4. Зафиксировать лимиты: размер manifest, число файлов, размер одного файла,
   общий download и unpacked overrides.
5. Описать `installed-state.json` и `journal.json` JSON Schema.
6. Выделить SHA/stream-copy utility из `ModMetadataResolver`.
7. Сделать read-only adapter из `PackSnapshotManifest` в migration diagnostics.
8. Создать пустой `publisher` application module с командами
   `validate`, `build` и `export-mrpack`, пока без публикации.
9. Добавить golden test, проверяющий соответствие PCUF и
   `modrinth.index.json`.
10. Добавить fault-injection test matrix для сбоев после backup, в середине
    apply, перед state commit и во время rollback.
