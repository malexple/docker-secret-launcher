package ru.mcs;

public class LauncherConfig {
    public static final String ENV_FILE = ".env";
    public static final String KEY_STORE_FILE =
            System.getProperty("user.home") + "\\.docker-launcher\\keepasshttp.properties";
    public static final int KEEPASSHTTP_PORT = 19455;
    public static final String CLIENT_NAME = "docker-launcher";
}