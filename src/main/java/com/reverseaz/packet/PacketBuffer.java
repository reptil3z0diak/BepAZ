package com.reverseaz.packet;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Buffer optimisé pour parser les paquets Minecraft
 * Supporte VarInt, Short, et autres types MC
 */
public class PacketBuffer {

    private byte[] data;
    private int readerIndex;
    private int writerIndex;
    private int capacity;

    public PacketBuffer(int initialCapacity) {
        this.capacity = initialCapacity;
        this.data = new byte[initialCapacity];
        this.readerIndex = 0;
        this.writerIndex = 0;
    }

    /**
     * Reset le buffer pour réutilisation (évite allocations)
     */
    public void clear() {
        readerIndex = 0;
        writerIndex = 0;
    }

    /**
     * Écrit des bytes dans le buffer
     */
    public void writeBytes(byte[] src, int offset, int length) {
        ensureCapacity(writerIndex + length);
        System.arraycopy(src, offset, data, writerIndex, length);
        writerIndex += length;
    }

    /**
     * Écrit un seul byte
     */
    public void writeByte(int value) {
        ensureCapacity(writerIndex + 1);
        data[writerIndex++] = (byte) value;
    }

    /**
     * Écrit un short (Big Endian comme MC)
     */
    public void writeShort(short value) {
        ensureCapacity(writerIndex + 2);
        data[writerIndex++] = (byte) ((value >> 8) & 0xFF);
        data[writerIndex++] = (byte) (value & 0xFF);
    }

    /**
     * Lit un VarInt (format Minecraft)
     */
    public int readVarInt() {
        int value = 0;
        int position = 0;
        byte currentByte;

        do {
            if (readerIndex >= writerIndex) {
                throw new RuntimeException("VarInt too long or incomplete");
            }
            currentByte = data[readerIndex++];
            value |= (currentByte & 0x7F) << position;
            position += 7;

            if (position > 35) {
                throw new RuntimeException("VarInt too big");
            }
        } while ((currentByte & 0x80) != 0);

        return value;
    }

    /**
     * Lit un VarInt sans avancer le curseur
     */
    public int peekVarInt() {
        int savedIndex = readerIndex;
        int value = readVarInt();
        readerIndex = savedIndex;
        return value;
    }

    /**
     * Écrit un VarInt
     */
    public void writeVarInt(int value) {
        while ((value & ~0x7F) != 0) {
            writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        writeByte(value);
    }

    /**
     * Retourne la taille en bytes d'un VarInt
     */
    public static int getVarIntSize(int value) {
        if ((value & 0xFFFFFF80) == 0)
            return 1;
        if ((value & 0xFFFFC000) == 0)
            return 2;
        if ((value & 0xFFE00000) == 0)
            return 3;
        if ((value & 0xF0000000) == 0)
            return 4;
        return 5;
    }

    /**
     * Lit un short (Big Endian)
     */
    public short readShort() {
        if (readerIndex + 2 > writerIndex) {
            throw new RuntimeException("Not enough data for short");
        }
        int high = (data[readerIndex++] & 0xFF) << 8;
        int low = data[readerIndex++] & 0xFF;
        return (short) (high | low);
    }

    /**
     * Lit N bytes
     */
    public byte[] readBytes(int length) {
        if (readerIndex + length > writerIndex) {
            throw new RuntimeException("Not enough data");
        }
        byte[] result = new byte[length];
        System.arraycopy(data, readerIndex, result, 0, length);
        readerIndex += length;
        return result;
    }

    /**
     * Skip N bytes
     */
    public void skip(int count) {
        readerIndex += count;
    }

    /**
     * Bytes disponibles à lire
     */
    public int readableBytes() {
        return writerIndex - readerIndex;
    }

    /**
     * Position de lecture actuelle
     */
    public int getReaderIndex() {
        return readerIndex;
    }

    /**
     * Définit la position de lecture
     */
    public void setReaderIndex(int index) {
        this.readerIndex = index;
    }

    /**
     * Position d'écriture actuelle
     */
    public int getWriterIndex() {
        return writerIndex;
    }

    /**
     * Accès direct aux données
     */
    public byte[] getData() {
        return data;
    }

    /**
     * Retourne les données écrites sous forme de tableau
     */
    public byte[] toArray() {
        byte[] result = new byte[writerIndex];
        System.arraycopy(data, 0, result, 0, writerIndex);
        return result;
    }

    /**
     * Assure la capacité du buffer
     */
    private void ensureCapacity(int minCapacity) {
        if (minCapacity > capacity) {
            int newCapacity = Math.max(minCapacity, capacity * 2);
            byte[] newData = new byte[newCapacity];
            System.arraycopy(data, 0, newData, 0, writerIndex);
            data = newData;
            capacity = newCapacity;
        }
    }

    /**
     * Crée un nouveau buffer à partir d'un tableau de bytes
     */
    public static PacketBuffer wrap(byte[] data, int length) {
        PacketBuffer buffer = new PacketBuffer(length);
        buffer.writeBytes(data, 0, length);
        return buffer;
    }
}
