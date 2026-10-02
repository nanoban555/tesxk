package miku.moe.app;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Decoder for Anime X Nonton media URLs returned by get_post_description.
 *
 * HAR evidence shows the response fields are Base64 encoded using this layout:
 *   16-byte GCM authentication tag + 12-byte nonce + ciphertext
 * with the response's 64-hex-character secretKey as the AES-256 key.
 */
public final class AnimeXnontonUrlDecoder {
    private static final int TAG_LENGTH_BYTES = 16;
    private static final int NONCE_LENGTH_BYTES = 12;
    private static final int MIN_ENCODED_PAYLOAD_BYTES = TAG_LENGTH_BYTES + NONCE_LENGTH_BYTES + 1;

    private AnimeXnontonUrlDecoder() {}

    public static String decryptUrl(String value, String secretKey) {
        String input = value == null ? "" : value.trim();
        if (isHttpUrl(input)) return input;
        if (input.isEmpty() || secretKey == null || secretKey.trim().isEmpty()) return "";

        try {
            byte[] key = hexToBytes(secretKey.trim());
            if (key.length != 16 && key.length != 24 && key.length != 32) return "";

            byte[] payload = Base64.decode(input, Base64.DEFAULT);
            if (payload.length < MIN_ENCODED_PAYLOAD_BYTES) return "";

            byte[] tag = Arrays.copyOfRange(payload, 0, TAG_LENGTH_BYTES);
            byte[] nonce = Arrays.copyOfRange(payload, TAG_LENGTH_BYTES, TAG_LENGTH_BYTES + NONCE_LENGTH_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(payload, TAG_LENGTH_BYTES + NONCE_LENGTH_BYTES, payload.length);

            byte[] ciphertextWithTag = new byte[ciphertext.length + tag.length];
            System.arraycopy(ciphertext, 0, ciphertextWithTag, 0, ciphertext.length);
            System.arraycopy(tag, 0, ciphertextWithTag, ciphertext.length, tag.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BYTES * 8, nonce)
            );

            String decoded = new String(cipher.doFinal(ciphertextWithTag), StandardCharsets.UTF_8).trim();
            return isHttpUrl(decoded) ? decoded : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static byte[] hexToBytes(String value) {
        if ((value.length() & 1) != 0 || !value.matches("(?i)[0-9a-f]+")) return new byte[0];
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) return new byte[0];
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static boolean isHttpUrl(String value) {
        return value != null
                && !value.trim().isEmpty()
                && value.trim().toLowerCase(java.util.Locale.ROOT).matches("^https?://.+");
    }
}
