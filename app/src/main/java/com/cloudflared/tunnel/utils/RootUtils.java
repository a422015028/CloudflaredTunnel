package com.cloudflared.tunnel.utils;

import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class RootUtils {

    private static final String TAG = "RootUtils";

    public static boolean isRootAvailable() {
        CommandResult result = executeRootCommand("echo root_test");
        return result.isSuccess();
    }

    public static CommandResult executeRootCommand(String command) {
        return executeRootCommands(new String[]{command});
    }

    public static CommandResult executeRootCommands(String[] commands) {
        Process process = null;
        DataOutputStream os = null;
        BufferedReader reader = null;
        StringBuilder output = new StringBuilder();

        try {
            ProcessBuilder pb = new ProcessBuilder("su");
            pb.redirectErrorStream(true);
            process = pb.start();

            os = new DataOutputStream(process.getOutputStream());
            for (String command : commands) {
                if (command == null || command.trim().isEmpty()) continue;
                Log.d(TAG, "Executing: " + command);
                os.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            }
            os.write("exit\n".getBytes(StandardCharsets.UTF_8));
            os.flush();

            reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            int exitCode = process.waitFor();
            String result = output.toString().trim();
            Log.d(TAG, "Exit code: " + exitCode + ", output: " + result);
            return new CommandResult(exitCode, result, "");

        } catch (Exception e) {
            Log.e(TAG, "Execution failed", e);
            return new CommandResult(-1, "", e.getMessage());
        } finally {
            try {
                if (os != null) os.close();
                if (reader != null) reader.close();
            } catch (Exception ignored) {}
            if (process != null) process.destroy();
        }
    }

    public static boolean isProcessRunning(String processName) {
        CommandResult result = executeRootCommand("pgrep -x \"" + processName + "\"");
        if (result.exitCode == 0 && !result.output.isEmpty()) return true;
        result = executeRootCommand("pidof \"" + processName + "\"");
        return result.exitCode == 0 && !result.output.isEmpty();
    }

    public static CommandResult stopProcess(String processName) {
        return executeRootCommand(
            "pids=$(pgrep -x \"" + processName + "\" 2>/dev/null); " +
            "if [ -n \"$pids\" ]; then " +
            "for pid in $pids; do kill -9 $pid 2>/dev/null; done; " +
            "echo \"Killed\"; else echo 'No process found'; fi"
        );
    }

    public static class CommandResult {
        public final int exitCode;
        public final String output;
        public final String error;

        public CommandResult(int exitCode, String output, String error) {
            this.exitCode = exitCode;
            this.output = output;
            this.error = error;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }
}
