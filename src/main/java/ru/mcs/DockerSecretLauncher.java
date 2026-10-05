package ru.mcs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DockerSecretLauncher {

    public static void main(String[] args) {
        try {
            // Парсим аргументы командной строки
            String composeFile = null;
            for (int i = 0; i < args.length; i++) {
                if (("-f".equals(args[i]) || "--file".equals(args[i])) && i + 1 < args.length) {
                    composeFile = args[i + 1];
                    i++;
                }
            }

            // 1. Читаем .env
            Set<String> variableNames = EnvFileReader.readVariableNames(LauncherConfig.ENV_FILE);
            if (variableNames.isEmpty()) {
                System.err.println("В файле " + LauncherConfig.ENV_FILE + " не найдено переменных.");
                System.exit(1);
            }
            System.out.println("Переменные из .env: " + variableNames);

            // 2. KeePassHttp
            KeePassHttpClient keepass = new KeePassHttpClient();

            // 3. Секреты
            Map<String, String> secrets = new HashMap<>();
            for (String varName : variableNames) {
                String secret = keepass.getSecret(varName);
                if (secret != null) {
                    secrets.put(varName, secret);
                    System.out.println("  ✓ " + varName + " — секрет получен");
                } else {
                    System.err.println("  ✗ " + varName + " — секрет НЕ найден");
                }
            }
            if (secrets.isEmpty()) {
                System.err.println("Не удалось получить ни одного секрета.");
                System.exit(1);
            }

            // 4. Собираем команду
            List<String> command = new ArrayList<>();
            command.add("docker");
            command.add("compose");
            if (composeFile != null) {
                command.add("-f");
                command.add(composeFile);
                System.out.println("Используется compose-файл: " + composeFile);
            }
            command.add("up");
            command.add("-d");

            runCommand(command, secrets);

        } catch (Exception e) {
            System.err.println("Критическая ошибка: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void runCommand(List<String> command, Map<String, String> secrets) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();

        Map<String, String> environment = pb.environment();
        environment.putAll(secrets);

        System.out.println("Запуск: " + String.join(" ", command));
        Process process = pb.start();
        int exitCode = process.waitFor();
        System.out.println("Команда завершена с кодом: " + exitCode);
    }
}