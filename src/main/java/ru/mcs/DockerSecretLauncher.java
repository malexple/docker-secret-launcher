package ru.mcs;

import java.io.Console;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DockerSecretLauncher {

    // {VAR_NAME} — имя переменной в KeePass
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

    public static void main(String[] args) {
        try {
            String kdbxPath = null;
            String context = null;
            String service = null;
            String get = null;
            boolean list = false;
            boolean quiet = false;
            boolean strict = false;
            boolean showCmd = false;

            // Находим индекс "--" — всё после него команда
            int dashDash = -1;
            for (int i = 0; i < args.length; i++) {
                if ("--".equals(args[i])) { dashDash = i; break; }
            }

            // Парсим только то, что ДО "--"
            int endArgs = (dashDash >= 0) ? dashDash : args.length;
            for (int i = 0; i < endArgs; i++) {
                switch (args[i]) {
                    case "--kdbx":    if (i+1 < endArgs) kdbxPath = args[++i]; break;
                    case "--context": case "-c": if (i+1 < endArgs) context = args[++i]; break;
                    case "--service": case "-s": if (i+1 < endArgs) service = args[++i]; break;
                    case "--get":     if (i+1 < endArgs) get = args[++i]; break;
                    case "--list":    list = true; break;
                    case "--quiet": case "-q": quiet = true; break;
                    case "--strict":  strict = true; break;
                    case "--show-cmd": showCmd = true; break;
                    default:
                        System.err.println("Неизвестный аргумент: " + args[i]);
                        printUsage();
                        System.exit(1);
                }
            }

            // Команда после "--"
            List<String> rawCommand = new ArrayList<>();
            if (dashDash >= 0) {
                for (int i = dashDash + 1; i < args.length; i++) {
                    rawCommand.add(args[i]);
                }
            }

            if (service == null) {
                service = Paths.get("").toAbsolutePath().getFileName().toString();
            }

            // ===== Режимы --get / --list =====
            if (get != null || list) {
                if (kdbxPath == null) { System.err.println("Нужен --kdbx"); System.exit(1); }
                char[] pwd = readPasswordSilently();
                try {
                    if (list) {
                        KdbxSecretReader.listNames(kdbxPath, pwd, service, context, strict)
                                .forEach(System.out::println);
                        return;
                    }
                    String v = KdbxSecretReader.getOne(kdbxPath, pwd, service, context, get, strict);
                    if (v == null) { System.err.println("Не найдено: " + get); System.exit(2); }
                    System.out.println(quiet ? v : (get + "=" + v));
                    return;
                } finally {
                    Arrays.fill(pwd, '\0');
                }
            }

            // ===== Режим запуска команды =====

            // Если команда не задана — по умолчанию docker compose
            if (rawCommand.isEmpty()) {
                rawCommand = List.of("docker", "compose", "up", "-d");
            }

            // 1. Собираем имена переменных:
            //    - из плейсхолдеров {VAR} в команде
            //    - из .env (если файл есть — для обратной совместимости)
            Set<String> needed = new LinkedHashSet<>();
            for (String arg : rawCommand) {
                Matcher m = PLACEHOLDER.matcher(arg);
                while (m.find()) {
                    needed.add(m.group(1));
                }
            }
            // .env опционален, если команда уже содержит плейсхолдеры
            if (needed.isEmpty() && Paths.get(LauncherConfig.ENV_FILE).toFile().exists()) {
                needed.addAll(EnvFileReader.readVariableNames(LauncherConfig.ENV_FILE));
            }

            if (needed.isEmpty()) {
                System.err.println("Не найдено ни плейсхолдеров {VAR}, ни .env. Нечего делать.");
                System.exit(1);
            }
            System.out.println("Нужны секреты: " + needed);

            // 2. Получаем секреты из KeePass
            Map<String, String> secrets = new HashMap<>();
            if (kdbxPath != null) {
                char[] pwd = readPasswordSilently();
                try {
                    for (String name : needed) {
                        String v = KdbxSecretReader.getOne(kdbxPath, pwd, service, context, name, strict);
                        if (v != null) {
                            secrets.put(name, v);
                            System.out.println("  [+] " + name);
                        } else if (strict) {
                            System.err.println("  [-] " + name + " не найден (strict)");
                            System.exit(1);
                        } else {
                            System.err.println("  [-] " + name + " не найден");
                        }
                    }
                } finally {
                    Arrays.fill(pwd, '\0');
                }
            } else {
                // через KeePassHttp
                KeePassHttpClient kp = new KeePassHttpClient();
                for (String name : needed) {
                    String v = kp.getSecretWithContext(service, context, name, !strict);
                    if (v != null) secrets.put(name, v);
                    else if (strict) { System.err.println("  [-] " + name + " не найден"); System.exit(1); }
                }
            }

            // 3. Подставляем плейсхолдеры в команду
            List<String> finalCommand = new ArrayList<>(rawCommand.size());
            for (String arg : rawCommand) {
                Matcher m = PLACEHOLDER.matcher(arg);
                StringBuilder sb = new StringBuilder();
                int last = 0;
                while (m.find()) {
                    sb.append(arg, last, m.start());
                    String name = m.group(1);
                    String value = secrets.get(name);
                    if (value == null) {
                        System.err.println("Нет значения для {" + name + "} — оставляю как есть");
                        sb.append(m.group(0));
                    } else {
                        sb.append(value);
                    }
                    last = m.end();
                }
                sb.append(arg.substring(last));
                finalCommand.add(sb.toString());
            }

            // 4. Запускаем
            if (showCmd) {
                System.out.println("Команда: " + String.join(" ", finalCommand));
            } else {
                System.out.println("Запуск команды (аргументы не показываются во избежание утечки)…");
            }

            ProcessBuilder pb = new ProcessBuilder(finalCommand);
            pb.inheritIO();
            // Дополнительно прокидываем секреты в env — на случай, если приложение умеет читать из env
            pb.environment().putAll(secrets);

            int code = pb.start().waitFor();
            System.out.println("Код завершения: " + code);
            System.exit(code);

        } catch (Exception e) {
            System.err.println("Ошибка: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static char[] readPasswordSilently() throws Exception {
        Console console = System.console();
        if (console != null) return console.readPassword("Master password: ");
        System.err.println("Нет TTY, читаю пароль из stdin");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(System.in))) {
            String line = r.readLine();
            if (line == null) throw new Exception("Пароль не получен");
            return line.toCharArray();
        }
    }

    private static void printUsage() {
        System.err.println("""
            Режим запуска команды:
              java -jar launcher.jar --kdbx <path> [-s service] [-c context] [--strict] \\
                  -- <команда> [аргументы]
            
              В аргументах можно использовать {VAR} — будут подставлены значения из KeePass.
              Также все секреты автоматически прокидываются в переменные окружения команды.
            
            Режим .env (обратная совместимость):
              java -jar launcher.jar --kdbx <path> [-s service] [-c context] [--strict]
              (без "--" — берётся .env, плейсхолдеры не используются)
            
            Режим просмотра:
              java -jar launcher.jar --kdbx <path> --get NAME  [-s service] [-c context] [-q]
              java -jar launcher.jar --kdbx <path> --list       [-s service] [-c context]
            
            Опции:
              -s, --service <name>   имя сервиса (по умолчанию — имя текущей папки)
              -c, --context <name>   контекст: dev, prod, qa, ...
                  --strict           запретить fallback, ошибка при отсутствии секрета
                  --kdbx <path>      путь к файлу .kdbx
                  --get <NAME>       вывести значение одного секрета
                  --list             вывести имена секретов в группе
              -q, --quiet            для --get: только значение
                  --show-cmd         показать команду с подставленными секретами (ОПАСНО!)
            
            ⚠️  ВНИМАНИЕ: секреты, переданные аргументами, видны в /proc/<pid>/cmdline
                и в ps aux. Если приложение умеет читать секреты из env — используйте
                переменные окружения через ${VAR}, а не {VAR}.
            """);
    }
}