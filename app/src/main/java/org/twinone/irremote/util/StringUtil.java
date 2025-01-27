package org.twinone.irremote.util;

public class StringUtil {
    public static String toHex(byte[] bytes) {
        return toHex(bytes, bytes.length);
    }

    public static String toHex(byte[] bytes, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            byte b = bytes[i];
            sb.append(String.format("%02X ", b));
        }
        return sb.toString();
    }
}
