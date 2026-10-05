package ru.mcs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class EnvFileReader {

    /**
     * Читает .env и возвращает только ИМЕНА переменных (без значений).
     * Поддерживает:
     *   KEY=value
     *   KEY=
     *   export KEY=value
     *   # комментарии
     *   пустые строки
     */
    public static Set<String> readVariableNames(String envFilePath) throws IOException {
        Path path = Paths.get(envFilePath);
        if (!Files.exists(path)) {
            throw new IOException("Файл .env не найден: " + path.toAbsolutePath());
        }

        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        Set<String> names = new LinkedHashSet<>();

        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i).trim();

            // пустая строка
            if (raw.isEmpty()) continue;

            // комментарий
            if (raw.startsWith("#")) continue;

            // снимаем "export " если есть
            if (raw.startsWith("export ")) {
                raw = raw.substring("export ".length()).trim();
            }

            // должно быть KEY=...
            int eq = raw.indexOf('=');
            if (eq <= 0) {
                System.err.println("Пропущена строка " + (i + 1) + " в .env (нет '='): " + lines.get(i));
                continue;
            }

            String key = raw.substring(0, eq).trim();
            if (key.isEmpty()) {
                System.err.println("Пропущена строка " + (i + 1) + " в .env (пустое имя): " + lines.get(i));
                continue;
            }

            names.add(key);
        }

        return names;
    }
}