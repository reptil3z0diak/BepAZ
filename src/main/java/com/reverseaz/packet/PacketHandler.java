package com.reverseaz.packet;

import com.reverseaz.VelocityModifier;
import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;

/**
 * Intercepte et modifie les paquets Minecraft serveur→client
 * Cible principale: Entity Velocity (0x3E en 1.9.4)
 */
public class PacketHandler {

    // ID des paquets en 1.9.4 (protocol 110)
    private static final int PACKET_SET_COMPRESSION = 0x03; // Login state
    private static final int PACKET_ENTITY_VELOCITY = 0x3E; // Play state
    private static final int PACKET_LOGIN_SUCCESS = 0x02; // Login state

    private final VelocityModifier velocityModifier;
    private final CompressionHandler compressionHandler;

    private boolean inPlayState = false;
    private int packetsProcessed = 0;

    public PacketHandler(VelocityModifier velocityModifier) {
        this.velocityModifier = velocityModifier;
        this.compressionHandler = new CompressionHandler();
    }

    /**
     * Traite un flux de bytes du serveur et modifie les paquets de vélocité
     * 
     * @param rawData    données brutes reçues
     * @param length     longueur des données
     * @param workBuffer buffer de travail réutilisable
     * @return données modifiées à envoyer au client
     */
    public byte[] processServerPackets(byte[] rawData, int length, PacketBuffer workBuffer) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(length + 64);
        PacketBuffer input = PacketBuffer.wrap(rawData, length);

        try {
            while (input.readableBytes() > 0) {
                int packetStart = input.getReaderIndex();

                // Lire la taille du paquet
                if (input.readableBytes() < 1)
                    break;

                int packetLength;
                try {
                    packetLength = input.readVarInt();
                } catch (Exception e) {
                    // Paquet incomplet, renvoyer le reste
                    byte[] remaining = input.readBytes(input.readableBytes());
                    output.write(rawData, packetStart, length - packetStart);
                    break;
                }

                if (input.readableBytes() < packetLength) {
                    // Paquet incomplet, renvoyer sans modification
                    output.write(rawData, packetStart, length - packetStart);
                    break;
                }

                // Lire le contenu du paquet
                byte[] packetData = input.readBytes(packetLength);

                // Traiter le paquet
                byte[] processedPacket = processPacket(packetData);

                // Écrire le paquet traité avec sa nouvelle taille
                workBuffer.clear();
                workBuffer.writeVarInt(processedPacket.length);
                workBuffer.writeBytes(processedPacket, 0, processedPacket.length);

                output.write(workBuffer.getData(), 0, workBuffer.getWriterIndex());
                packetsProcessed++;
            }
        } catch (Exception e) {
            // En cas d'erreur, renvoyer les données originales
            System.err.println("[!] Erreur parsing: " + e.getMessage());
            return new byte[0];
        }

        byte[] result = output.toByteArray();
        return result.length > 0 ? result : rawData;
    }

    /**
     * Traite un paquet individuel
     */
    private byte[] processPacket(byte[] packetData) {
        if (packetData.length == 0)
            return packetData;

        try {
            if (compressionHandler.isCompressionEnabled()) {
                return processCompressedPacket(packetData);
            } else {
                return processUncompressedPacket(packetData);
            }
        } catch (Exception e) {
            return packetData; // En cas d'erreur, renvoyer tel quel
        }
    }

    /**
     * Traite un paquet non compressé
     */
    private byte[] processUncompressedPacket(byte[] packetData) {
        PacketBuffer buffer = PacketBuffer.wrap(packetData, packetData.length);

        int packetId;
        try {
            packetId = buffer.readVarInt();
        } catch (Exception e) {
            return packetData;
        }

        // Détecter le Set Compression
        if (!inPlayState && packetId == PACKET_SET_COMPRESSION) {
            int threshold = buffer.readVarInt();
            compressionHandler.enableCompression(threshold);
            System.out.println("[*] Compression activée, seuil: " + threshold);
            return packetData;
        }

        // Détecter Login Success (passage en Play state)
        if (!inPlayState && packetId == PACKET_LOGIN_SUCCESS) {
            inPlayState = true;
            System.out.println("[*] Play state détecté");
            return packetData;
        }

        // Modifier Entity Velocity
        if (inPlayState && packetId == PACKET_ENTITY_VELOCITY) {
            return modifyVelocityPacket(packetData);
        }

        return packetData;
    }

    /**
     * Traite un paquet compressé
     */
    private byte[] processCompressedPacket(byte[] packetData) throws DataFormatException {
        PacketBuffer buffer = PacketBuffer.wrap(packetData, packetData.length);

        int uncompressedLength = buffer.readVarInt();
        byte[] remainingData = buffer.readBytes(buffer.readableBytes());

        // Décompresser si nécessaire
        byte[] uncompressedData;
        if (uncompressedLength == 0) {
            uncompressedData = remainingData;
        } else {
            uncompressedData = compressionHandler.decompress(remainingData, uncompressedLength);
        }

        // Traiter le paquet décompressé
        PacketBuffer uncompBuffer = PacketBuffer.wrap(uncompressedData, uncompressedData.length);
        int packetId;
        try {
            packetId = uncompBuffer.readVarInt();
        } catch (Exception e) {
            return packetData;
        }

        // Détecter Login Success
        if (!inPlayState && packetId == PACKET_LOGIN_SUCCESS) {
            inPlayState = true;
            System.out.println("[*] Play state détecté");
            return packetData;
        }

        // Modifier Entity Velocity
        if (inPlayState && packetId == PACKET_ENTITY_VELOCITY) {
            byte[] modifiedUncompressed = modifyVelocityPacketData(uncompressedData);

            // Recompresser
            return recompressPacket(modifiedUncompressed);
        }

        return packetData;
    }

    /**
     * Recompresse un paquet
     */
    private byte[] recompressPacket(byte[] uncompressedData) {
        PacketBuffer output = new PacketBuffer(uncompressedData.length + 10);

        CompressionHandler.CompressionResult result = compressionHandler.compress(uncompressedData);

        output.writeVarInt(result.uncompressedLength);
        output.writeBytes(result.data, 0, result.data.length);

        return output.toArray();
    }

    /**
     * Modifie le paquet Entity Velocity (version non compressée)
     */
    private byte[] modifyVelocityPacket(byte[] packetData) {
        return modifyVelocityPacketData(packetData);
    }

    /**
     * Modifie les données du paquet Entity Velocity
     * Format: VarInt(packetId) + VarInt(entityId) + Short(velX) + Short(velY) +
     * Short(velZ)
     */
    private byte[] modifyVelocityPacketData(byte[] data) {
        try {
            PacketBuffer buffer = PacketBuffer.wrap(data, data.length);

            int packetId = buffer.readVarInt();
            int entityId = buffer.readVarInt();
            short velX = buffer.readShort();
            short velY = buffer.readShort();
            short velZ = buffer.readShort();

            // Appliquer les modifications
            short[] modified = velocityModifier.modifyVelocity(velX, velY, velZ);

            // Log si modification significative
            if (velocityModifier.getMultiplierX() != 1.0 ||
                    velocityModifier.getMultiplierY() != 1.0 ||
                    velocityModifier.getMultiplierZ() != 1.0) {
                // Log seulement occasionnellement pour éviter le spam
                if (packetsProcessed % 100 == 0) {
                    System.out.println("[>] Velocity modifiée: (" + velX + "," + velY + "," + velZ +
                            ") → (" + modified[0] + "," + modified[1] + "," + modified[2] + ")");
                }
            }

            // Reconstruire le paquet
            PacketBuffer output = new PacketBuffer(data.length);
            output.writeVarInt(packetId);
            output.writeVarInt(entityId);
            output.writeShort(modified[0]);
            output.writeShort(modified[1]);
            output.writeShort(modified[2]);

            return output.toArray();

        } catch (Exception e) {
            return data; // En cas d'erreur, renvoyer tel quel
        }
    }
}
