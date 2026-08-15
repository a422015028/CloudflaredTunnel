package com.cloudflared.tunnel.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.cloudflared.tunnel.model.Config;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CloudflaredRunner {

    private static final String TAG = "CloudflaredRunner";
    private static final String PREFS = "cloudflared_prefs";
    private static final String KEY_MODE = "run_mode";
    public static final String MODE_ROOT = "root";
    public static final String MODE_NON_ROOT = "non_root";

    private static final String BINARY_NAME = "cloudflared";
    private static final String NATIVE_BINARY = "libcloudflared.so";
    private static final String ROOT_DIR = "/data/local/tmp/cloudflared";
    private static final String ROOT_BINARY_PATH = ROOT_DIR + "/cloudflared";
    private static final String ROOT_TOKEN_PATH = ROOT_DIR + "/token";
    private static final String ROOT_LOG_PATH = ROOT_DIR + "/cloudflared.log";
    private static final String ROOT_CONFIG_PATH = ROOT_DIR + "/config.yml";
    private static final String ROOT_CRED_PATH = ROOT_DIR + "/credentials.json";

    private static final Object LOCK = new Object();
    private static volatile Process nonRootProcess;
    private static volatile String nonRootState = "stopped";
    private static volatile String nonRootError = "";
    private static volatile boolean connected = false;
    private static volatile Context appContext;

    public static String getRunMode(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getString(KEY_MODE, MODE_NON_ROOT);
    }

    public static void setRunMode(Context context, String mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_MODE, mode).apply();
    }

    public static File getBinaryFile(Context context) {
        String dir = context.getApplicationInfo().nativeLibraryDir;
        return new File(dir == null ? "" : dir, NATIVE_BINARY);
    }

    public static boolean isBinaryAvailable(Context context) {
        return getBinaryFile(context).isFile();
    }

    public static boolean isRunning(Context context) {
        if (MODE_ROOT.equals(getRunMode(context))) {
            return RootUtils.isProcessRunning(BINARY_NAME);
        } else {
            synchronized (LOCK) {
                return nonRootProcess != null && nonRootProcess.isAlive();
            }
        }
    }

    public static boolean start(Context context, String token, String protocol) {
        appContext = context.getApplicationContext();
        if (MODE_ROOT.equals(getRunMode(context))) {
            return startRoot(context, token, protocol);
        } else {
            return startNonRoot(context, token, protocol);
        }
    }

    public static boolean start(Context context, Config config) {
        appContext = context.getApplicationContext();
        if (config.isLocal()) {
            if (MODE_ROOT.equals(getRunMode(context))) {
                return startRootLocal(context, config);
            } else {
                Log.e(TAG, "本地管理隧道配置需要ROOT模式");
                return false;
            }
        } else {
            return start(context, config.getToken(), config.getProtocol());
        }
    }

    public static boolean stop(Context context) {
        if (MODE_ROOT.equals(getRunMode(context))) {
            RootUtils.CommandResult result = RootUtils.stopProcess(BINARY_NAME);
            return result.isSuccess();
        } else {
            return stopNonRoot();
        }
    }

    public static String getState(Context context) {
        if (MODE_ROOT.equals(getRunMode(context))) {
            if (!RootUtils.isProcessRunning(BINARY_NAME)) return "stopped";
            return checkRootLogState();
        } else {
            synchronized (LOCK) {
                if (nonRootProcess != null && nonRootProcess.isAlive()) {
                    return connected ? "connected" : "connecting";
                }
                return nonRootError.isEmpty() ? "stopped" : "error";
            }
        }
    }

    public static String getError(Context context) {
        if (MODE_ROOT.equals(getRunMode(context))) {
            if (!RootUtils.isProcessRunning(BINARY_NAME)) return "";
            return checkRootLogError();
        } else {
            synchronized (LOCK) {
                return nonRootError;
            }
        }
    }

    public static boolean isConnected(Context context) {
        if (MODE_ROOT.equals(getRunMode(context))) {
            if (!RootUtils.isProcessRunning(BINARY_NAME)) return false;
            return "connected".equals(checkRootLogState());
        } else {
            return connected;
        }
    }

    // ==================== Tunnel Login & Create ====================

    public interface LoginCallback {
        void onUrlReady(String url);
        void onSuccess();
        void onError(String error);
    }

    public interface CreateTunnelCallback {
        void onSuccess(String tunnelId, String credentialsJson);
        void onError(String error);
    }

    private static void forceIPv4Root(boolean enable) {
        if (enable) {
            RootUtils.executeRootCommand("ip6tables -C OUTPUT -p tcp --dport 443 -j REJECT 2>/dev/null || ip6tables -I OUTPUT 1 -p tcp --dport 443 -j REJECT 2>/dev/null; true");
        } else {
            RootUtils.executeRootCommand("ip6tables -D OUTPUT -p tcp --dport 443 -j REJECT 2>/dev/null; true");
        }
    }

    private static File getCloudflaredHomeDir(Context context) {
        if (MODE_ROOT.equals(getRunMode(context))) {
            return new File(ROOT_DIR);
        } else {
            return new File(context.getNoBackupFilesDir(), "cloudflared_home");
        }
    }

    private static File getCertFile(Context context) {
        return new File(getCloudflaredHomeDir(context), ".cloudflared/cert.pem");
    }

    public static boolean isLoggedIn(Context context) {
        if (MODE_ROOT.equals(getRunMode(context))) {
            RootUtils.CommandResult r = RootUtils.executeRootCommand(
                    "test -f \"" + getCertFile(context).getAbsolutePath() + "\" && echo yes");
            return r.isSuccess() && r.output.trim().equals("yes");
        } else {
            return getCertFile(context).isFile();
        }
    }

    public static void login(Context context, LoginCallback callback) {
        final Context appCtx = context.getApplicationContext();
        final File binary = getBinaryFile(appCtx);
        final File homeDir = getCloudflaredHomeDir(appCtx);

        if (!binary.isFile()) {
            callback.onError("二进制文件未找到");
            return;
        }
        if (!MODE_ROOT.equals(getRunMode(appCtx))) {
            callback.onError("本地管理隧道配置需要ROOT模式");
            return;
        }

        new Thread(() -> {
            loginRoot(appCtx, binary, homeDir, callback);
        }, "cloudflared-login").start();
    }

    private static void loginRoot(Context context, File binary, File homeDir, LoginCallback callback) {
        forceIPv4Root(true);
        try {
            String logPath = ROOT_DIR + "/login.log";
            String[] commands = {
                    "mkdir -p \"" + homeDir.getAbsolutePath() + "/.cloudflared\"",
                    "truncate -s 0 \"" + logPath + "\" 2>/dev/null; true",
                    "HOME=\"" + homeDir.getAbsolutePath() + "\" nohup \"" + binary.getAbsolutePath() + "\" --no-autoupdate tunnel login > \"" + logPath + "\" 2>&1 &"
            };
            RootUtils.executeRootCommands(commands);

            String url = null;
            for (int i = 0; i < 60; i++) {
                SystemClock.sleep(500);
                RootUtils.CommandResult r = RootUtils.executeRootCommand("cat \"" + logPath + "\" 2>/dev/null");
                if (r.isSuccess() && !r.output.isEmpty()) {
                    for (String line : r.output.split("\n")) {
                        if (line.contains("https://") && line.contains("dash.cloudflare.com")) {
                            url = line.trim();
                            break;
                        }
                    }
                    if (url != null) break;
                }
            }

            if (url == null) {
                RootUtils.CommandResult r = RootUtils.executeRootCommand("cat \"" + logPath + "\" 2>/dev/null");
                new Handler(Looper.getMainLooper()).post(() -> callback.onError("未能获取登录URL\n" + (r.output != null ? r.output : "")));
                return;
            }
            final String finalUrl = url;
            new Handler(Looper.getMainLooper()).post(() -> callback.onUrlReady(finalUrl));

            for (int i = 0; i < 120; i++) {
                SystemClock.sleep(1000);
                if (getCertFile(context).isFile()) {
                    new Handler(Looper.getMainLooper()).post(callback::onSuccess);
                    return;
                }
            }
            new Handler(Looper.getMainLooper()).post(() -> callback.onError("登录超时，请在浏览器完成授权后重试"));
        } finally {
            forceIPv4Root(false);
        }
    }

    public static void createTunnel(Context context, String tunnelName, CreateTunnelCallback callback) {
        final Context appCtx = context.getApplicationContext();
        final File binary = getBinaryFile(appCtx);
        final File homeDir = getCloudflaredHomeDir(appCtx);

        if (!binary.isFile()) {
            callback.onError("二进制文件未找到");
            return;
        }
        if (!MODE_ROOT.equals(getRunMode(appCtx))) {
            callback.onError("本地管理隧道配置需要ROOT模式");
            return;
        }
        if (!isLoggedIn(appCtx)) {
            callback.onError("请先登录 Cloudflare");
            return;
        }

        new Thread(() -> {
            createTunnelRoot(appCtx, binary, homeDir, tunnelName, callback);
        }, "cloudflared-create").start();
    }

    private static void createTunnelRoot(Context context, File binary, File homeDir,
                                          String tunnelName, CreateTunnelCallback callback) {
        forceIPv4Root(true);
        try {
            String cmd = "HOME=\"" + homeDir.getAbsolutePath() + "\" \""
                    + binary.getAbsolutePath() + "\" --no-autoupdate tunnel create \"" + tunnelName + "\" 2>&1";

            RootUtils.CommandResult result = RootUtils.executeRootCommand(cmd);
            String output = result.output != null ? result.output : "";

            String tunnelId = extractTunnelId(output);
            if (tunnelId == null) {
                new Handler(Looper.getMainLooper()).post(() -> callback.onError(output));
                return;
            }

            String credPath = extractCredFilePath(output, tunnelId, homeDir);

            RootUtils.CommandResult catResult = RootUtils.executeRootCommand(
                    "cat \"" + credPath + "\" 2>&1");

            if (!catResult.isSuccess() || catResult.output.trim().isEmpty()
                    || catResult.output.contains("No such file") || catResult.output.contains("Permission denied")) {
                RootUtils.CommandResult findResult = RootUtils.executeRootCommand(
                        "find /data/local/tmp/cloudflared/.cloudflared -name \"" + tunnelId + ".json\" -type f 2>/dev/null; " +
                        "find /root/.cloudflared -name \"" + tunnelId + ".json\" -type f 2>/dev/null; " +
                        "find /data/local/tmp/cloudflared -name \"" + tunnelId + ".json\" -type f 2>/dev/null");
                if (findResult.isSuccess() && !findResult.output.trim().isEmpty()) {
                    credPath = findResult.output.trim().split("\n")[0];
                    catResult = RootUtils.executeRootCommand("cat \"" + credPath + "\" 2>&1");
                }
            }

            if (!catResult.isSuccess() || catResult.output.trim().isEmpty()
                    || catResult.output.contains("No such file") || catResult.output.contains("Permission denied")) {
                RootUtils.CommandResult lsResult = RootUtils.executeRootCommand(
                        "ls -la /data/local/tmp/cloudflared/.cloudflared/ 2>&1; echo '---'; " +
                        "ls -la /root/.cloudflared/ 2>&1");
                String error = "读取凭证文件失败: " + credPath + "\ncat: " + catResult.output;
                if (lsResult.output != null) error += "\n" + lsResult.output;
                final String finalError = error;
                new Handler(Looper.getMainLooper()).post(() -> callback.onError(finalError));
                return;
            }

            RootUtils.executeRootCommand("rm -f \"" + credPath + "\" 2>/dev/null; true");
            final String finalId = tunnelId;
            final String finalJson = catResult.output.trim();
            new Handler(Looper.getMainLooper()).post(() -> callback.onSuccess(finalId, finalJson));
        } finally {
            forceIPv4Root(false);
        }
    }

    private static String extractTunnelId(String output) {
        if (output == null) return null;
        Pattern p = Pattern.compile("with id\\s+([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");
        Matcher m = p.matcher(output);
        if (m.find()) return m.group(1);
        p = Pattern.compile("([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");
        m = p.matcher(output);
        if (m.find()) return m.group(1);
        return null;
    }

    private static String extractCredFilePath(String output, String tunnelId, File homeDir) {
        if (output != null) {
            Pattern p = Pattern.compile("credentials written to\\s+(\\S+)");
            Matcher m = p.matcher(output);
            if (m.find()) return m.group(1);
        }
        return new File(homeDir, ".cloudflared/" + tunnelId + ".json").getAbsolutePath();
    }

    private static String readFile(File file) {
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            fis.close();
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== ROOT Mode ====================

    private static String fixCredentialsPath(String yaml, String newPath) {
        if (yaml == null) return null;
        Pattern p = Pattern.compile("(?m)^(credentials-file:\\s*).+$");
        Matcher m = p.matcher(yaml);
        if (m.find()) {
            return m.replaceAll("$1" + newPath);
        }
        return yaml;
    }

    private static void writeTextFile(File file, String content) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        FileOutputStream fos = new FileOutputStream(file, false);
        fos.write(content.getBytes(StandardCharsets.UTF_8));
        fos.flush();
        fos.getFD().sync();
        fos.close();
    }


    private static boolean startRoot(Context context, String token, String protocol) {
        File nativeBinary = getBinaryFile(context);
        if (!nativeBinary.isFile()) {
            Log.e(TAG, "Binary not found: " + nativeBinary.getAbsolutePath());
            return false;
        }

        List<String> commands = new ArrayList<>();
        commands.add("mkdir -p \"" + ROOT_DIR + "\"");
        commands.add("cp -f \"" + nativeBinary.getAbsolutePath() + "\" \"" + ROOT_BINARY_PATH + "\"");
        commands.add("chmod 777 \"" + ROOT_BINARY_PATH + "\"");
        commands.add("truncate -s 0 \"" + ROOT_LOG_PATH + "\" 2>/dev/null; true");

        StringBuilder cmd = new StringBuilder();
        cmd.append("nohup \"").append(ROOT_BINARY_PATH).append("\" tunnel --no-autoupdate");
        cmd.append(" --grace-period 2s");
        cmd.append(" --loglevel info");
        cmd.append(" --transport-loglevel warn");
        cmd.append(" --metrics 127.0.0.1:0");
        cmd.append(" --edge-ip-version auto");
        if (protocol != null && !protocol.isEmpty() && !"auto".equals(protocol)) {
            cmd.append(" --protocol ").append(protocol);
        }
        if (token != null && !token.trim().isEmpty()) {
            try {
                File tokenDir = new File(context.getNoBackupFilesDir(), "cloudflared_root");
                if (!tokenDir.isDirectory()) tokenDir.mkdirs();
                File tokenFile = new File(tokenDir, "token");
                FileOutputStream fos = new FileOutputStream(tokenFile, false);
                fos.write(token.trim().getBytes(StandardCharsets.UTF_8));
                fos.flush();
                fos.close();
                commands.add("cp -f \"" + tokenFile.getAbsolutePath() + "\" \"" + ROOT_TOKEN_PATH + "\"");
                commands.add("chmod 644 \"" + ROOT_TOKEN_PATH + "\"");
                cmd.append(" run --token-file \"").append(ROOT_TOKEN_PATH).append("\"");
            } catch (Exception e) {
                Log.e(TAG, "Failed to write token file", e);
                cmd.append(" run --token ").append(token.trim());
            }
        } else {
            cmd.append(" run");
        }
        cmd.append(" > \"").append(ROOT_LOG_PATH).append("\" 2>&1 &");

        commands.add(cmd.toString());
        commands.add("sleep 1");

        RootUtils.CommandResult result = RootUtils.executeRootCommands(commands.toArray(new String[0]));
        return result.isSuccess();
    }

    private static boolean startRootLocal(Context context, Config config) {
        File nativeBinary = getBinaryFile(context);
        if (!nativeBinary.isFile()) {
            Log.e(TAG, "Binary not found: " + nativeBinary.getAbsolutePath());
            return false;
        }

        String yaml = config.getConfigYaml();
        String cred = config.getCredentialsJson();
        if (yaml == null || yaml.trim().isEmpty()) {
            Log.e(TAG, "Config YAML is empty");
            return false;
        }

        File credDir = new File(context.getNoBackupFilesDir(), "cloudflared_local_root");
        if (!credDir.isDirectory()) credDir.mkdirs();
        File credFile = new File(credDir, "credentials.json");
        File yamlFile = new File(credDir, "config.yml");

        try {
            writeTextFile(credFile, cred != null ? cred.trim() : "");
            writeTextFile(yamlFile, fixCredentialsPath(yaml, ROOT_CRED_PATH));
        } catch (Exception e) {
            Log.e(TAG, "Failed to write local config files", e);
            return false;
        }

        List<String> commands = new ArrayList<>();
        commands.add("mkdir -p \"" + ROOT_DIR + "\"");
        commands.add("cp -f \"" + nativeBinary.getAbsolutePath() + "\" \"" + ROOT_BINARY_PATH + "\"");
        commands.add("chmod 777 \"" + ROOT_BINARY_PATH + "\"");
        commands.add("truncate -s 0 \"" + ROOT_LOG_PATH + "\" 2>/dev/null; true");
        commands.add("cp -f \"" + credFile.getAbsolutePath() + "\" \"" + ROOT_CRED_PATH + "\"");
        commands.add("chmod 644 \"" + ROOT_CRED_PATH + "\"");
        commands.add("cp -f \"" + yamlFile.getAbsolutePath() + "\" \"" + ROOT_CONFIG_PATH + "\"");
        commands.add("chmod 644 \"" + ROOT_CONFIG_PATH + "\"");

        StringBuilder cmd = new StringBuilder();
        cmd.append("nohup \"").append(ROOT_BINARY_PATH).append("\" tunnel --no-autoupdate");
        cmd.append(" --grace-period 2s");
        cmd.append(" --loglevel info");
        cmd.append(" --transport-loglevel warn");
        cmd.append(" --metrics 127.0.0.1:0");
        cmd.append(" --edge-ip-version auto");
        String protocol = config.getProtocol();
        if (protocol != null && !protocol.isEmpty() && !"auto".equals(protocol)) {
            cmd.append(" --protocol ").append(protocol);
        }
        cmd.append(" --config \"").append(ROOT_CONFIG_PATH).append("\"");
        cmd.append(" run");
        cmd.append(" > \"").append(ROOT_LOG_PATH).append("\" 2>&1 &");

        commands.add(cmd.toString());
        commands.add("sleep 1");

        RootUtils.CommandResult result = RootUtils.executeRootCommands(commands.toArray(new String[0]));
        return result.isSuccess();
    }


    private static String checkRootLogState() {
        RootUtils.CommandResult result = RootUtils.executeRootCommand(
                "tail -30 \"" + ROOT_LOG_PATH + "\" 2>/dev/null");
        if (!result.isSuccess() || result.output.isEmpty()) return "connecting";

        String lower = result.output.toLowerCase(Locale.US);
        if (lower.contains("registered tunnel connection")
                || lower.contains("tunnel connection registered")) {
            return "connected";
        }
        if (lower.contains("invalid tunnel token")
                || lower.contains("failed to parse tunnel token")
                || lower.contains("unauthorized")) {
            return "error";
        }
        return "connecting";
    }

    private static String checkRootLogError() {
        RootUtils.CommandResult result = RootUtils.executeRootCommand(
                "tail -30 \"" + ROOT_LOG_PATH + "\" 2>/dev/null");
        if (!result.isSuccess() || result.output.isEmpty()) return "";

        String lower = result.output.toLowerCase(Locale.US);
        if (lower.contains("invalid tunnel token") || lower.contains("unauthorized")) {
            return "Token被Cloudflare拒绝";
        }
        if (lower.contains("unable to establish connection")) {
            return "正在连接Cloudflare边缘...";
        }
        return "";
    }

    // ==================== Non-ROOT Mode ====================

    private static boolean startNonRoot(Context context, String token, String protocol) {
        synchronized (LOCK) {
            if (nonRootProcess != null) {
                Log.w(TAG, "Process already running");
                return false;
            }
        }

        File binary = getBinaryFile(context);
        if (!binary.isFile()) {
            Log.e(TAG, "Binary not found: " + binary.getAbsolutePath());
            return false;
        }

        File tokenFile = null;
        try {
            if (token != null && !token.trim().isEmpty()) {
                File tokenDir = new File(context.getNoBackupFilesDir(), "cloudflared_token");
                if (!tokenDir.isDirectory()) tokenDir.mkdirs();
                tokenDir.setReadable(true, true);
                tokenDir.setWritable(true, true);
                tokenDir.setExecutable(true, true);
                tokenFile = new File(tokenDir, "token");
                FileOutputStream fos = new FileOutputStream(tokenFile, false);
                fos.write(token.trim().getBytes(StandardCharsets.UTF_8));
                fos.flush();
                fos.getFD().sync();
                fos.close();
                tokenFile.setReadable(true, true);
                tokenFile.setWritable(true, true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to prepare token file", e);
            synchronized (LOCK) {
                nonRootState = "error";
                nonRootError = "Token file error: " + e.getMessage();
            }
            return false;
        }

        try {
            List<String> command = new ArrayList<>();
            command.add(binary.getAbsolutePath());
            command.add("tunnel");
            command.add("--no-autoupdate");
            command.add("--grace-period");
            command.add("2s");
            command.add("--loglevel");
            command.add("info");
            command.add("--transport-loglevel");
            command.add("warn");
            command.add("--metrics");
            command.add("127.0.0.1:0");
            command.add("--edge-ip-version");
            command.add("auto");
            if (protocol != null && !protocol.isEmpty() && !"auto".equals(protocol)) {
                command.add("--protocol");
                command.add(protocol);
            }
            if (tokenFile != null) {
                command.add("run");
                command.add("--token-file");
                command.add(tokenFile.getAbsolutePath());
            } else {
                command.add("run");
            }

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(context.getFilesDir());
            builder.redirectErrorStream(true);
            Process launched = builder.start();

            synchronized (LOCK) {
                nonRootProcess = launched;
                nonRootState = "connecting";
                nonRootError = "";
                connected = false;
            }

            startReaderThread(launched, tokenFile);
            Log.i(TAG, "cloudflared started (non-root)");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to start cloudflared", e);
            synchronized (LOCK) {
                nonRootState = "error";
                nonRootError = e.getMessage() != null ? e.getMessage() : "Unknown error";
            }
            if (tokenFile != null) tokenFile.delete();
            return false;
        }
    }

    private static void startReaderThread(final Process launched, final File tokenFile) {
        Thread reader = new Thread(() -> {
            int exitCode = -1;
            try {
                BufferedReader input = new BufferedReader(
                        new InputStreamReader(launched.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = input.readLine()) != null) {
                    handleProcessLine(launched, line);
                }
                exitCode = launched.waitFor();
            } catch (Exception e) {
                Log.e(TAG, "Reader thread error", e);
            } finally {
                synchronized (LOCK) {
                    if (nonRootProcess == launched) {
                        nonRootProcess = null;
                        connected = false;
                        if (nonRootError.isEmpty()) {
                            nonRootError = "cloudflared exited (code=" + exitCode + ")";
                        }
                        nonRootState = "error";
                    }
                }
                if (tokenFile != null) {
                    try { tokenFile.delete(); } catch (Exception ignored) {}
                }
            }
        }, "cloudflared-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private static void handleProcessLine(Process launched, String raw) {
        if (raw == null || raw.trim().isEmpty()) return;
        String lower = raw.toLowerCase(Locale.US);
        synchronized (LOCK) {
            if (nonRootProcess != launched) return;
            if (lower.contains("registered tunnel connection")
                    || lower.contains("tunnel connection registered")) {
                connected = true;
                nonRootState = "connected";
                nonRootError = "";
            } else if (lower.contains("invalid tunnel token")
                    || lower.contains("failed to parse tunnel token")
                    || lower.contains("unauthorized")) {
                nonRootState = "error";
                nonRootError = "Token rejected by Cloudflare";
            } else if (lower.contains("unable to establish connection")) {
                if (!connected) nonRootState = "connecting";
                nonRootError = "Connecting to Cloudflare edge...";
            }
        }
    }

    private static boolean stopNonRoot() {
        synchronized (LOCK) {
            Process active = nonRootProcess;
            nonRootProcess = null;
            connected = false;
            if (active == null) {
                nonRootState = "stopped";
                nonRootError = "";
                return true;
            }
            try { active.destroy(); } catch (Exception ignored) {}

            final Process finishing = active;
            Thread reaper = new Thread(() -> {
                try {
                    long deadline = SystemClock.elapsedRealtime() + 3000L;
                    while (SystemClock.elapsedRealtime() < deadline) {
                        try {
                            finishing.exitValue();
                            return;
                        } catch (IllegalThreadStateException stillRunning) {
                            SystemClock.sleep(100L);
                        }
                    }
                    try {
                        java.lang.reflect.Method force = Process.class.getMethod("destroyForcibly");
                        force.invoke(finishing);
                    } catch (Exception e) {
                        try { finishing.destroy(); } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
            }, "cloudflared-stop");
            reaper.setDaemon(true);
            reaper.start();

            nonRootState = "stopped";
            nonRootError = "";
            return true;
        }
    }
}
