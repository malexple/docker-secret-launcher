# docker-secret-launcher

CLI-утилита для безопасного запуска команд и контейнеров с секретами
из базы KeePass 2 (KDBX). Секреты не хранятся в `.env`, не попадают
в `bash_history`, не светятся в `ps aux` и не оседают на диске.

## Зачем это нужно

Классическая проблема: в проекте лежит `.env` с паролями от БД, Redis,
API-ключей. Файл коммитится в git, шарится в чатах, попадает в нейронки.
`docker compose up` подхватывает переменные, но источник этих переменных
— открытый текстовый файл.

`docker-secret-launcher` решает это так:

- в `.env` остаются **только имена** переменных (`POSTGRES_PASSWORD=`),
- значения тянутся из KeePass при запуске,
- переменные живут только в окружении дочернего процесса и умирают вместе с ним,
- ничего не остаётся ни в шелле, ни в истории, ни в файлах.

## Возможности

- **KeePassHttp** — работает с запущенным KeePass 2 Classic (Windows-сценарий).
- **Прямое чтение `.kdbx`** через `KeePassJava2` (Linux-сценарий, без GUI).
- **Иерархия групп** в KeePass: `.env/<service>/<context>/<NAME>`.
- **Контексты** (`dev`, `prod`, `qa`, ...) — один `.env` для всех сред.
- **Строгий режим** (`--strict`) — запрещает fallback на корневой контекст.
- **Просмотр секретов** — `--get NAME` и `--list` без запуска чего-либо.
- **Запуск произвольной команды** — `-- docker compose up -d`,
  `-- java -jar app.war`, `-- bash`.
- **Плейсхолдеры** `{VAR}` в аргументах команды (осторожно — видны в `ps`).
- **Генерация конфигов** из шаблонов `{{VAR}}` с правами `600` и
  автоудалением после завершения (`--render`, `--cleanup`).
- **Без установки KeePass и KeePassXC** — достаточно `.kdbx` файла и JAR.

## Требования

- Java 21+ (для сборки и запуска).
- Docker (если запускаете через `docker compose`).
- Для Linux-сценария: файл `.kdbx` (база KeePass 2).
- Для Windows-сценария: запущенный KeePass 2 Classic с плагином KeePassHttp.

## Сборка

```bash
gradle clean build
```

Артефакт: `build/libs/docker-secret-launcher-1.0.0-all.jar`
(fat jar, все зависимости внутри).

## Структура KeePass

Утилита ищет секреты по пути в дереве групп KeePass:

```
.env/
└── <service>/
    ├── POSTGRES_PASSWORD        ← общий секрет для всех сред
    ├── REDIS_PASSWORD
    ├── dev/
    │   ├── POSTGRES_PASSWORD    ← переопределение для dev
    │   └── REDIS_PASSWORD
    ├── prod/
    │   ├── POSTGRES_PASSWORD
    │   └── REDIS_PASSWORD
    └── qa/
        └── POSTGRES_PASSWORD
```

Правила поиска:

| Параметры запуска | Что ищем в KeePass |
|---|---|
| `-s redis` | `.env/redis/<NAME>` |
| `-s redis -c dev` | `.env/redis/dev/<NAME>`, при неудаче → `.env/redis/<NAME>` |
| `-s redis -c prod --strict` | только `.env/redis/prod/<NAME>`, иначе ошибка |

> **Для Windows + KeePassHttp**: плагин ищет записи по полю **URL**.
> Заполняйте URL так же, как путь: `redis/dev/POSTGRES_PASSWORD`.
>
> **Для Linux + прямое чтение `.kdbx`**: поиск идёт по группам и `Title`.
> Достаточно разложить записи по папкам и назвать их именами переменных.

## `.env`

Файл `.env` содержит **только имена** переменных. Значения — в KeePass.

```dotenv
# .env
POSTGRES_DB=
POSTGRES_USER=
POSTGRES_PASSWORD=
POSTGRES_PORT=
REDIS_PASSWORD=
```

Поддерживаются комментарии (`#`), пустые строки и префикс `export`.

## Примеры использования

### Запуск docker compose (Windows, через KeePassHttp)

```powershell
cd C:\projects\my-service
java -jar C:\tools\docker-secret-launcher-all.jar --context dev
```

Launcher найдёт в KeePass `.env/my-service/dev/POSTGRES_PASSWORD` и
передаст секреты в `docker compose up -d`.

### Запуск docker compose (Linux, прямое чтение `.kdbx`)

```bash
cd /opt/services/redis
export KDBX_PATH=/opt/secrets/prod.kdbx
java -jar /opt/launcher/docker-secret-launcher-all.jar \
    --kdbx "$KDBX_PATH" --context prod --strict
```

### Запуск произвольной команды

```bash
./run.sh --kdbx /opt/secrets/prod.kdbx -s gitbucket -c prod --strict -- \
    java -jar gitbucket.war
```

### Подстановка `{VAR}` в аргументы (legacy-приложения)

```bash
./run.sh --kdbx /opt/secrets/prod.kdbx -s gitbucket -c prod -- \
    java -jar gitbucket.war -user {GITBUCKET_USER} -password {GITBUCKET_PASSWORD}
```

> ⚠️ Секреты в аргументах **видны** в `/proc/<pid>/cmdline` и `ps aux`.
> Используйте только если приложение не умеет читать секреты из env
> или из конфиг-файла.

### Генерация конфиг-файла из шаблона

`gitbucket.conf.tpl`:

```hocon
db {
  url = "jdbc:postgresql://db.local:5432/gitbucket"
  user = "{{GITBUCKET_DB_USER}}"
  password = "{{GITBUCKET_DB_PASSWORD}}"
}
```

Запуск:

```bash
./run.sh --kdbx /opt/secrets/prod.kdbx -s gitbucket -c prod --strict \
    --render gitbucket.conf.tpl:gitbucket.conf \
    --cleanup \
    -- java -jar gitbucket.war --config gitbucket.conf
```

Launcher:
1. Читает шаблон.
2. Заменяет `{{VAR}}` на значения из KeePass.
3. Пишет `gitbucket.conf` с правами `600`.
4. Запускает `java -jar gitbucket.war --config gitbucket.conf`.
5. По завершении (в том числе при Ctrl+C) удаляет `gitbucket.conf`.

### Просмотр секрета

Забыли пароль от БД:

```bash
./run.sh --kdbx /opt/secrets/prod.kdbx -s myapp -c prod --get POSTGRES_PASSWORD
```

Только значение (для скриптов):

```bash
./run.sh --kdbx /opt/secrets/prod.kdbx -s myapp -c prod --get POSTGRES_PASSWORD -q
```

### Список имён секретов в группе

```bash
./run.sh --kdbx /opt/secrets/prod.kdbx -s myapp -c prod --list
```

Выводит только имена записей, без значений.

### Интерактивный шелл с секретами

```bash
./run.sh --kdbx /opt/secrets/dev.kdbx -s myapp -c dev -- bash
```

Внутри: `echo $POSTGRES_PASSWORD`, `psql`, `redis-cli` — всё работает.

## Справка по аргументам

```
Режим запуска команды:
  java -jar launcher.jar --kdbx <path> [-s service] [-c context] [--strict] \
      -- <команда> [аргументы]

Режим .env (обратная совместимость):
  java -jar launcher.jar --kdbx <path> [-s service] [-c context] [--strict]

Режим просмотра:
  java -jar launcher.jar --kdbx <path> --get NAME  [-s service] [-c context] [-q]
  java -jar launcher.jar --kdbx <path> --list       [-s service] [-c context]

Опции:
  -s, --service <name>   имя сервиса (по умолчанию — имя текущей папки)
  -c, --context <name>   контекст: dev, prod, qa, ...
      --strict           запретить fallback, ошибка при отсутствии секрета
      --kdbx <path>      путь к файлу .kdbx (Linux, без KeePassHttp)
      --get <NAME>       вывести значение одного секрета
      --list             вывести имена секретов в группе
  -q, --quiet            для --get: только значение
      --render <tpl>:<out>  отрендерить шаблон <tpl> в файл <out>
      --cleanup          удалить сгенерированные файлы после завершения
      --show-cmd         показать команду с подставленными секретами (ОПАСНО)
```

## Модель безопасности

| Аспект | Как решено |
|---|---|
| Мастер-пароль KeePass | Вводится через `System.console().readPassword()` — без эха |
| Мастер-пароль в history | Не попадает: вводится внутри Java, не в shell |
| Мастер-пароль в памяти | `char[]`, затирается `Arrays.fill` после использования |
| Секреты в `.env` | Только имена переменных, значений нет |
| Секреты в `docker-compose.yml` | Подстановка `${VAR}` из окружения |
| Секреты в `ps aux` | Не попадают, если не использовать `{VAR}` в argv |
| Секреты в `bash_history` | Не попадают: команды без значений |
| Секреты на диске | Только в KeePass; временные конфиги удаляются |
| Секреты после запуска | Живут только в окружении дочернего процесса |

**Ограничения:**

- Мастер-пароль KeePass — единственный барьер. Если машина
  скомпрометирована и `KDBX` доступна, секреты скомпрометированы.
- Аргументы команды (`{VAR}`) видны в `/proc/<pid>/cmdline`.
  Для prod используйте env или конфиг-файлы.
- KeePassHttp слушает `localhost:19455` — локальный канал, но
  ассоциация даёт доступ ко всем записям базы.
- Сгенерированные конфиги с правами `600` видны владельцу и root.

## Ассоциация с KeePassHttp (первый запуск)

При первом запуске без `--kdbx` утилита инициирует ассоциацию:

1. Генерируется случайный 256-битный AES-ключ.
2. В KeePass всплывает диалог подтверждения.
3. Введите имя клиента (например, `docker-launcher`) и нажмите **Allow**.
4. Ключ и `Id` сохраняются в
   `%USERPROFILE%\.docker-launcher\keepasshttp.properties`.

При последующих запусках ассоциация переиспользуется — мастер-пароль
KeePass вводить не нужно, достаточно чтобы KeePass был запущен
и база разблокирована.

## Сборка fat jar

```groovy
// build.gradle
tasks.register('fatJar', Jar) {
    archiveClassifier = 'all'
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes 'Main-Class': 'ru.mcs.DockerSecretLauncher'
    }
    from sourceSets.main.output
    dependsOn configurations.runtimeClasspath
    from {
        configurations.runtimeClasspath.findAll {
            it.name.endsWith('jar')
        }.collect { zipTree(it) }
    }
    exclude 'META-INF/*.SF', 'META-INF/*.DSA', 'META-INF/*.RSA', 'META-INF/MANIFEST.MF'
}
```

## Лицензия

MIT. См. файл [LICENSE](LICENSE).