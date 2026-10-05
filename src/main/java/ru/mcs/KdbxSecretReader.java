package ru.mcs;

import org.linguafranca.pwdb.Credentials;
import org.linguafranca.pwdb.Entry;
import org.linguafranca.pwdb.Group;
import org.linguafranca.pwdb.kdbx.KdbxCreds;
import org.linguafranca.pwdb.kdbx.simple.SimpleDatabase;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class KdbxSecretReader {

    private static SimpleDatabase open(String kdbxPath, char[] password) throws Exception {
        Credentials creds = new KdbxCreds(
                new String(password).getBytes(StandardCharsets.UTF_8));
        try (InputStream is = Files.newInputStream(Paths.get(kdbxPath))) {
            return SimpleDatabase.load(creds, is);
        }
    }

    private static Group<?, ?, ?, ?> findEnvRoot(SimpleDatabase db) throws Exception {
        Group<?, ?, ?, ?> envRoot = findGroup(db.getRootGroup(), ".env");
        if (envRoot == null) {
            throw new Exception("Группа '.env' не найдена в базе");
        }
        return envRoot;
    }

    private static Group<?, ?, ?, ?> resolveTarget(Group<?, ?, ?, ?> envRoot,
                                                   String service,
                                                   String context,
                                                   boolean strict) throws Exception {
        Group<?, ?, ?, ?> serviceGroup = findGroup(envRoot, service);
        if (serviceGroup == null) {
            throw new Exception("Группа '.env/" + service + "' не найдена в базе");
        }

        if (context == null) {
            return serviceGroup;
        }

        Group<?, ?, ?, ?> contextGroup = findGroup(serviceGroup, context);
        if (contextGroup != null) {
            return contextGroup;
        }

        if (strict) {
            throw new Exception("Группа '.env/" + service + "/" + context
                    + "' не найдена (strict)");
        }

        System.out.println("  Контекст '" + context
                + "' не найден — берём корень сервиса '" + service + "'");
        return serviceGroup;
    }

    public static Map<String, String> extract(String kdbxPath,
                                              char[] password,
                                              String service,
                                              String context,
                                              Set<String> varNames,
                                              boolean strict) throws Exception {
        SimpleDatabase db = open(kdbxPath, password);
        Group<?, ?, ?, ?> envRoot = findEnvRoot(db);
        Group<?, ?, ?, ?> target = resolveTarget(envRoot, service, context, strict);

        Map<String, String> result = new HashMap<>();
        for (String varName : varNames) {
            Entry<?, ?, ?, ?> entry = findEntry(target, varName);
            if (entry != null) {
                result.put(varName, entry.getPassword());
            } else if (strict) {
                throw new Exception("Секрет '" + varName
                        + "' не найден в группе '" + target.getName() + "' (strict)");
            } else {
                System.err.println("  [-] " + varName + " — не найден");
            }
        }
        return result;
    }

    public static String getOne(String kdbxPath,
                                char[] password,
                                String service,
                                String context,
                                String name,
                                boolean strict) throws Exception {
        SimpleDatabase db = open(kdbxPath, password);
        Group<?, ?, ?, ?> envRoot = findEnvRoot(db);
        Group<?, ?, ?, ?> target = resolveTarget(envRoot, service, context, strict);

        Entry<?, ?, ?, ?> entry = findEntry(target, name);
        if (entry == null) {
            if (strict) {
                throw new Exception("Секрет '" + name
                        + "' не найден в группе '" + target.getName() + "' (strict)");
            }
            return null;
        }
        return entry.getPassword();
    }

    public static List<String> listNames(String kdbxPath,
                                         char[] password,
                                         String service,
                                         String context,
                                         boolean strict) throws Exception {
        SimpleDatabase db = open(kdbxPath, password);
        Group<?, ?, ?, ?> envRoot = findEnvRoot(db);
        Group<?, ?, ?, ?> target = resolveTarget(envRoot, service, context, strict);

        List<String> names = new ArrayList<>();
        for (Entry<?, ?, ?, ?> e : target.getEntries()) {
            names.add(e.getTitle());
        }
        Collections.sort(names);
        return names;
    }

    public static List<String> listSubgroups(String kdbxPath,
                                             char[] password,
                                             String service,
                                             String context,
                                             boolean strict) throws Exception {
        SimpleDatabase db = open(kdbxPath, password);
        Group<?, ?, ?, ?> envRoot = findEnvRoot(db);
        Group<?, ?, ?, ?> target = resolveTarget(envRoot, service, context, strict);

        List<String> names = new ArrayList<>();
        for (Group<?, ?, ?, ?> g : target.getGroups()) {
            names.add(g.getName());
        }
        Collections.sort(names);
        return names;
    }

    // ==================== Вспомогательные методы ====================

    private static Group<?, ?, ?, ?> findGroup(Group<?, ?, ?, ?> parent, String name) {
        for (Group<?, ?, ?, ?> g : parent.getGroups()) {
            if (name.equals(g.getName())) {
                return g;
            }
        }
        return null;
    }

    private static Entry<?, ?, ?, ?> findEntry(Group<?, ?, ?, ?> group, String title) {
        for (Entry<?, ?, ?, ?> e : group.getEntries()) {
            if (title.equals(e.getTitle())) {
                return e;
            }
        }
        return null;
    }

    public static void ensureReadable(String kdbxPath) throws Exception {
        Path p = Paths.get(kdbxPath);
        if (!Files.exists(p)) {
            throw new Exception("Файл базы не найден: " + p.toAbsolutePath());
        }
        if (!Files.isReadable(p)) {
            throw new Exception("Файл базы недоступен для чтения: " + p.toAbsolutePath());
        }
        if (!Files.isRegularFile(p)) {
            throw new Exception("Указанный путь не является файлом: " + p.toAbsolutePath());
        }
    }
}