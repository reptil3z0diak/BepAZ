package com.reverseaz.auth;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;

/**
 * Gère l'encryption Minecraft (RSA + AES/CFB8)
 */
public class EncryptionUtil {

    /**
     * Génère un shared secret aléatoire de 16 bytes pour AES
     */
    public static byte[] generateSharedSecret() {
        byte[] secret = new byte[16];
        new SecureRandom().nextBytes(secret);
        return secret;
    }

    /**
     * Chiffre le shared secret avec la clé publique RSA du serveur
     * 
     * @param publicKeyBytes clé publique en format X.509/DER
     * @param data           données à chiffrer (shared secret ou verify token)
     */
    public static byte[] encryptRSA(byte[] publicKeyBytes, byte[] data) throws Exception {
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicKeyBytes);
        PublicKey publicKey = keyFactory.generatePublic(keySpec);

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        return cipher.doFinal(data);
    }

    /**
     * Calcule le hash du serveur pour l'authentification Yggdrasil
     * Format: SHA1(serverIdString + sharedSecret + publicKey)
     * Retourne le hash au format "Java BigInteger" (signé, hexadécimal)
     */
    public static String computeServerHash(String serverId, byte[] sharedSecret, byte[] publicKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(serverId.getBytes("ISO-8859-1"));
            sha1.update(sharedSecret);
            sha1.update(publicKey);
            byte[] digest = sha1.digest();

            // Convertir en format Java BigInteger (signé)
            return new BigInteger(digest).toString(16);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute server hash", e);
        }
    }

    /**
     * Crée un cipher AES/CFB8 pour encryption/decryption
     * Minecraft utilise le shared secret comme clé ET comme IV
     */
    public static Cipher createAESCipher(int mode, byte[] sharedSecret) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(sharedSecret, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(sharedSecret);

        Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
        cipher.init(mode, keySpec, ivSpec);
        return cipher;
    }
}
