package com.reverseaz.auth;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Authentification Mojang/Microsoft pour les serveurs online-mode
 */
public class MojangAuth {

    private static final String SESSION_SERVER = "https://sessionserver.mojang.com/session/minecraft/join";
    private static final String PROFILE_API = "https://api.minecraftservices.com/minecraft/profile";

    private String accessToken;
    private String playerName;
    private String playerUUID;

    public MojangAuth() {
    }

    /**
     * Configure le token d'accès (Bearer token de minecraft.net)
     */
    public void setAccessToken(String token) {
        this.accessToken = token;
    }

    /**
     * Récupère le profil du joueur à partir du token
     * 
     * @return true si succès
     */
    public boolean fetchProfile() {
        if (accessToken == null || accessToken.isEmpty()) {
            System.out.println("[Auth] Pas de token configuré");
            return false;
        }

        try {
            URL url = new URL(PROFILE_API);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int code = conn.getResponseCode();
            if (code != 200) {
                System.out.println("[Auth] Erreur profil: HTTP " + code);
                return false;
            }

            String response = readResponse(conn.getInputStream());

            // Parse JSON simple (sans dépendance)
            playerName = extractJsonValue(response, "name");
            playerUUID = extractJsonValue(response, "id");

            if (playerName != null && playerUUID != null) {
                System.out.println("[Auth] Profil récupéré: " + playerName + " (" + playerUUID + ")");
                return true;
            }

            System.out.println("[Auth] Profil invalide: " + response);
            return false;

        } catch (Exception e) {
            System.out.println("[Auth] Erreur: " + e.getMessage());
            return false;
        }
    }

    /**
     * Envoie la requête "join" au serveur de session Mojang
     * Doit être appelé AVANT d'envoyer le paquet Encryption Response
     */
    public boolean joinServer(String serverHash) {
        if (accessToken == null || playerUUID == null) {
            System.out.println("[Auth] Token ou UUID manquant");
            return false;
        }

        try {
            URL url = new URL(SESSION_SERVER);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            // JSON payload
            String json = String.format(
                    "{\"accessToken\":\"%s\",\"selectedProfile\":\"%s\",\"serverId\":\"%s\"}",
                    accessToken,
                    playerUUID,
                    serverHash);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code == 204 || code == 200) {
                System.out.println("[Auth] Session join réussi!");
                return true;
            }

            String error = readResponse(conn.getErrorStream());
            System.out.println("[Auth] Erreur join: HTTP " + code + " - " + error);
            return false;

        } catch (Exception e) {
            System.out.println("[Auth] Erreur join: " + e.getMessage());
            return false;
        }
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getPlayerUUID() {
        return playerUUID;
    }

    public boolean hasAuth() {
        return accessToken != null && !accessToken.isEmpty() && playerUUID != null;
    }

    private String readResponse(InputStream is) throws IOException {
        if (is == null)
            return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }

    /**
     * Parse JSON simple - extrait une valeur string
     * Gère les espaces autour de : comme "key" : "value"
     */
    private String extractJsonValue(String json, String key) {
        // Chercher "key" puis : puis "value" avec espaces possibles
        String keyQuoted = "\"" + key + "\"";
        int keyPos = json.indexOf(keyQuoted);
        if (keyPos == -1)
            return null;

        // Chercher : après la clé
        int colonPos = json.indexOf(":", keyPos + keyQuoted.length());
        if (colonPos == -1)
            return null;

        // Chercher le premier " après le :
        int valueStart = json.indexOf("\"", colonPos + 1);
        if (valueStart == -1)
            return null;
        valueStart++; // Skip the opening quote

        // Chercher le " fermant
        int valueEnd = json.indexOf("\"", valueStart);
        if (valueEnd == -1)
            return null;

        return json.substring(valueStart, valueEnd);
    }
}
