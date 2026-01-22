package com.reverseaz;

import com.reverseaz.auth.MojangAuth;
import java.io.*;
import java.net.*;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minecraft 1.9.4 MITM Proxy - Intercepte et modifie les paquets de vélocité
 * (KB)
 * Supporte l'encryption pour les serveurs online-mode
 * 
 * Usage: java -jar reverseaz-1.0.jar <server_ip> <server_port> [token]
 */
public class MinecraftProxy {

    private static final int LOCAL_PORT = 25566;
    private final String targetHost;
    private final int targetPort;
    private final ExecutorService executor;
    private final VelocityModifier velocityModifier;
    private final MojangAuth auth;
    private volatile boolean running = true;

    public MinecraftProxy(String targetHost, int targetPort, String accessToken) {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.executor = Executors.newCachedThreadPool();
        this.velocityModifier = new VelocityModifier();
        this.auth = new MojangAuth();

        // Configurer l'auth si token fourni
        if (accessToken != null && !accessToken.isEmpty()) {
            auth.setAccessToken(accessToken);
            System.out.println("[*] Token configuré, récupération du profil...");
            if (auth.fetchProfile()) {
                System.out.println("[*] Compte premium: " + auth.getPlayerName());
            } else {
                System.out.println("[!] Échec récupération profil - mode offline");
            }
        }
    }

    public void start() {
        // Thread pour les commandes console
        executor.submit(this::handleConsoleCommands);

        try (ServerSocket serverSocket = new ServerSocket(LOCAL_PORT)) {
            printBanner();

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("[+] Nouvelle connexion de: " + clientSocket.getInetAddress());

                    // Créer une nouvelle session avec l'auth
                    ProxySession session = new ProxySession(clientSocket, targetHost, targetPort, velocityModifier,
                            auth);
                    executor.submit(session::start);

                } catch (SocketException e) {
                    if (running) {
                        System.err.println("[-] Erreur socket: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[-] Erreur fatale: " + e.getMessage());
            e.printStackTrace();
        } finally {
            executor.shutdownNow();
        }
    }

    private void printBanner() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║          ⚡ ReverseAZ - Minecraft 1.9.4 Proxy ⚡              ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║  Proxy:    localhost:" + LOCAL_PORT + "                                   ║");
        System.out.println("║  Serveur:  " + padRight(targetHost + ":" + targetPort, 48) + " ║");
        System.out.println("║  Mode:     "
                + padRight(auth.hasAuth() ? "PREMIUM (" + auth.getPlayerName() + ")" : "OFFLINE", 48) + " ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║  Commandes KB:                                               ║");
        System.out.println("║    kb <mult>         - KB horizontal (X/Z)                   ║");
        System.out.println("║    kby <mult>        - KB vertical (Y)                       ║");
        System.out.println("║    kball <x> <y> <z> - Les 3 axes                            ║");
        System.out.println("║    reset             - Remet à 1.0                           ║");
        System.out.println("║    status            - Affiche multiplicateurs               ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║  Auth:                                                       ║");
        System.out.println("║    token <bearer>    - Configure le token Mojang             ║");
        System.out.println("║    auth              - Affiche l'état d'authentification     ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private void handleConsoleCommands() {
        Scanner scanner = new Scanner(System.in);
        while (running) {
            try {
                if (!scanner.hasNextLine()) {
                    Thread.sleep(100);
                    continue;
                }

                String line = scanner.nextLine().trim();
                String[] parts = line.split("\\s+", 2);

                if (parts.length == 0 || parts[0].isEmpty())
                    continue;

                String cmd = parts[0].toLowerCase();
                String args = parts.length > 1 ? parts[1] : "";

                switch (cmd) {
                    case "kb":
                        if (!args.isEmpty()) {
                            double mult = Double.parseDouble(args);
                            velocityModifier.setHorizontalMultiplier(mult);
                            System.out.println("[*] KB horizontal (X/Z) = " + mult);
                        } else {
                            System.out.println("[!] Usage: kb <multiplicateur>");
                        }
                        break;

                    case "kby":
                        if (!args.isEmpty()) {
                            double mult = Double.parseDouble(args);
                            velocityModifier.setMultiplierY(mult);
                            System.out.println("[*] KB vertical (Y) = " + mult);
                        } else {
                            System.out.println("[!] Usage: kby <multiplicateur>");
                        }
                        break;

                    case "kball":
                        String[] xyz = args.split("\\s+");
                        if (xyz.length >= 3) {
                            double x = Double.parseDouble(xyz[0]);
                            double y = Double.parseDouble(xyz[1]);
                            double z = Double.parseDouble(xyz[2]);
                            velocityModifier.setMultipliers(x, y, z);
                            System.out.println("[*] KB = X:" + x + " Y:" + y + " Z:" + z);
                        } else {
                            System.out.println("[!] Usage: kball <x> <y> <z>");
                        }
                        break;

                    case "reset":
                        velocityModifier.reset();
                        System.out.println("[*] KB reset à 1.0");
                        break;

                    case "status":
                        System.out.println("[*] Multiplicateurs actuels:");
                        System.out.println("    X: " + velocityModifier.getMultiplierX());
                        System.out.println("    Y: " + velocityModifier.getMultiplierY());
                        System.out.println("    Z: " + velocityModifier.getMultiplierZ());
                        break;

                    case "token":
                        if (!args.isEmpty()) {
                            auth.setAccessToken(args);
                            System.out.println("[*] Token configuré, récupération profil...");
                            if (auth.fetchProfile()) {
                                System.out.println("[*] Authentifié: " + auth.getPlayerName());
                            } else {
                                System.out.println("[!] Échec récupération profil");
                            }
                        } else {
                            System.out.println("[!] Usage: token <bearer_token>");
                            System.out.println(
                                    "    Récupère ton token sur minecraft.net/profile (F12 > Network > profile > Authorization)");
                        }
                        break;

                    case "auth":
                        if (auth.hasAuth()) {
                            System.out.println("[*] Mode: PREMIUM");
                            System.out.println("    Joueur: " + auth.getPlayerName());
                            System.out.println("    UUID: " + auth.getPlayerUUID());
                        } else {
                            System.out.println("[*] Mode: OFFLINE");
                            System.out.println("    Utilise 'token <bearer>' pour configurer l'auth");
                        }
                        break;

                    case "quit":
                    case "exit":
                    case "stop":
                        System.out.println("[*] Arrêt du proxy...");
                        running = false;
                        System.exit(0);
                        break;

                    case "help":
                        printBanner();
                        break;

                    default:
                        System.out.println("[!] Commande inconnue: " + cmd + " (tape 'help' pour la liste)");
                        break;
                }
            } catch (NumberFormatException e) {
                System.out.println("[!] Valeur numérique invalide");
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    private static String padRight(String s, int n) {
        return String.format("%-" + n + "s", s);
    }

    public static void main(String[] args) {
        System.out.println();
        System.out.println("  ██████╗ ███████╗██╗   ██╗███████╗██████╗ ███████╗███████╗ █████╗ ███████╗");
        System.out.println("  ██╔══██╗██╔════╝██║   ██║██╔════╝██╔══██╗██╔════╝██╔════╝██╔══██╗╚══███╔╝");
        System.out.println("  ██████╔╝█████╗  ██║   ██║█████╗  ██████╔╝███████╗█████╗  ███████║  ███╔╝ ");
        System.out.println("  ██╔══██╗██╔══╝  ╚██╗ ██╔╝██╔══╝  ██╔══██╗╚════██║██╔══╝  ██╔══██║ ███╔╝  ");
        System.out.println("  ██║  ██║███████╗ ╚████╔╝ ███████╗██║  ██║███████║███████╗██║  ██║███████╗");
        System.out.println("  ╚═╝  ╚═╝╚══════╝  ╚═══╝  ╚══════╝╚═╝  ╚═╝╚══════╝╚══════╝╚═╝  ╚═╝╚══════╝");
        System.out.println();

        if (args.length < 2) {
            System.out.println("Usage: java -jar reverseaz-1.0.jar <server_ip> <server_port> [token]");
            System.out.println();
            System.out.println("Arguments:");
            System.out.println("  server_ip    IP ou hostname du serveur Minecraft");
            System.out.println("  server_port  Port du serveur (généralement 25565)");
            System.out.println("  token        (Optionnel) Bearer token de minecraft.net pour serveurs online-mode");
            System.out.println();
            System.out.println("Exemple:");
            System.out.println("  java -jar reverseaz-1.0.jar play.hypixel.net 25565");
            System.out.println("  java -jar reverseaz-1.0.jar mc.server.com 25565 eyJhbGciOiJS...");
            System.out.println();
            System.out.println("Pour obtenir le token:");
            System.out.println("  1. Va sur minecraft.net/profile et connecte-toi");
            System.out.println("  2. Ouvre F12 > Network > Filtre 'profile'");
            System.out.println("  3. Copie la valeur après 'Bearer ' dans l'en-tête Authorization");
            return;
        }

        String serverIp = args[0];
        int serverPort;
        String token = args.length > 2 ? args[2] : null;

        try {
            serverPort = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.err.println("[-] Port invalide: " + args[1]);
            return;
        }

        MinecraftProxy proxy = new MinecraftProxy(serverIp, serverPort, token);
        proxy.start();
    }
}
