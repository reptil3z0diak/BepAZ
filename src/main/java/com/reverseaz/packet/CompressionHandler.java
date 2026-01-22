package com.reverseaz.packet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.DataFormatException;

/**
 * Gère la compression/décompression zlib pour les paquets Minecraft
 */
public class CompressionHandler {

    private final Inflater inflater;
    private final Deflater deflater;
    private final byte[] inflateBuffer;
    private final byte[] deflateBuffer;

    private int compressionThreshold = -1; // -1 = compression désactivée

    public CompressionHandler() {
        this.inflater = new Inflater();
        this.deflater = new Deflater();
        this.inflateBuffer = new byte[65536];
        this.deflateBuffer = new byte[65536];
    }

    /**
     * Active la compression avec le seuil donné
     */
    public void enableCompression(int threshold) {
        this.compressionThreshold = threshold;
    }

    /**
     * Vérifie si la compression est active
     */
    public boolean isCompressionEnabled() {
        return compressionThreshold >= 0;
    }

    public int getCompressionThreshold() {
        return compressionThreshold;
    }

    /**
     * Décompresse les données si nécessaire
     * 
     * @param compressedData données potentiellement compressées
     * @param dataLength     taille indiquée dans le paquet
     * @return données décompressées
     */
    public byte[] decompress(byte[] compressedData, int uncompressedLength) throws DataFormatException {
        if (uncompressedLength == 0) {
            // Pas de compression, retourner tel quel
            return compressedData;
        }

        inflater.reset();
        inflater.setInput(compressedData);

        ByteArrayOutputStream baos = new ByteArrayOutputStream(uncompressedLength);

        while (!inflater.finished()) {
            int count = inflater.inflate(inflateBuffer);
            if (count == 0 && inflater.needsInput()) {
                break;
            }
            baos.write(inflateBuffer, 0, count);
        }

        return baos.toByteArray();
    }

    /**
     * Compresse les données selon le seuil
     * 
     * @param data données à compresser
     * @return données compressées ou originales si sous le seuil
     */
    public CompressionResult compress(byte[] data) {
        if (!isCompressionEnabled() || data.length < compressionThreshold) {
            // Pas de compression
            return new CompressionResult(data, 0);
        }

        deflater.reset();
        deflater.setInput(data);
        deflater.finish();

        ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length);

        while (!deflater.finished()) {
            int count = deflater.deflate(deflateBuffer);
            baos.write(deflateBuffer, 0, count);
        }

        return new CompressionResult(baos.toByteArray(), data.length);
    }

    /**
     * Résultat de compression
     */
    public static class CompressionResult {
        public final byte[] data;
        public final int uncompressedLength; // 0 si non compressé

        public CompressionResult(byte[] data, int uncompressedLength) {
            this.data = data;
            this.uncompressedLength = uncompressedLength;
        }
    }
}
