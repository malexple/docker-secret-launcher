package ru.mcs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;

public class KeePassHttpClient {
    private static final String BASE_URL = "http://localhost:" + LauncherConfig.KEEPASSHTTP_PORT;
    private final ObjectMapper mapper = new ObjectMapper();
    private String clientId;
    private byte[] aesKey;

    public KeePassHttpClient() throws Exception {
        loadAssociation();
    }

    /**
     * Загружает сохранённый ключ ассоциации и Id из локального файла.
     * Если файла нет — выполняет ассоциацию.
     */
    private void loadAssociation() throws Exception {
        Path keyPath = Paths.get(LauncherConfig.KEY_STORE_FILE);
        if (Files.exists(keyPath)) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(keyPath)) {
                props.load(in);
            }
            this.clientId = props.getProperty("clientId");
            this.aesKey = Base64.getDecoder().decode(props.getProperty("aesKey"));

            if (!testAssociate()) {
                System.out.println("Сохранённая ассоциация недействительна. Требуется повторная ассоциация.");
                associate();
            }
        } else {
            associate();
        }
    }

    /**
     * Первоначальная ассоциация с KeePassHttp.
     */
    private void associate() throws Exception {
        System.out.println("Выполняется ассоциация с KeePassHttp. Подтвердите запрос в KeePass.");

        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        String base64Key = Base64.getEncoder().encodeToString(keyBytes);

        ObjectNode request = mapper.createObjectNode();
        request.put("RequestType", "associate");
        request.put("Key", base64Key);
        String nonce = generateNonce();
        request.put("Nonce", nonce);
        request.put("Verifier", encryptWithAes(nonce, keyBytes, nonce));

        JsonNode response = sendRequest(request);
        if (!response.get("Success").asBoolean()) {
            throw new IOException("Ассоциация не удалась: " + response.path("Error").asText());
        }
        this.clientId = response.get("Id").asText();
        this.aesKey = keyBytes;
        saveAssociation();
        System.out.println("Ассоциация успешно завершена. Id: " + clientId);
    }

    /**
     * Проверяет, что сохранённая ассоциация всё ещё действительна.
     */
    private boolean testAssociate() throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("RequestType", "test-associate");
        request.put("Id", clientId);
        String nonce = generateNonce();
        request.put("Nonce", nonce);
        request.put("Verifier", encryptWithAes(nonce, aesKey, nonce));

        JsonNode response = sendRequest(request);
        return response.get("Success").asBoolean();
    }

    /**
     * Получает пароль для указанного имени переменной (поле Url в KeePass).
     */
    public String getSecret(String variableName) throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("RequestType", "get-logins");
        request.put("Id", clientId);
        String nonce = generateNonce();
        request.put("Nonce", nonce);
        request.put("Verifier", encryptWithAes(nonce, aesKey, nonce));
        request.put("Url", encryptWithAes(variableName, aesKey, nonce));

        JsonNode response = sendRequest(request);
        if (!response.get("Success").asBoolean()) {
            System.err.println("Ошибка при получении секрета для " + variableName
                    + ": " + response.path("Error").asText());
            return null;
        }

        // 🔑 Ключевое исправление: берём Nonce из ОТВЕТА
        String responseNonce = response.get("Nonce").asText();
        if (responseNonce == null || responseNonce.isEmpty()) {
            System.err.println("Ответ не содержит Nonce — не могу расшифровать поля.");
            return null;
        }

        JsonNode entries = response.get("Entries");
        if (entries != null && entries.isArray() && !entries.isEmpty()) {
            String encryptedPassword = entries.get(0).get("Password").asText();
            // Используем responseNonce как IV
            return decryptWithAes(encryptedPassword, aesKey, responseNonce);
        }
        System.err.println("Секрет для переменной '" + variableName + "' не найден в KeePass.");
        return null;
    }

    // --- Вспомогательные методы ---

    private String generateNonce() {
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        return Base64.getEncoder().encodeToString(nonce);
    }

    private String encryptWithAes(String plainText, byte[] key, String base64Iv) throws Exception {
        byte[] iv = Base64.getDecoder().decode(base64Iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }

    private String decryptWithAes(String base64CipherText, byte[] key, String base64Iv) throws Exception {
        byte[] iv = Base64.getDecoder().decode(base64Iv);
        byte[] cipherText = Base64.getDecoder().decode(base64CipherText);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        byte[] decrypted = cipher.doFinal(cipherText);
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    private JsonNode sendRequest(ObjectNode request) throws IOException {
        URL url = URI.create(BASE_URL).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(mapper.writeValueAsBytes(request));
        }
        try (InputStream is = conn.getInputStream()) {
            return mapper.readTree(is);
        }
    }

    private void saveAssociation() throws IOException {
        Path keyPath = Paths.get(LauncherConfig.KEY_STORE_FILE);
        Files.createDirectories(keyPath.getParent());
        Properties props = new Properties();
        props.setProperty("clientId", clientId);
        props.setProperty("aesKey", Base64.getEncoder().encodeToString(aesKey));
        try (OutputStream out = Files.newOutputStream(keyPath)) {
            props.store(out, "KeePassHttp Association for Docker Launcher");
        }
    }
}