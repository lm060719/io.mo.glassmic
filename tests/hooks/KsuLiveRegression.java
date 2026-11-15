import io.mo.glassmic.root.KsuLiveProtocol;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class KsuLiveRegression {
    public static void main(String[] args) {
        byte[] wire = KsuLiveProtocol.request("com.example.music", 0x123456789abcdefL);
        ByteBuffer b = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN);
        check(wire.length == 4120 && b.getInt() == 0x474D3031 && b.getInt() == 16, "native request ABI");
        check(b.getLong() == 0x123456789abcdefL && b.getInt() == 0 && b.getInt() == 0, "session token and reserved fields");
        check(new String(wire, 24, 17, StandardCharsets.UTF_8).equals("com.example.music"), "source package offset");
        for (int i = 41; i < wire.length; i++) check(wire[i] == 0, "request must be NUL terminated and padded");
        for (String name : new String[]{"", "../secret", "pkg\ncmd", "pkg;id", "single", "x.".repeat(111)}) {
            try { KsuLiveProtocol.request(name, 1); throw new AssertionError("invalid package accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        try { KsuLiveProtocol.request("com.example.music", 0); throw new AssertionError("invalid lease accepted"); }
        catch (IllegalArgumentException expected) { }
        try {
            String reply = "{\"ok\":true,\"liveSession\":\"1\"}";
            ByteArrayInputStream input = new ByteArrayInputStream((reply + "\nNEXT").getBytes(StandardCharsets.UTF_8));
            check(KsuLiveProtocol.reply(input).equals(reply) && input.read() == 'N', "handshake consumes exactly one line");
            rejects(new byte[0]);
            byte[] tooLong = new byte[1025];Arrays.fill(tooLong, (byte)'x');rejects(tooLong);
        } catch (IOException error) { throw new AssertionError(error); }
        System.out.println("KsuLiveRegression passed");
    }
    static void rejects(byte[] data) {
        try { KsuLiveProtocol.reply(new ByteArrayInputStream(data));throw new AssertionError("unbounded/truncated reply accepted"); }
        catch (IOException expected) { }
    }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
