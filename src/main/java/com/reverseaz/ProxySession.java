package com.reverseaz;

import com.reverseaz.auth.CipherStreams;
import com.reverseaz.auth.EncryptionUtil;
import com.reverseaz.auth.MojangAuth;
import com.reverseaz.packet.PacketBuffer;
import com.reverseaz.packet.PacketHandler;

import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Gère une session de proxy entre un client et le serveur
 * Supporte l'encryption Mojang pour les serveurs online-mode
 */
public class ProxySession {

    private static final int BUFFER_SIZE = 65536;

    // Packet IDs (Protocol 110 / 1.9.4)
    private static final int PACKET_HANDSHAKE = 0x00;
    private static final int PACKET_LOGIN_START = 0x00;
    private static final int PACKET_ENCRYPTION_REQUEST = 0x01;
    private static final int PACKET_ENCRYPTION_RESPONSE = 0x01;
    private static final int PACKET_LOGIN_SUCCESS = 0x02;
    private static final int PACKET_SET_COMPRESSION = 0x03;

    private final Socket clientSocket;
    private final String targetHost;
    private final int targetPort;
    private final VelocityModifier velocityModifier;
    private final MojangAuth auth;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final PacketHandler packetHandler;

    private Socket serverSocket;
    private InputStream serverIn;
    private OutputStream serverOut;
    private InputStream clientIn;
    private OutputStream clientOut;

    private int connectionState = 0; // 0=Handshake, 1=Status, 2=Login, 3=Play
    private int compressionThreshold = -1;
    private boolean serverEncrypted = false;
    private byte[] sharedSecret = null;

    public ProxySession(Socket clientSocket, String targetHost, int targetPort,
            VelocityModifier velocityModifier, MojangAuth auth) {
        this.clientSocket = clientSocket;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.velocityModifier = velocityModifier;
        this.auth = auth;
        this.packetHandler = new PacketHandler(velocityModifier);
    }

    public void start() {
        try {
            // Connexion au serveur cible
            serverSocket = new Socket();
            serverSocket.connect(new InetSocketAddress(targetHost, targetPort), 10000);
            serverSocket.setTcpNoDelay(true);

            // Configurer les sockets
            clientSocket.setTcpNoDelay(true);
            clientSocket.setReceiveBufferSize(BUFFER_SIZE);
            clientSocket.setSendBufferSize(BUFFER_SIZE);
            serverSocket.setReceiveBufferSize(BUFFER_SIZE);
            serverSocket.setSendBufferSize(BUFFER_SIZE);

            // Streams initiaux (non chiffrés)
            serverIn = new BufferedInputStream(serverSocket.getInputStream(), BUFFER_SIZE);
            serverOut = new BufferedOutputStream(serverSocket.getOutputStream(), BUFFER_SIZE);
            clientIn = new BufferedInputStream(clientSocket.getInputStream(), BUFFER_SIZE);
            clientOut = new BufferedOutputStream(clientSocket.getOutputStream(), BUFFER_SIZE);

            // Phase Login avec gestion encryption
            handleLoginPhase();

            if (!running.get())
                return;

            // Si play state, lancer relay normal
            if (connectionState == 3) {
                Thread clientToServer = new Thread(() -> relayClientToServer(), "C2S");
                Thread serverToClient = new Thread(() -> relayServerToClient(), "S2C");

                clientToServer.start();
                serverToClient.start();

                clientToServer.join();
                serverToClient.join();
            }

        } catch (IOException e) {
            System.err.println("[-] Erreur session: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("[-] Erreur: " + e.getMessage());
            e.printStackTrace();
        } finally {
            close();
        }
    }

    /**
     * Gère la phase de login avec interception de l'encryption request
     */
    private void handleLoginPhase() throws Exception {
        // Lire le Handshake du client
        PacketData handshake = readPacket(clientIn);
        if (handshake == null)
            return;

        PacketBuffer buf = new PacketBuffer(handshake.data.length + 10);
        buf.writeBytes(handshake.data, 0, handshake.data.length);
        buf.setReaderIndex(0);

        int protocolVersion = buf.readVarInt();
        String serverAddress = readString(buf);
        int serverPort = buf.readShort() & 0xFFFF;
        int nextState = buf.readVarInt();

        connectionState = nextState;
        System.out.println("[C->S] Handshake: v" + protocolVersion + " -> " + targetHost + ":" + targetPort + " state="
                + nextState);

        // Réécrire le handshake avec notre adresse cible
        PacketBuffer newHandshake = new PacketBuffer(256);
        newHandshake.writeVarInt(protocolVersion);
        writeString(newHandshake, targetHost);
        newHandshake.writeShort((short) targetPort);
        newHandshake.writeVarInt(nextState);

        sendPacket(serverOut, PACKET_HANDSHAKE, newHandshake.toArray());

        if (nextState == 1) {
            // Status: juste relay
            System.out.println("[*] Mode Status - relay simple");
            startSimpleRelay();
            return;
        }

        // Login Start du client
        PacketData loginStart = readPacket(clientIn);
        if (loginStart == null || loginStart.packetId != PACKET_LOGIN_START)
            return;

        buf = new PacketBuffer(loginStart.data.length + 10);
        buf.writeBytes(loginStart.data, 0, loginStart.data.length);
        buf.setReaderIndex(0);
        String clientUsername = readString(buf);
        System.out.println("[C->S] Login Start: " + clientUsername);

        // Si on a un token, utiliser notre profil
        String usernameToSend = clientUsername;
        if (auth != null && auth.hasAuth()) {
            usernameToSend = auth.getPlayerName();
            System.out.println("[*] Override username -> " + usernameToSend);
        }

        // Envoyer Login Start au serveur avec le username approprié
        PacketBuffer loginPacket = new PacketBuffer(256);
        writeString(loginPacket, usernameToSend);
        sendPacket(serverOut, PACKET_LOGIN_START, loginPacket.toArray());

        // Boucle de lecture serveur pendant login
        while (running.get() && connectionState == 2) {
            PacketData serverPacket = readPacket(serverIn);
            if (serverPacket == null) {
                running.set(false);
                return;
            }

            switch (serverPacket.packetId) {
                case PACKET_ENCRYPTION_REQUEST:
                    handleEncryptionRequest(serverPacket.data, clientUsername);
                    break;

                case PACKET_SET_COMPRESSION:
                    buf = new PacketBuffer(serverPacket.data.length);
                    buf.writeBytes(serverPacket.data, 0, serverPacket.data.length);
                    buf.setReaderIndex(0);
                    compressionThreshold = buf.readVarInt();
                    System.out.println(
                            "[S->P] Set Compression: " + compressionThreshold + " (client reste non compressé)");
                    // NE PAS forward au client - on gère la compression uniquement côté serveur
                    // Le client reste en mode non compressé
                    break;

                case PACKET_LOGIN_SUCCESS:
                    System.out.println("[S->C] Login Success!");
                    connectionState = 3;
                    // Envoyer au client SANS compression (il n'a pas reçu Set Compression)
                    sendPacketToClient(PACKET_LOGIN_SUCCESS, serverPacket.data);
                    break;

                default:
                    // Forward les autres paquets
                    sendPacket(clientOut, serverPacket.packetId, serverPacket.data);
                    break;
            }
        }
    }

    /**
     * Gère le paquet Encryption Request du serveur
     */
    private void handleEncryptionRequest(byte[] data, String clientUsername) throws Exception {
        System.out.println("[!] Encryption Request reçue!");

        PacketBuffer buf = new PacketBuffer(data.length);
        buf.writeBytes(data, 0, data.length);
        buf.setReaderIndex(0);

        String serverId = readString(buf);
        int pubKeyLen = buf.readVarInt();
        byte[] publicKey = buf.readBytes(pubKeyLen);
        int tokenLen = buf.readVarInt();
        byte[] verifyToken = buf.readBytes(tokenLen);

        System.out.println("[*] ServerId: " + serverId + " | PubKey: " + pubKeyLen + "b | Token: " + tokenLen + "b");

        // Générer le shared secret
        sharedSecret = EncryptionUtil.generateSharedSecret();

        // Authentification Mojang si on a un token
        if (auth != null && auth.hasAuth()) {
            String serverHash = EncryptionUtil.computeServerHash(serverId, sharedSecret, publicKey);
            System.out.println("[*] Server Hash: " + serverHash);

            boolean authSuccess = auth.joinServer(serverHash);
            if (!authSuccess) {
                System.err.println("[!] ATTENTION: Auth Mojang échouée! Le serveur peut rejeter la connexion.");
            }
        } else {
            System.out.println("[!] Pas de token - le serveur online-mode va probablement rejeter");
        }

        // Chiffrer le shared secret et verify token avec RSA
        byte[] encryptedSecret = EncryptionUtil.encryptRSA(publicKey, sharedSecret);
        byte[] encryptedToken = EncryptionUtil.encryptRSA(publicKey, verifyToken);

        // Construire l'Encryption Response
        PacketBuffer response = new PacketBuffer(512);
        response.writeVarInt(encryptedSecret.length);
        response.writeBytes(encryptedSecret, 0, encryptedSecret.length);
        response.writeVarInt(encryptedToken.length);
        response.writeBytes(encryptedToken, 0, encryptedToken.length);

        // Envoyer au serveur (non chiffré, c'est le dernier paquet clair)
        sendPacket(serverOut, PACKET_ENCRYPTION_RESPONSE, response.toArray());
        System.out.println("[P->S] Encryption Response envoyée");

        // Activer l'encryption sur la connexion serveur
        serverIn = new CipherStreams.DecryptingInputStream(serverSocket.getInputStream(), sharedSecret);
        serverOut = new CipherStreams.EncryptingOutputStream(serverSocket.getOutputStream(), sharedSecret);
        serverEncrypted = true;
        System.out.println("[*] Encryption activée avec le serveur!");

        // Note: Le client ne voit jamais l'encryption - on fait l'auth pour lui
    }

    /**
     * Relay Client -> Serveur en mode brut (bytes)
     */
    private void relayClientToServer() {
        try {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while (running.get() && (bytesRead = clientIn.read(buffer)) != -1) {
                serverOut.write(buffer, 0, bytesRead);
                serverOut.flush();
            }
        } catch (Exception e) {
            if (running.get()) {
                System.err.println("[-] Erreur C2S: " + e.getMessage());
            }
        } finally {
            running.set(false);
        }
    }

    /**
     * Relay Serveur -> Client en mode brut avec interception des paquets Velocity
     */
    private void relayServerToClient() {
        try {
            while (running.get()) {
                // Lire la taille du paquet
                int packetLength = readVarInt(serverIn);
                if (packetLength < 0)
                    break;

                // Lire tout le contenu du paquet
                byte[] rawPacket = new byte[packetLength];
                int totalRead = 0;
                while (totalRead < packetLength) {
                    int read = serverIn.read(rawPacket, totalRead, packetLength - totalRead);
                    if (read == -1)
                        break;
                    totalRead += read;
                }
                if (totalRead < packetLength)
                    break;

                // Traiter le paquet (décompression si nécessaire)
                byte[] processedPacket = processAndModifyPacket(rawPacket);

                // Envoyer au client avec le VarInt de taille
                writeVarIntTo(clientOut, processedPacket.length);
                clientOut.write(processedPacket);
                clientOut.flush();
            }
        } catch (Exception e) {
            if (running.get()) {
                System.err.println("[-] Erreur S2C: " + e.getMessage());
            }
        } finally {
            running.set(false);
        }
    }

    /**
     * Traite un paquet du serveur et le renvoie au format client (non compressé)
     * Serveur envoie: compressé si threshold >= 0
     * Client attend: toujours non compressé (pas de Set Compression reçu)
     */
    private byte[] processAndModifyPacket(byte[] rawPacket) {
        try {
            byte[] uncompressedContent;

            if (compressionThreshold < 0) {
                // Serveur n'a pas activé la compression - paquet = [ID][Payload]
                uncompressedContent = rawPacket;
            } else {
                // Serveur utilise la compression - paquet = [DataLength][Data]
                PacketBuffer buf = new PacketBuffer(rawPacket.length);
                buf.writeBytes(rawPacket, 0, rawPacket.length);
                buf.setReaderIndex(0);

                int dataLength = buf.readVarInt();
                byte[] remaining = buf.readBytes(buf.readableBytes());

                if (dataLength == 0) {
                    // Non compressé par le serveur, remaining = [ID][Payload]
                    uncompressedContent = remaining;
                } else {
                    // Compressé, décompresser
                    uncompressedContent = decompress(remaining, dataLength);
                }
            }

            // Modifier la vélocité si c'est un paquet 0x3E
            byte[] modified = modifyVelocityIfNeeded(uncompressedContent);

            // Retourner directement (le client attend [ID][Payload] sans compression)
            return modified;

        } catch (Exception e) {
            // En cas d'erreur, tenter de décompresser et retourner brut
            try {
                if (compressionThreshold >= 0) {
                    PacketBuffer buf = new PacketBuffer(rawPacket.length);
                    buf.writeBytes(rawPacket, 0, rawPacket.length);
                    buf.setReaderIndex(0);
                    int dataLength = buf.readVarInt();
                    byte[] remaining = buf.readBytes(buf.readableBytes());
                    if (dataLength == 0)
                        return remaining;
                    return decompress(remaining, dataLength);
                }
            } catch (Exception e2) {
            }
            return rawPacket;
        }
    }

    /**
     * Modifie le paquet Entity Velocity (0x3E) si c'est le bon type
     */
    private byte[] modifyVelocityIfNeeded(byte[] packet) {
        if (packet.length < 1)
            return packet;

        PacketBuffer buf = new PacketBuffer(packet.length);
        buf.writeBytes(packet, 0, packet.length);
        buf.setReaderIndex(0);

        int packetId = buf.readVarInt();

        // 0x3E = Entity Velocity en 1.9.4
        if (packetId != 0x3E)
            return packet;
        if (buf.readableBytes() < 8)
            return packet; // VarInt + 3 shorts

        try {
            int entityId = buf.readVarInt();
            short velX = buf.readShort();
            short velY = buf.readShort();
            short velZ = buf.readShort();

            // Appliquer les multiplicateurs
            short[] modified = velocityModifier.modifyVelocity(velX, velY, velZ);

            // Log si modification active
            if (velocityModifier.getMultiplierX() != 1.0 ||
                    velocityModifier.getMultiplierY() != 1.0 ||
                    velocityModifier.getMultiplierZ() != 1.0) {
                System.out.println("[KB] Velocity: (" + velX + "," + velY + "," + velZ +
                        ") -> (" + modified[0] + "," + modified[1] + "," + modified[2] + ")");
            }

            // Reconstruire le paquet
            PacketBuffer out = new PacketBuffer(packet.length);
            out.writeVarInt(packetId);
            out.writeVarInt(entityId);
            out.writeShort(modified[0]);
            out.writeShort(modified[1]);
            out.writeShort(modified[2]);

            return out.toArray();
        } catch (Exception e) {
            return packet;
        }
    }

    private void writeVarIntTo(OutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    /**
     * Démarre un relay simple pour le mode Status
     */
    private void startSimpleRelay() {
        Thread clientToServer = new Thread(() -> {
            try {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while (running.get() && (bytesRead = clientIn.read(buffer)) != -1) {
                    serverOut.write(buffer, 0, bytesRead);
                    serverOut.flush();
                }
            } catch (IOException e) {
            }
            running.set(false);
        }, "C2S-Status");

        Thread serverToClient = new Thread(() -> {
            try {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while (running.get() && (bytesRead = serverIn.read(buffer)) != -1) {
                    clientOut.write(buffer, 0, bytesRead);
                    clientOut.flush();
                }
            } catch (IOException e) {
            }
            running.set(false);
        }, "S2C-Status");

        clientToServer.start();
        serverToClient.start();

        try {
            clientToServer.join();
            serverToClient.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // =============== PACKET I/O ===============

    private PacketData readPacket(InputStream in) throws IOException {
        int length = readVarInt(in);
        if (length < 0)
            return null;

        byte[] data = new byte[length];
        int totalRead = 0;
        while (totalRead < length) {
            int read = in.read(data, totalRead, length - totalRead);
            if (read == -1)
                return null;
            totalRead += read;
        }

        // Décompression si nécessaire
        if (compressionThreshold >= 0) {
            PacketBuffer buf = new PacketBuffer(length);
            buf.writeBytes(data, 0, length);
            buf.setReaderIndex(0);

            int uncompressedLength = buf.readVarInt();
            if (uncompressedLength > 0) {
                // Décompresser
                byte[] compressed = buf.readBytes(buf.readableBytes());
                data = decompress(compressed, uncompressedLength);
            } else {
                data = buf.readBytes(buf.readableBytes());
            }
        }

        // Lire le packet ID
        PacketBuffer buf = new PacketBuffer(data.length);
        buf.writeBytes(data, 0, data.length);
        buf.setReaderIndex(0);
        int packetId = buf.readVarInt();
        byte[] payload = buf.readBytes(buf.readableBytes());

        return new PacketData(packetId, payload);
    }

    private void sendPacket(OutputStream out, int packetId, byte[] payload) throws IOException {
        PacketBuffer content = new PacketBuffer(payload.length + 10);
        content.writeVarInt(packetId);
        content.writeBytes(payload, 0, payload.length);
        byte[] contentBytes = content.toArray();

        PacketBuffer packet = new PacketBuffer(contentBytes.length + 10);

        if (compressionThreshold >= 0) {
            if (contentBytes.length >= compressionThreshold) {
                byte[] compressed = compress(contentBytes);
                packet.writeVarInt(PacketBuffer.getVarIntSize(contentBytes.length) + compressed.length);
                packet.writeVarInt(contentBytes.length);
                packet.writeBytes(compressed, 0, compressed.length);
            } else {
                packet.writeVarInt(contentBytes.length + 1);
                packet.writeVarInt(0);
                packet.writeBytes(contentBytes, 0, contentBytes.length);
            }
        } else {
            packet.writeVarInt(contentBytes.length);
            packet.writeBytes(contentBytes, 0, contentBytes.length);
        }

        out.write(packet.getData(), 0, packet.getWriterIndex());
        out.flush();
    }

    /**
     * Envoie un paquet au CLIENT toujours sans compression
     * (le client n'a pas reçu Set Compression)
     */
    private void sendPacketToClient(int packetId, byte[] payload) throws IOException {
        PacketBuffer content = new PacketBuffer(payload.length + 10);
        content.writeVarInt(packetId);
        content.writeBytes(payload, 0, payload.length);
        byte[] contentBytes = content.toArray();

        PacketBuffer packet = new PacketBuffer(contentBytes.length + 10);
        packet.writeVarInt(contentBytes.length);
        packet.writeBytes(contentBytes, 0, contentBytes.length);

        clientOut.write(packet.getData(), 0, packet.getWriterIndex());
        clientOut.flush();
    }

    private byte[] buildPacketBytes(int packetId, byte[] payload) {
        PacketBuffer content = new PacketBuffer(payload.length + 10);
        content.writeVarInt(packetId);
        content.writeBytes(payload, 0, payload.length);
        byte[] contentBytes = content.toArray();

        PacketBuffer packet = new PacketBuffer(contentBytes.length + 10);
        packet.writeVarInt(contentBytes.length);
        packet.writeBytes(contentBytes, 0, contentBytes.length);

        return packet.toArray();
    }

    private int readVarInt(InputStream in) throws IOException {
        int value = 0;
        int position = 0;
        int b;

        do {
            b = in.read();
            if (b == -1)
                return -1;
            value |= (b & 0x7F) << position;
            position += 7;
            if (position > 35)
                throw new IOException("VarInt too big");
        } while ((b & 0x80) != 0);

        return value;
    }

    private String readString(PacketBuffer buf) {
        int length = buf.readVarInt();
        byte[] bytes = buf.readBytes(length);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private void writeString(PacketBuffer buf, String s) {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.writeVarInt(bytes.length);
        buf.writeBytes(bytes, 0, bytes.length);
    }

    private byte[] decompress(byte[] data, int uncompressedLength) throws IOException {
        java.util.zip.Inflater inflater = new java.util.zip.Inflater();
        inflater.setInput(data);
        byte[] result = new byte[uncompressedLength];
        try {
            inflater.inflate(result);
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("Decompression failed", e);
        }
        inflater.end();
        return result;
    }

    private byte[] compress(byte[] data) {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater();
        deflater.setInput(data);
        deflater.finish();

        ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length);
        byte[] buffer = new byte[1024];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            baos.write(buffer, 0, count);
        }
        deflater.end();
        return baos.toByteArray();
    }

    private void close() {
        running.set(false);
        try {
            if (clientSocket != null)
                clientSocket.close();
        } catch (IOException ignored) {
        }
        try {
            if (serverSocket != null)
                serverSocket.close();
        } catch (IOException ignored) {
        }
        System.out.println("[-] Session terminée");
    }

    // Classe interne pour les données de paquet
    private static class PacketData {
        final int packetId;
        final byte[] data;

        PacketData(int packetId, byte[] data) {
            this.packetId = packetId;
            this.data = data;
        }
    }
}
