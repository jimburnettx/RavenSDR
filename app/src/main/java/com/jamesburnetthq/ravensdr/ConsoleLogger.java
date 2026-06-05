package com.jamesburnetthq.ravensdr;

import java.util.ArrayDeque;

public final class ConsoleLogger {

    private static final int MAX_LINES = 200;
    private static final ConsoleLogger INSTANCE = new ConsoleLogger();

    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private volatile Listener listener;

    public interface Listener {
        void onNewLine(String line);
    }

    private ConsoleLogger() {}

    public static ConsoleLogger get() { return INSTANCE; }

    public void setListener(Listener l) { listener = l; }

    public void log(String msg) {
        long ms = android.os.SystemClock.uptimeMillis();
        String line = String.format("[%6d] %s", ms % 1000000, msg);
        synchronized (lines) {
            lines.addLast(line);
            while (lines.size() > MAX_LINES) lines.pollFirst();
        }
        Listener l = listener;
        if (l != null) l.onNewLine(line);
    }

    public String getFullLog() {
        synchronized (lines) {
            StringBuilder sb = new StringBuilder();
            for (String s : lines) { sb.append(s); sb.append('\n'); }
            return sb.toString();
        }
    }
}
