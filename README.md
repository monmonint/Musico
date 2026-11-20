# Musico

Diffuse le son de ton téléphone Android (Deezer, YouTube, Spotify…) vers les téléphones autour de toi, **en synchro**, pour avoir plus de son.
Les auditeurs **n'installent rien** : ils se connectent à ton partage de connexion, scannent un QR code et écoutent dans leur navigateur (Android ou iPhone).

> Statut : **prototype 0.1**. Compile et passe les tests automatiques, mais n'a pas encore été essayé sur un vrai téléphone. Les deux grandes questions ouvertes sont en bas de page.

## Comment ça marche

```
 Téléphone émetteur (appli Musico, Android 10+)                          Téléphones auditeurs (navigateur)
 ┌───────────────────────────────────────────────────────────┐            ┌──────────────────────────────────┐
 │ Deezer ──► mixeur Android ──► (volume média à 0)          │            │ page http://IP:8080/             │
 │                 │                                         │  Wi-Fi     │  • synchro d'horloge (ping/pong) │
 │                 ▼ AudioPlaybackCapture (MediaProjection)  │  hotspot   │  • décodeur Opus WebAssembly     │
 │          PCM 48 kHz stéréo, horodaté (horloge monotone)   │ ─────────► │  • tampon + planification        │
 │            │                        │                     │ WebSocket  │    Web Audio à l'heure cible     │
 │            ▼                        ▼                     │            └──────────────────────────────────┘
 │   Encodeur Opus (MediaCodec)   Lecteur local (flux alarme)│
 │            │                   joue à « capture + latence »│
 │            ▼                                              │
 │   Serveur HTTP + WebSocket intégré (port 8080) + QR code  │
 └───────────────────────────────────────────────────────────┘
```

1. **Capture** : l'API système `AudioPlaybackCapture` (Android 10+) copie le son joué par les autres applis, après une fenêtre d'autorisation (la même que pour une capture d'écran). Un service de premier plan de type `mediaProjection` la fait tourner écran éteint.
2. **Horodatage** : chaque bloc de 20 ms reçoit l'instant de sa capture, mesuré sur l'horloge monotone du téléphone émetteur (`AudioRecord.getTimestamp`).
3. **Encodage** : Opus 128 kbit/s via l'encodeur `MediaCodec` intégré à Android 10+ (repli automatique en PCM brut si l'appareil n'en a pas).
4. **Diffusion** : un mini-serveur HTTP + WebSocket écrit à la main (aucune dépendance, module `core/`) sert la page d'écoute et envoie les paquets à tous les auditeurs. Chaque auditeur a sa propre file : un téléphone lent ne ralentit pas les autres.
5. **Synchro** :
   - chaque page mesure l'écart entre son horloge et celle de l'émetteur par des ping/pong WebSocket (façon NTP, on garde les mesures au plus faible aller-retour) ;
   - chaque bloc doit sonner à `instant de capture + latence cible` (300 ms par défaut, réglable de 100 à 1000 ms dans l'appli) ;
   - la page convertit cette heure en temps `AudioContext` (via `getOutputTimestamp()`, qui inclut la latence de sortie) et programme le bloc avec `AudioBufferSourceNode.start(t)` ;
   - la dérive entre horloges est corrigée en douceur (vitesse ±0,5 %, inaudible), avec recalage franc si l'écart dépasse 40 ms.
6. **Synchro parfaite côté émetteur** : on ne peut pas retarder Deezer. Musico **met donc le volume média à 0** et **rejoue lui-même** le son capturé avec la même latence cible, sur un autre flux audio (alarme par défaut). Ce flux n'est pas capturable et l'appli s'exclut de sa propre capture : pas de boucle.
7. **Réglages de secours** : un décalage manuel (±200 ms dans l'appli pour le téléphone émetteur, ±300 ms sur chaque page) corrige un écho résiduel (par ex. une enceinte Bluetooth).

### La capture marche-t-elle avec le son coupé ?

D'après la documentation et les retours du projet scrcpy (qui utilise le même mécanisme, [scrcpy#4380](https://github.com/Genymobile/scrcpy/issues/4380), [scrcpy#5102](https://github.com/Genymobile/scrcpy/pull/5102)) : la capture de lecture (`AudioPlaybackCapture`, basée sur une « audio policy ») **n'est pas affectée par le volume système**, contrairement à l'ancienne source `REMOTE_SUBMIX`. Mettre le volume média à 0 devrait donc couper le son de Deezer sur le haut-parleur tout en gardant une capture à plein niveau. **C'est à confirmer sur ton Poco** (HyperOS peut se comporter différemment). Si ce n'est pas le cas, l'appli le signalera (« Aucun son capté… ») dès que le volume passe à 0 ; il suffira alors de désactiver « Mettre le volume média à 0 » et de baisser Deezer à la main.

Le lecteur local joue sur le flux **alarme** (son volume est indépendant du volume média). Tu peux choisir aussi notification ou accessibilité. Limite connue : sur beaucoup de téléphones, le flux alarme sort sur le haut-parleur même avec un casque branché.

## Utilisation

1. Installe l'APK sur ton téléphone (voir plus bas) et ouvre **Musico**.
2. Active ton **partage de connexion** et fais-y connecter les autres téléphones (ou mettez-vous tous sur le même Wi-Fi). Si un téléphone dit « pas d'accès Internet », il faut choisir de rester connecté.
3. Appuie sur **Démarrer la diffusion**, accepte l'autorisation audio puis la fenêtre « Commencer l'enregistrement / la diffusion » (choisis l'écran entier si on te le demande).
4. Lance ta musique dans Deezer.
5. Les auditeurs scannent le **QR code** (ou tapent l'adresse affichée, du genre `http://192.168.x.x:8080/`) et appuient sur **▶ Écouter**.
6. S'il y a un léger écho, utilise les curseurs de décalage. Pour une connexion Wi-Fi instable, augmente la latence cible.

Si l'adresse du QR code ne fonctionne pas, touche le QR code : l'appli passe à l'adresse réseau suivante (les noms d'interface du partage de connexion varient selon les constructeurs).

## Compiler et installer

### Le plus simple : GitHub Actions
Chaque push sur `main` lance le workflow **Build APK** (`.github/workflows/android.yml`) : tests + compilation. L'APK debug est téléchargeable dans l'onglet **Actions** du dépôt → dernier run → artefact **Musico-debug-apk** (un zip contenant `app-debug.apk`). Sur le téléphone, autorise l'installation d'applis inconnues pour ton navigateur / gestionnaire de fichiers.

### En local
Prérequis : JDK 17+ et le SDK Android (platform 36).
```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # chemin de ton SDK
./gradlew assembleDebug                               # -> app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Tests
```bash
./gradlew :core:test                 # serveur HTTP/WebSocket + protocole (JVM)
cd web-tests && npm test             # logique de synchro + décodeur Opus embarqué (Node, ffmpeg requis)
```
Test de bout en bout dans un vrai Chrome (2 auditeurs, vérifie qu'ils planifient chaque paquet au même instant) :
```bash
MUSICO_LIVE_SECONDS=40 ./gradlew :core:test --tests '*LiveStreamDemo*' --rerun &
cd web-tests && npm i --no-save puppeteer-core && CHROME=/usr/bin/google-chrome node e2e-browser.js
```
Résultat obtenu : 0 paquet en retard ou perdu, écart entre les deux auditeurs ≈ 2 ms.

## Organisation du code

| Chemin | Rôle |
|---|---|
| `app/src/main/java/fr/musico/MainActivity.kt` | Écran principal (UI en code, sans AndroidX), permissions, consentement MediaProjection, QR code |
| `app/.../StreamService.kt` | Service de premier plan : capture + serveur + lecteur local, volume média, verrous Wi-Fi/veille |
| `app/.../CaptureEngine.kt` | `AudioRecord` + `AudioPlaybackCaptureConfiguration`, horodatage, détection de silence, encodage Opus |
| `app/.../LocalPlayer.kt` | Relecture locale à l'heure cible (insère du silence / saute des échantillons pour rester calé) |
| `app/.../NetUtils.kt`, `QrUtils.kt` | Choix de l'adresse IP du hotspot, génération du QR code (ZXing) |
| `core/` | Serveur HTTP + WebSocket (RFC 6455) et protocole Musico, Kotlin/JVM pur, testé sur JVM |
| `app/src/main/assets/web/` | Page d'écoute : `index.html`, `app.js`, `musico-core.js` (synchro), `opus-decoder.min.js` ([opus-decoder](https://github.com/eshaz/wasm-audio-decoders) 0.7.12, MIT, WebAssembly embarqué) |
| `web-tests/` | Tests Node + test navigateur |

### Protocole
- Texte JSON. Émetteur → page : `hello` (codec, fréquence, canaux, latence, pré-skip Opus), `config` (nouvelle latence), `pong`, `status` (capture active, silence détecté). Page → émetteur : `ping {c}`, `ready` (après « Écouter »), `pause`, `stats`.
- Binaire : `[1][codec][seq u32][horodatage f64 ms][charge utile]`, en big-endian, un paquet Opus de 20 ms.

## Limites connues

- **Applis qui bloquent la capture** : une appli peut interdire qu'on capture son son (`allowAudioPlaybackCapture=false` ou politique de capture). Dans ce cas, Musico ne reçoit que du silence et affiche : « Aucun son capté alors qu'une appli joue… ». Pas de repli micro (choix volontaire).
- **Page en http, sans certificat** : le navigateur n'y autorise pas certaines API récentes (WebCodecs, AudioWorklet, Wake Lock). D'où le décodeur WebAssembly et la lecture par blocs Web Audio. L'écran de l'auditeur doit rester allumé, avec la page au premier plan : en arrière-plan ou écran verrouillé, le navigateur (surtout Safari) coupe le son.
- **iPhone** : le son Web Audio peut être coupé par le bouton silencieux. La page tente de passer en mode « lecture » (élément audio silencieux en boucle + `navigator.audioSession`), mais c'est à vérifier.
- **Enceintes/casques Bluetooth** : leur latence (100 à 300 ms) n'est pas toujours annoncée par le navigateur, donc il faut corriger avec le curseur de décalage.
- **Corrections du lecteur local** : quand il se recale (rare), on peut entendre un petit clic.
- Pas de mot de passe : toute personne sur le même réseau qui connaît l'adresse peut écouter.
- Seul l'émetteur doit être sous Android 10 ou plus. iOS ne permet pas de capturer le son des autres applis, donc un iPhone ne peut pas être émetteur.

## Questions ouvertes (à tester sur le Poco F8 Pro)

1. **Deezer autorise-t-il la capture ?** Lance Deezer, puis Musico. Si le témoin de niveau bouge et que les auditeurs entendent, c'est bon. Si Musico affiche « Aucun son capté alors qu'une appli joue », Deezer bloque la capture : il faudra tester une autre appli (YouTube Music, Spotify, lecteur local…).
2. **La capture continue-t-elle volume média à 0 ?** Active « Synchro parfaite » (activée par défaut) : le volume média passe à 0 et seul le son rejoué par Musico doit s'entendre sur ton téléphone, en même temps que les autres. Si la capture devient silencieuse à ce moment-là, désactive « Mettre le volume média à 0 ».
3. La latence cible de 300 ms suffit-elle sur ton partage de connexion avec plusieurs auditeurs ? À ajuster.
4. Le flux alarme de HyperOS convient-il pour la relecture locale (volume, sortie) ?
