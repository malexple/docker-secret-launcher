package ru.mcs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConfigRenderer {

    // {{VAR_NAME}} — двойные фигурные
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}}");

    /**
     * Возвращает множество имён переменных, встречающихся в шаблоне.
     * Нужно, чтобы заранее знать, что тянуть из KeePass.
     */
    public static Set<String> findPlaceholders(String templatePath) throws IOException {
        String content = Files.readString(Paths.get(templatePath), StandardCharsets.UTF_8);
        Set<String> names = new HashSet<>();
        Matcher m = PLACEHOLDER.matcher(content);
        while (m.find()) names.add(m.group(1));
        return names;
    }

    /**
     * Рендерит шаблон в файл. Права — 600 на POSIX.
     * @param strict если true — падать при отсутствии значения для плейсхолдера
     */
    public static void render(String templatePath, String outputPath,
                              Map<String, String> secrets, boolean strict) throws IOException {
        String content = Files.readString(Paths.get(templatePath), StandardCharsets.UTF_8);

        Matcher m = PLACEHOLDER.matcher(content);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(content, last, m.start());
            String name = m.group(1);
            String value = secrets.get(name);
            if (value == null) {
                if (strict) {
                    throw new IOException("Нет значения для {{" + name + "}} (strict)");
                }
                System.err.println("  [!] {{" + name + "}} — значение не найдено, оставляю плейсхолдер");
                sb.append(m.group(0));
            } else {
                sb.append(value);
            }
            last = m.end();
        }
        sb.append(content.substring(last));

        Path out = Paths.get(outputPath);
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.writeString(out, sb, StandardCharsets.UTF_8);

        // Права 600 (POSIX). На Windows молча пропустится.
        try {
            Files.setPosixFilePermissions(out,
                    PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows — ничего не делаем
        }

        System.out.println("  [+] " + templatePath + " → " + outputPath);
    }

    /**
     * Удаляет файл, если он существует. Используется для очистки после запуска.
     */
    public static void cleanup(String path) {
        try {
            Path p = Paths.get(path);
            if (Files.deleteIfExists(p)) {
                System.out.println("  [x] Удалён " + path);
            }
        } catch (IOException e) {
            System.err.println("  [!] Не удалось удалить " + path + ": " + e.getMessage());
        }
    }
}