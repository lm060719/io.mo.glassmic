package io.mo.glassmic.root;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Root-only daemon Request v1, followed by PCM16 LE / 48 kHz / mono. */
public final class KsuLiveProtocol {
    private KsuLiveProtocol() {}
    public static byte[] request(String sourcePackage, long session) {
        if (session <= 0 || sourcePackage.length() > 220 ||
                !sourcePackage.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))
            throw new IllegalArgumentException("Invalid live capture source/session");
        ByteBuffer out = ByteBuffer.allocate(4120).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x474D3031).putInt(16).putLong(session).putInt(0).putInt(0);
        out.put(sourcePackage.getBytes(StandardCharsets.UTF_8));
        return out.array();
    }
    public static String reply(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (line.size() < 1024) {
            int b = input.read();
            if (b < 0) throw new IOException("Module closed the live handshake");
            if (b == '\n') return line.toString(StandardCharsets.UTF_8.name());
            line.write(b);
        }
        throw new IOException("Module live reply exceeds limit");
    }
}
