package org.teavm.classlib.java.net;

import java.net.UnknownHostException;

/** Non-networking TeaVM mapping needed only for Gson's optional InetAddress adapter. */
public final class TInetAddress {
    private final String address;

    private TInetAddress(String address) {
        this.address = address;
    }

    public static TInetAddress getByName(String host) throws UnknownHostException {
        if (host == null || host.isBlank()) {
            throw new UnknownHostException(String.valueOf(host));
        }
        return new TInetAddress(host);
    }

    public String getHostAddress() {
        return address;
    }
}
