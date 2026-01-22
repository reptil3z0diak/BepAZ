<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.9.4-brightgreen?style=for-the-badge&logo=minecraft" alt="Minecraft 1.9.4"/>
  <img src="https://img.shields.io/badge/Java-8+-orange?style=for-the-badge&logo=openjdk" alt="Java 8+"/>
  <img src="https://img.shields.io/badge/Protocol-110-blue?style=for-the-badge" alt="Protocol 110"/>
  <img src="https://img.shields.io/badge/Premium-Supported-gold?style=for-the-badge" alt="Premium Support"/>
</p>

<h1 align="center">⚡ ReverseAZ</h1>

<p align="center">
  <b>Minecraft 1.9.4 MITM Proxy — Intercepte et modifie le knockback en temps réel</b><br>
  <i>Supporte les comptes Premium (Microsoft/Mojang)</i>
</p>

---

## 🎯 Fonctionnalités

- 🔄 **Proxy transparent** — Se place entre le client et le serveur
- 🎮 **Modification KB en temps réel** — Change le knockback à la volée via console
- 🔐 **Support Premium** — Encryption Mojang (RSA + AES/CFB8) pour serveurs online-mode
- 📦 **Parsing complet** — VarInt, compression zlib, protocole 110
- ⚡ **Optimisé** — Buffers 64KB, TCP_NODELAY, zero-copy

## 🚀 Installation

### Prérequis
- Java 8 ou supérieur
- Maven (pour build)

### Build
```bash
git clone https://github.com/votre-username/ReverseAZ.git
cd ReverseAZ
mvn clean package
```

## 💻 Utilisation

### Mode Offline (serveurs online-mode=false)
```bash
java -jar target/reverseaz-1.0.jar <ip_serveur> <port_serveur>
```

### Mode Premium (serveurs online-mode=true)
```bash
java -jar target/reverseaz-1.0.jar <ip_serveur> <port_serveur> <bearer_token>
```

Puis connecter **Minecraft 1.9.4** à `localhost:25566`

### 🔑 Obtenir le Token Premium

1. Va sur [minecraft.net/profile](https://minecraft.net/profile) et connecte-toi
2. Ouvre les DevTools (F12) → Onglet **Network**
3. Actualise la page (F5)
4. Filtre par `profile`
5. Clique sur la requête → **Headers** → cherche `Authorization`
6. Copie la valeur après `Bearer ` (sans l'espace)

## 🎛️ Commandes Console

### Knockback
| Commande | Description | Exemple |
|----------|-------------|---------|
| `kb <mult>` | KB horizontal (X/Z) | `kb 0` = pas de recul |
| `kby <mult>` | KB vertical (Y) | `kby 0.5` = demi-hauteur |
| `kball <x> <y> <z>` | Modifie les 3 axes | `kball 0 0 0` |
| `reset` | Remet à 1.0 | |
| `status` | Affiche multiplicateurs | |

### Authentification
| Commande | Description |
|----------|-------------|
| `token <bearer>` | Configure le token Mojang |
| `auth` | Affiche l'état d'authentification |

### Exemples
```bash
# Désactiver le knockback
> kb 0
[*] KB horizontal (X/Z) = 0.0

# Configurer un token après démarrage
> token eyJhbGciOiJSUzI1NiJ9...
[*] Token configuré, récupération profil...
[*] Authentifié: MonPseudo
```

## 🏗️ Architecture

```
src/main/java/com/reverseaz/
├── MinecraftProxy.java       # 🚀 Point d'entrée + console
├── ProxySession.java         # 🔄 Session client ↔ serveur + encryption
├── VelocityModifier.java     # 🎯 Multiplicateurs thread-safe
├── auth/
│   ├── EncryptionUtil.java   # 🔐 RSA + SHA-1 + AES setup
│   ├── CipherStreams.java    # 🔒 AES/CFB8 I/O streams
│   └── MojangAuth.java       # 🎫 Session server API
└── packet/
    ├── PacketBuffer.java     # 📦 Buffer VarInt optimisé
    ├── PacketHandler.java    # 🎮 Modification paquet 0x3E
    └── CompressionHandler.java # 🗜️ Zlib compression
```

## 🔐 Encryption Flow

```
Client (ton MC 1.9.4)  →  ReverseAZ Proxy  →  Serveur Premium
       ↓                       ↓                    ↓
  Connexion NON          RSA key exchange      Connexion
  chiffrée               + Auth Mojang         chiffrée AES
                              ↓
                    Le proxy gère l'encryption
                    pour toi, tu joues en clair
```

## ⚠️ Notes Importantes

- Le **token expire** après quelques heures, renouvelle-le si l'auth échoue
- Le proxy intercepte l'encryption request et fait l'auth **à ta place**
- Ton client Minecraft se connecte en clair au proxy, seul le flux proxy↔serveur est chiffré

## 🔧 Optimisations

- **Buffer pooling** — Réutilisation des buffers
- **Direct I/O** — Buffers 64KB
- **TCP_NODELAY** — Latence réduite
- **Zero-copy** — Paquets non-vélocité passent sans modification

## 📄 License

MIT License

---

<p align="center">
  <i>Made with ☕ for the Minecraft PvP community</i>
</p>
