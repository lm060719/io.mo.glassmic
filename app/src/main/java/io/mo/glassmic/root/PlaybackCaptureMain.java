package io.mo.glassmic.root;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Standalone app_process entry point that captures another app's playback (issue #42).
 * Uses only framework/Java classes, never the Application/Hilt.
 *
 * Registers a dynamic AudioPolicy whose loopback mix matches the source app's players by UID,
 * reads it through {@code createAudioRecordSink} and streams raw PCM16 LE mono 48 kHz into a FIFO.
 * A root-registered policy is not subject to the player's allowed-capture policy, and must NOT set
 * {@code allowPrivilegedPlaybackCapture}: that path is capped at 16 kHz.
 *
 * Arguments: source app UID, render flag (1 = keep playing on the device, 0 = divert), FIFO path.
 * Ownership mirrors {@link AudioPolicyMain}: stdin EOF / STOP or a missing PING ends the process.
 */
public final class PlaybackCaptureMain {
    private static final int SAMPLE_RATE = 48000;
    private static final int CHUNK_BYTES = 1920; // 20 ms mono PCM16

    private static volatile long lastHeartbeat = SystemClock.elapsedRealtime();
    private static volatile Context context;
    private static volatile Class<?> policyClass;
    private static volatile Object policy;
    private static volatile AudioRecord record;
    private static final AtomicBoolean exiting = new AtomicBoolean();

    public static void main(String[] args) {
        if (Process.myUid() != 0 || args.length != 3) {
            System.out.println("ERROR Root access, source UID, render flag and FIFO are required");
            System.exit(1);
        }
        new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = input.readLine()) != null) {
                    if ("STOP".equals(line)) break;
                    if ("PING".equals(line)) lastHeartbeat = SystemClock.elapsedRealtime();
                }
            } catch (Exception ignored) { }
            exit(0);
        }, "glassmic-owner").start();
        new Thread(() -> {
            while (true) {
                SystemClock.sleep(1000);
                if (SystemClock.elapsedRealtime() - lastHeartbeat > 6000) exit(2);
            }
        }, "glassmic-lease").start();
        Runtime.getRuntime().addShutdownHook(new Thread(PlaybackCaptureMain::cleanup));
        try {
            int uid = Integer.parseInt(args[0].trim());
            if (uid % 100000 < 10000) throw new IllegalArgumentException("System UIDs are not supported");
            boolean render = "1".equals(args[1]);
            Looper.prepareMainLooper();
            Class<?> vm = Class.forName("dalvik.system.VMRuntime");
            Object runtime = vm.getDeclaredMethod("getRuntime").invoke(null);
            vm.getDeclaredMethod("setHiddenApiExemptions", String[].class)
                    .invoke(runtime, (Object) new String[]{"L"});
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            register(uid, render);
            new Thread(() -> {
                try {
                    pump(args[2]);
                    exit(0);
                } catch (Throwable error) {
                    fail(error);
                }
            }, "glassmic-capture-pcm").start();
            System.out.println("READY");
            System.out.flush();
            Looper.loop();
        } catch (Throwable error) {
            fail(error);
        }
    }

    private static void register(int uid, boolean render) throws Exception {
        Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
        Class<?> builderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");

        Object ruleBuilder = ruleBuilderClass.getConstructor().newInstance();
        try {
            ruleBuilderClass.getMethod("setTargetMixRole", int.class).invoke(ruleBuilder,
                    constant(ruleClass, "MIX_ROLE_PLAYERS"));
        } catch (NoSuchMethodException | NoSuchFieldException olderAndroid) {
            ruleBuilderClass.getMethod("setTargetMixType", int.class).invoke(ruleBuilder,
                    constant(mixClass, "MIX_TYPE_PLAYERS"));
        }
        ruleBuilderClass.getMethod("addMixRule", int.class, Object.class).invoke(ruleBuilder,
                constant(ruleClass, "RULE_MATCH_UID"), uid);
        Object rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder);

        // The mix format uses an OUTPUT channel mask; the record sink converts it to the input mask.
        AudioFormat format = new AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
        Object mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule);
        mixBuilderClass.getMethod("setFormat", AudioFormat.class).invoke(mixBuilder, format);
        int flags = constant(mixClass, "ROUTE_FLAG_LOOP_BACK");
        if (render) flags |= constant(mixClass, "ROUTE_FLAG_RENDER");
        mixBuilderClass.getMethod("setRouteFlags", int.class).invoke(mixBuilder, flags);
        Object mix = mixBuilderClass.getMethod("build").invoke(mixBuilder);

        Object builder = builderClass.getConstructor(Context.class).newInstance(context);
        builderClass.getMethod("addMix", mixClass).invoke(builder, mix);
        Object candidate = builderClass.getMethod("build").invoke(builder);
        int result;
        try {
            Method method = AudioManager.class.getDeclaredMethod("registerAudioPolicyStatic", policyClass);
            method.setAccessible(true);
            result = (Integer) method.invoke(null, candidate);
        } catch (NoSuchMethodException olderAndroid) {
            result = (Integer) AudioManager.class.getMethod("registerAudioPolicy", policyClass)
                    .invoke(context.getSystemService(Context.AUDIO_SERVICE), candidate);
        }
        if (result != 0) throw new IllegalStateException("AudioPolicy registration failed: " + result);
        policy = candidate;
        record = (AudioRecord) policyClass.getMethod("createAudioRecordSink", mixClass).invoke(policy, mix);
        if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IllegalStateException("Capture AudioRecord is unavailable");
        }
        if (record.getSampleRate() != SAMPLE_RATE || record.getChannelCount() != 1) {
            throw new IllegalStateException("Unexpected capture format " + record.getSampleRate()
                    + "Hz x" + record.getChannelCount());
        }
        record.startRecording();
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            throw new IllegalStateException("Capture did not start");
        }
    }

    private static void pump(String fifo) throws Exception {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        byte[] pcm = new byte[CHUNK_BYTES];
        // The parent opened the read end before launching us, so this open does not block.
        try (FileOutputStream out = new FileOutputStream(fifo)) {
            while (true) {
                int n = record.read(pcm, 0, pcm.length);
                if (n < 0) throw new IllegalStateException("AudioRecord.read: " + n);
                if (n > 0) out.write(pcm, 0, n); // EPIPE once the parent closes its end.
            }
        }
    }

    private static int constant(Class<?> type, String name) throws Exception {
        java.lang.reflect.Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static void cleanup() {
        if (record != null) {
            try { record.stop(); } catch (Throwable ignored) { }
            try { record.release(); } catch (Throwable ignored) { }
        }
        if (policy != null) {
            try {
                AudioManager.class.getMethod("unregisterAudioPolicy", policyClass)
                        .invoke(context.getSystemService(Context.AUDIO_SERVICE), policy);
            } catch (Throwable error) {
                try {
                    Method method = AudioManager.class.getDeclaredMethod("unregisterAudioPolicyAsyncStatic", policyClass);
                    method.setAccessible(true);
                    method.invoke(null, policy);
                } catch (Throwable ignored) { /* Binder death also removes the registered policy. */ }
            }
        }
    }

    private static void fail(Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
        System.out.println("ERROR " + error);
        System.out.flush();
        exit(1);
    }

    private static void exit(int code) {
        if (!exiting.compareAndSet(false, true)) return;
        new Thread(() -> {
            SystemClock.sleep(1500);
            Process.killProcess(Process.myPid());
        }, "glassmic-exit-deadline").start();
        System.exit(code);
    }
}
