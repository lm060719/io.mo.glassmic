package io.mo.glassmic.root;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded root subprocess. Killing it also closes the reader on timeout. No shell receives PCM. */
public final class KsuProcess {
    private KsuProcess() {}
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ksu-command-deadline");
        thread.setDaemon(true);
        return thread;
    });

    public static String execute(String command) throws IOException, InterruptedException {
        return read(new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start(), 30000, 2 * 1024 * 1024);
    }

    public static String read(Process process, long timeoutMs, int limit) throws IOException, InterruptedException {
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> timer = TIMER.schedule(() -> {
            timedOut.set(true);
            process.destroyForcibly();
            try { process.getInputStream().close(); } catch (IOException ignored) { }
        }, timeoutMs, TimeUnit.MILLISECONDS);
        try (InputStream input = process.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            process.getOutputStream().close();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > limit) throw new IOException("Module response is too large");
                output.write(buffer, 0, count);
            }
            int exit = process.waitFor();
            if (timedOut.get()) throw new IOException("Root command timed out");
            String text = output.toString(StandardCharsets.UTF_8.name()).trim();
            if (exit != 0) throw new IOException(text.isEmpty() ? "Root command failed (" + exit + ")" : text.substring(0, Math.min(text.length(), 500)));
            return text;
        } finally {
            timer.cancel(false);
            process.destroyForcibly();
        }
    }
}
