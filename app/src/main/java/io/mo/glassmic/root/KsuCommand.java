package io.mo.glassmic.root;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** The controller can only invoke the installed module's public control commands. */
public final class KsuCommand {
    private KsuCommand() {}
    public static final String BINARY = "/data/adb/modules/glassmic/bin/glassmicctl";

    public static String quote(String value) {
        if (value.indexOf('\0') >= 0) throw new IllegalArgumentException("NUL in argument");
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    public static String build(String action, List<String> args) {
        switch (action) {
            case "status": case "play": case "pause": case "real": case "silence":
                if (!args.isEmpty()) throw new IllegalArgumentException("Unexpected arguments");
                break;
            case "load":
                if (args.size() != 1 || !mediaName(args.get(0)))
                    throw new IllegalArgumentException("Invalid audio filename");
                break;
            case "import":
                if (args.size() != 1 || !args.get(0).matches("/storage/emulated/[0-9]+/Android/data/io\\.mo\\.glassmic/files/ksu-import/[A-Za-z0-9-]+/[^/]+")
                        || args.get(0).contains("/../"))
                    throw new IllegalArgumentException("Invalid import staging path");
                break;
            case "seek": case "gain": case "speed": case "loop": case "mix": case "mic-level":
                if (args.size() != 1) throw new IllegalArgumentException("One value required");
                double n = Double.parseDouble(args.get(0));
                double min = action.equals("gain") ? -24 : action.equals("speed") ? .5 : 0;
                double max = action.equals("gain") ? 12 : action.equals("speed") ? 2 : (action.equals("loop") || action.equals("mix")) ? 1 : action.equals("mic-level") ? 200 : 86400000;
                if (!Double.isFinite(n) || n < min || n > max || ((action.equals("loop") || action.equals("mix") || action.equals("mic-level")) && n != Math.rint(n)))
                    throw new IllegalArgumentException("Value outside module range");
                break;
            case "targets":
                int bytes = 0;
                for (String pkg : args) {
                    if (pkg.length() > 220 || !pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))
                        throw new IllegalArgumentException("Invalid package name");
                    bytes += pkg.length() + 1;
                }
                if (bytes > 4096) throw new IllegalArgumentException("Too many target packages");
                break;
            default: throw new IllegalArgumentException("Unsupported module command");
        }
        String guard = action.equals("real") ? "" :
                "if [ -e /data/adb/modules/glassmic/disable ] || [ -e /data/adb/modules/glassmic/remove ]; then "
                + "echo 'GlassMic module is disabled or pending removal' >&2; exit 1; fi; ";
        StringBuilder out = new StringBuilder(guard).append("exec ").append(quote(BINARY)).append(' ').append(quote(action));
        for (String arg : args) out.append(' ').append(quote(arg));
        return out.toString();
    }

    public static boolean mediaName(String name) {
        return !name.isEmpty() && name.getBytes(StandardCharsets.UTF_8).length <= 180
                && !name.equals(".") && !name.equals("..") && name.chars().noneMatch(c -> c < 32 || c == 127 || c == '/' || c == '\\');
    }
}
