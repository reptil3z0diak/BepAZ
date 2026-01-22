package com.reverseaz.auth;

import javax.crypto.Cipher;
import java.io.*;

/**
 * Wrapper pour les flux chiffrés AES/CFB8
 * Minecraft chiffre le flux complet après l'Encryption Response
 */
public class CipherStreams {

    /**
     * InputStream qui déchiffre les données reçues
     */
    public static class DecryptingInputStream extends FilterInputStream {
        private final Cipher cipher;

        public DecryptingInputStream(InputStream in, byte[] sharedSecret) throws Exception {
            super(in);
            this.cipher = EncryptionUtil.createAESCipher(Cipher.DECRYPT_MODE, sharedSecret);
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b == -1)
                return -1;

            byte[] single = new byte[] { (byte) b };
            byte[] decrypted = cipher.update(single);
            return decrypted != null && decrypted.length > 0 ? (decrypted[0] & 0xFF) : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            byte[] encrypted = new byte[len];
            int bytesRead = in.read(encrypted, 0, len);
            if (bytesRead == -1)
                return -1;

            byte[] decrypted = cipher.update(encrypted, 0, bytesRead);
            if (decrypted != null) {
                System.arraycopy(decrypted, 0, b, off, decrypted.length);
                return decrypted.length;
            }
            return 0;
        }
    }

    /**
     * OutputStream qui chiffre les données envoyées
     */
    public static class EncryptingOutputStream extends FilterOutputStream {
        private final Cipher cipher;

        public EncryptingOutputStream(OutputStream out, byte[] sharedSecret) throws Exception {
            super(out);
            this.cipher = EncryptionUtil.createAESCipher(Cipher.ENCRYPT_MODE, sharedSecret);
        }

        @Override
        public void write(int b) throws IOException {
            byte[] single = new byte[] { (byte) b };
            byte[] encrypted = cipher.update(single);
            if (encrypted != null) {
                out.write(encrypted);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            byte[] encrypted = cipher.update(b, off, len);
            if (encrypted != null) {
                out.write(encrypted);
            }
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }
    }
}
