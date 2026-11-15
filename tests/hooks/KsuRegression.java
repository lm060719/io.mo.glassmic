import io.mo.glassmic.core.model.ConfigSnapshot;
import io.mo.glassmic.core.model.ScopeMode;
import io.mo.glassmic.root.KsuCommand;
import io.mo.glassmic.root.KsuProcess;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/** Control-plane regressions: backend exclusion, shell arguments and bounded subprocess cleanup. */
public final class KsuRegression {
    public static void main(String[] args) {
        for (boolean policy : new boolean[]{false, true}) {
            ConfigSnapshot ksu = snapshot(policy, true);
            check(!ksu.handlesInAppBackend(false) && !ksu.handlesInAppBackend(true), "KSU must exclude BOTH APK injection paths");
            ConfigSnapshot old = snapshot(policy, false);
            check(old.handlesInAppBackend(policy) && !old.handlesInAppBackend(!policy), "old backend selection changed");
        }
        check(KsuCommand.build("targets", Collections.emptyList()).endsWith("'targets'"), "empty scope must clear all targets");
        rejects(() -> KsuCommand.build("stop", List.of()));
        rejects(() -> KsuCommand.build("play", List.of("extra")));
        rejects(() -> KsuCommand.build("targets", List.of("pkg;reboot")));
        rejects(() -> KsuCommand.build("load", List.of("../outside.wav")));
        rejects(() -> KsuCommand.build("load", List.of("bad\nname.wav")));
        rejects(() -> KsuCommand.build("load", List.of("音".repeat(61))));
        rejects(() -> KsuCommand.build("gain", List.of("NaN")));
        rejects(() -> KsuCommand.build("gain", List.of("13")));
        rejects(() -> KsuCommand.build("speed", List.of("0")));
        rejects(() -> KsuCommand.build("loop", List.of("0.5")));
        rejects(() -> KsuCommand.build("seek", List.of("-1")));
        rejects(() -> KsuCommand.build("import", List.of("/data/adb/private.wav")));
        for (String value : List.of("-1", "201", "0.5", "NaN", "Infinity", "100;id"))
            rejects(() -> KsuCommand.build("mic-level", List.of(value)));
        for (String value : List.of("-1", "2", "0.5", "NaN"))
            rejects(() -> KsuCommand.build("mix", List.of(value)));
        check(KsuCommand.build("mix", List.of("1")).endsWith("'mix' '1'"), "mix command");
        check(KsuCommand.build("mic-level", List.of("0")).endsWith("'mic-level' '0'"), "microphone mute");
        check(KsuCommand.build("mic-level", List.of("200")).endsWith("'mic-level' '200'"), "microphone boost");
        String filename = "voice ' $(reboot) `id` & 曲.wav";
        String quoted = KsuCommand.quote(filename);
        check(quoted.equals("'voice '\"'\"' $(reboot) `id` & 曲.wav'"), "metacharacters must remain inside POSIX quotes");
        check(KsuCommand.build("load", List.of(filename)).endsWith(quoted), "filename lost its quoting");
        FakeProcess ok = new FakeProcess("{\"ok\":true}", 0);
        try { check(KsuProcess.read(ok, 1000, 1024).equals("{\"ok\":true}"), "response changed"); }
        catch (Exception e) { throw new AssertionError(e); }
        check(ok.destroyed, "successful process not cleaned up");
        processFails(new FakeProcess("permission denied", 1), 1000, 1024);
        processFails(new FakeProcess("x".repeat(128), 0), 1000, 32);
        FakeProcess hung = new FakeProcess("", 0) {
            final CountDownLatch done = new CountDownLatch(1);
            final InputStream blocking = new InputStream() {
                public int read() throws IOException {
                    try { done.await(); return -1; } catch (InterruptedException e) { throw new IOException(e); }
                }
                public void close() { done.countDown(); }
            };
            public InputStream getInputStream() { return blocking; }
            public Process destroyForcibly() { done.countDown(); return super.destroyForcibly(); }
        };
        processFails(hung, 40, 1024);
        System.out.println("KsuRegression passed");
    }
    static ConfigSnapshot snapshot(boolean policy, boolean ksu) {
        return new ConfigSnapshot(true, ScopeMode.WHITELIST, Set.of("test.app"), Set.of(), true, policy, Set.of("test.app"), false, ksu);
    }
    static void rejects(Runnable task) {
        try { task.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("unsafe command accepted");
    }
    static void processFails(FakeProcess p, int timeout, int limit) {
        try { KsuProcess.read(p, timeout, limit); throw new AssertionError("process failure ignored"); }
        catch (IOException expected) { check(p.destroyed, "failed process not cleaned up"); }
        catch (InterruptedException e) { throw new AssertionError(e); }
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static class FakeProcess extends Process {
        final InputStream input;
        final int code;
        boolean destroyed;
        FakeProcess(String text, int code) { input = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); this.code = code; }
        public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        public InputStream getInputStream() { return input; }
        public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        public int waitFor() { return code; }
        public int exitValue() { return code; }
        public void destroy() { destroyed = true; }
        public Process destroyForcibly() { destroyed = true; return this; }
    }
}
