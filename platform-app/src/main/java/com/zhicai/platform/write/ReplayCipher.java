package com.zhicai.platform.write;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-256-GCM replay envelope. The caller binds the record identity as AAD. */
public final class ReplayCipher {
    private static final byte FORMAT = 1;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public ReplayCipher(String base64Key) {
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(base64Key); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("Replay key must be base64-encoded 32 bytes"); }
        if (bytes.length != 32) throw new IllegalArgumentException("Replay key must contain 32 bytes");
        this.key = new SecretKeySpec(bytes, "AES");
        java.util.Arrays.fill(bytes, (byte) 0);
    }

    public byte[] encrypt(String response, String recordIdentity) {
        byte[] nonce = new byte[12]; random.nextBytes(nonce);
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, response.getBytes(StandardCharsets.UTF_8), nonce, recordIdentity);
        return ByteBuffer.allocate(1 + nonce.length + encrypted.length).put(FORMAT).put(nonce).put(encrypted).array();
    }

    public String decrypt(byte[] envelope, String recordIdentity) {
        if (envelope == null || envelope.length < 29 || envelope[0] != FORMAT)
            throw new IllegalStateException("Invalid replay envelope");
        ByteBuffer buffer = ByteBuffer.wrap(envelope); buffer.get();
        byte[] nonce = new byte[12]; buffer.get(nonce);
        byte[] encrypted = new byte[buffer.remaining()]; buffer.get(encrypted);
        return new String(crypt(Cipher.DECRYPT_MODE, encrypted, nonce, recordIdentity), StandardCharsets.UTF_8);
    }

    private byte[] crypt(int mode, byte[] input, byte[] nonce, String recordIdentity) {
        if (recordIdentity == null || recordIdentity.isBlank()) throw new IllegalArgumentException("Replay record identity is required");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(recordIdentity.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException error) { throw new IllegalStateException("Replay encryption or authentication failed", error); }
    }
}
