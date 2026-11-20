# Musico

Diffuse le son de ton téléphone Android (Deezer, YouTube, Spotify…) vers les téléphones autour de toi, **en synchro**, pour avoir plus de son.
Les auditeurs **n'installent rien** : ils se connectent à ton partage de connexion, scannent un QR code et écoutent dans leur navigateur (Android ou iPhone).

> Statut : **préversion 0.2**. Compile et passe tous les tests automatiques (JVM, Node, navigateur), mais n'a pas encore été essayé sur un vrai téléphone. Les questions ouvertes sont en bas de page.
>
> **Nouveautés 0.2** : latence 600 ms par défaut · **synchro automatique par le micro, sans aucun son ajouté** · affichage de l'écart réel en ms (appli + page) · lecture sans sauts (correction de dérive progressive, tampon anti-gigue, masquage des paquets perdus) · appli blindée contre les plantages (rapport de plantage, volume restauré).

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
   - chaque page mesure l'écart entre son horloge et celle de l'émetteur par des ping/pong WebSocket (façon NTP, on garde les mesures au plus faible aller-retour, on rejette les aberrantes, et l'estimation glisse d'au plus 0,5 ms par mesure au lieu de sauter) ;
   - chaque bloc doit sonner à `instant de capture + latence cible` (**600 ms par défaut**, réglable de 200 à 1500 ms dans l'appli) ;
   - la page convertit cette heure en temps `AudioContext` (via `getOutputTimestamp()`, qui inclut la latence de sortie ; la lecture attend que cette conversion soit stable) ;
   - **lecteur continu** : le son décodé va dans un tampon circulaire ; la page le **rééchantillonne elle-même** (interpolation Catmull-Rom, aussi vers 44,1 kHz sur iPhone) et programme des blocs **contigus**, sans trou ni chevauchement ;
   - la dérive est corrigée **progressivement** en faisant varier très légèrement la vitesse de lecture (±0,5 %, jusqu'à ±1 % si l'écart dépasse 30 ms : inaudible). Pas de saut : un vrai recalage (avec fondu) n'a lieu qu'au-delà de 150 ms d'écart (grosse coupure réseau) ou quand on change un réglage à la main ;
   - **paquet perdu ou en retard** : le dernier bloc est répété en s'atténuant (masquage), puis fondu au silence ; les doublons sont ignorés.
6. **Synchro parfaite côté émetteur** : on ne peut pas retarder Deezer. Musico **met donc le volume média à 0** et **rejoue lui-même** le son capturé avec la même latence cible, sur un autre flux audio (alarme par défaut). Ce flux n'est pas capturable et l'appli s'exclut de sa propre capture : pas de boucle.
7. **Synchro automatique par le micro** (nouveau, voir plus bas) : le micro de l'émetteur écoute la pièce et Musico corrige chaque auditeur tout seul.
8. **Réglages manuels** : un décalage manuel (±200 ms dans l'appli pour le téléphone émetteur, ±300 ms sur chaque page) reste disponible, en plus ou à la place de la synchro auto.

### Synchro automatique par le micro (sans bip)

Le but : que tous les téléphones sonnent **au même instant dans la pièce**, y compris en tenant compte de ce que ni l'horloge ni le navigateur ne connaissent (latence réelle du haut-parleur, enceinte Bluetooth…). Aucun son de test n'est ajouté : on se sert de la musique elle-même.

Principe :
1. Musico connaît exactement le signal qu'il diffuse (la capture, horodatée). Le micro de l'émetteur enregistre la pièce (horodaté sur la même horloge). Les deux sont ramenés à 16 kHz.
2. Une **intercorrélation GCC-PHAT** (bande 200 Hz–6 kHz, fenêtres d'1 s) entre micro et signal de référence donne un « pic » par source sonore : le haut-parleur de l'émetteur, chaque auditeur, les échos.
3. **Problème** : le haut-parleur de l'émetteur est 20 à 35 dB plus fort que les autres et tout le monde joue la même musique : impossible de savoir quel pic est quel téléphone. **Solution** : Musico demande à un seul auditeur à la fois de se décaler de **+3 ms puis −3 ms** (glissement de vitesse, inaudible). On fait la différence des deux corrélations moyennées : tout ce qui ne bouge pas (émetteur, échos, autres auditeurs) s'annule, il ne reste que l'auditeur testé, même s'il est déjà parfaitement calé. Un test statistique (z ≥ 7 par rapport au bruit des fenêtres) évite les fausses détections.
4. Écart mesuré = position de l'auditeur − position du haut-parleur de l'émetteur (la latence du micro s'annule). Musico envoie une **correction** à la page, qui l'applique en douceur. Une correction de plus de 10 ms doit être confirmée par deux mesures concordantes.
5. On passe à l'auditeur suivant (chacun son tour), en continu : la synchro suit les dérives au fil du temps.

Ce qui s'affiche :
- **Appli** : pour chaque auditeur, l'écart mesuré, la correction auto, le décalage manuel et l'état (« Synchronisé », « Mesure en cours… », « Non détecté… »), plus « Écart réel max : ±x ms ».
- **Page** : la carte « Écart mesuré avec l'émetteur » (en ms, objectif ≈ 0) et la correction auto appliquée.
- Le bouton « Remettre les corrections auto à 0 » efface tout. Le décalage manuel d'une page est respecté : la synchro auto vise ce décalage (si quelqu'un veut volontairement être en avance/retard).

Limites :
- Il faut que la **relecture locale (« Synchro parfaite ») soit active** : c'est le haut-parleur de l'émetteur qui sert de référence.
- L'auditeur doit être **à quelques mètres** du téléphone émetteur et la musique pas trop calme ; sinon « Non détecté (micro trop loin ou musique trop calme) » et la page garde le réglage manuel.
- Une mesure prend ~14 s par auditeur (3 s de stabilisation + 4 fenêtres d'1 s, ×2) ; avec 5 auditeurs, chacun est revérifié environ toutes les 70 s.
- Changer le décalage local de l'émetteur décale tout le monde quand la synchro auto est active (c'est voulu : la référence, c'est le haut-parleur de l'émetteur).
- Précision attendue : ~1 ms en simulation (pièce simulée avec échos, bruit, auditeurs 28–31 dB plus faibles) ; à confirmer en vrai.
- Android impose l'autorisation **micro** pour capturer le son des applis, donc elle est déjà demandée. Si elle est refusée ou si le micro est indisponible (pris par un appel, HyperOS…), la synchro auto est simplement désactivée et tout le reste fonctionne avec les réglages manuels. Android affiche le témoin vert « micro utilisé » pendant la diffusion.

### Robustesse

- Chaque thread (capture, micro, serveur, clients, relecture, synchro) est protégé : une erreur est affichée en français au lieu de faire planter l'appli.
- En cas de plantage malgré tout, le **volume média est restauré**, et un rapport (`dernier_plantage.txt`) est proposé au prochain lancement, avec un bouton « Partager ».
- Le serveur ignore les messages invalides et limite à 32 auditeurs.

### La capture marche-t-elle avec le son coupé ?

D'après la documentation et les retours du projet scrcpy (qui utilise le même mécanisme, [scrcpy#4380](https://github.com/Genymobile/scrcpy/issues/4380), [scrcpy#5102](https://github.com/Genymobile/scrcpy/pull/5102)) : la capture de lecture (`AudioPlaybackCapture`, basée sur une « audio policy ») **n'est pas affectée par le volume système**, contrairement à l'ancienne source `REMOTE_SUBMIX`. Mettre le volume média à 0 devrait donc couper le son de Deezer sur le haut-parleur tout en gardant une capture à plein niveau. **C'est à confirmer sur ton Poco** (HyperOS peut se comporter différemment). Si ce n'est pas le cas, l'appli le signalera (« Aucun son capté… ») dès que le volume passe à 0 ; il suffira alors de désactiver « Mettre le volume média à 0 » et de baisser Deezer à la main.

Le lecteur local joue sur le flux **alarme** (son volume est indépendant du volume média). Tu peux choisir aussi notification ou accessibilité. Limite connue : sur beaucoup de téléphones, le flux alarme sort sur le haut-parleur même avec un casque branché.

## Utilisation

1. Installe l'APK sur ton téléphone (voir plus bas) et ouvre **Musico**.
2. Active ton **partage de connexion** et fais-y connecter les autres téléphones (ou mettez-vous tous sur le même Wi-Fi). Si un téléphone dit « pas d'accès Internet », il faut choisir de rester connecté.
3. Appuie sur **Démarrer la diffusion**, accepte l'autorisation audio puis la fenêtre « Commencer l'enregistrement / la diffusion » (choisis l'écran entier si on te le demande).
4. Lance ta musique dans Deezer.
5. Les auditeurs scannent le **QR code** (ou tapent l'adresse affichée, du genre `http://192.168.x.x:8080/`) et appuient sur **▶ Écouter**.
6. Laisse « Synchro auto par le micro » activé et pose ton téléphone pas trop loin des autres : en une quinzaine de secondes par auditeur, l'écart mesuré doit tomber vers 0 ms. Sinon, utilise les curseurs de décalage. Pour une connexion Wi-Fi instable, augmente la latence cible.

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
./gradlew :core:test                 # serveur HTTP/WebSocket, protocole, synchro micro simulée (JVM)
cd web-tests && npm test             # horloges, lecteur (gigue, dérive, pertes, coupures), décodeur Opus (Node, ffmpeg requis)
```
Test de bout en bout dans un vrai Chrome (2 auditeurs, vérifie qu'ils planifient chaque paquet au même instant) :
```bash
MUSICO_LIVE_SECONDS=40 MUSICO_JITTER_MS=40 MUSICO_PROBE=1 ./gradlew :core:test --tests '*LiveStreamDemo*' --rerun &
cd web-tests && npm i --no-save puppeteer-core && CHROME=/usr/bin/google-chrome node e2e-browser.js
```
Options du flux de test : `MUSICO_JITTER_MS` (gigue réseau simulée), `MUSICO_STALL_MS` (coupure d'émission à t = 12 s), `MUSICO_PROBE=1` (envoie des corrections ±3 ms et +20 ms comme la synchro micro), `MUSICO_LATENCY`.

Résultats obtenus (0.2) :
- gigue 40 ms + corrections : 0 trou, 0 recalage, 0 sous-alimentation ; écart entre les deux auditeurs médiane 0,08 ms, max 0,23 ms ;
- gigue 30 ms + coupure de 400 ms : 0 trou, 0 recalage, 0 sous-alimentation ; médiane 0,04 ms, max 0,19 ms ;
- simulation de la synchro micro : un auditeur décalé de 37 ms converge à 0,01 ms ; deux auditeurs (+42 / −55 ms, le 2ᵉ arrivant en cours de route) convergent à 0,00 ms ; un auditeur inaudible n'est pas « corrigé » au hasard.

## Organisation du code

| Chemin | Rôle |
|---|---|
| `app/src/main/java/fr/musico/MainActivity.kt` | Écran principal (UI en code, sans AndroidX), permissions, consentement MediaProjection, QR code |
| `app/.../StreamService.kt` | Service de premier plan : capture + serveur + lecteur local, volume média, verrous Wi-Fi/veille |
| `app/.../CaptureEngine.kt` | `AudioRecord` + `AudioPlaybackCaptureConfiguration`, horodatage, détection de silence, encodage Opus |
| `app/.../LocalPlayer.kt` | Relecture locale à l'heure cible (ne se recale qu'au-delà de 30 ms, avec fondus de 5 ms) |
| `app/.../MicMonitor.kt` | Micro horodaté pour la synchro auto (repli propre si indisponible) |
| `app/.../MusicoApp.kt` | Filet anti-plantage : rapport + restauration du volume |
| `core/src/main/kotlin/fr/musico/sync/` | Synchro micro : FFT, GCC-PHAT, décimation 48→16 kHz, `AcousticAligner` (sondes ±3 ms, tests statistiques) |
| `app/.../NetUtils.kt`, `QrUtils.kt` | Choix de l'adresse IP du hotspot, génération du QR code (ZXing) |
| `core/` | Serveur HTTP + WebSocket (RFC 6455) et protocole Musico, Kotlin/JVM pur, testé sur JVM |
| `app/src/main/assets/web/` | Page d'écoute : `index.html`, `app.js`, `musico-core.js` (horloges, lecteur continu), `opus-decoder.min.js` ([opus-decoder](https://github.com/eshaz/wasm-audio-decoders) 0.7.12, MIT, WebAssembly embarqué) |
| `web-tests/` | Tests Node + test navigateur |

### Protocole
- Texte JSON. Émetteur → page : `hello` (id, codec, fréquence, canaux, latence, pré-skip Opus), `config` (nouvelle latence), `pong`, `status` (capture active, silence détecté), `offset` (`auto` = correction en ms, `probe` = sonde ±3 ms, `measured` = écart mesuré, `status`). Page → émetteur : `ping {c}`, `ready` (après « Écouter », avec décalage manuel, correction auto mémorisée et navigateur), `pause`, `stats`.
- Décalage effectif d'une page = manuel + auto + sonde.
- Binaire : `[1][codec][seq u32][horodatage f64 ms][charge utile]`, en big-endian, un paquet Opus de 20 ms.

## Limites connues

- **Applis qui bloquent la capture** : une appli peut interdire qu'on capture son son (`allowAudioPlaybackCapture=false` ou politique de capture). Dans ce cas, Musico ne reçoit que du silence et affiche : « Aucun son capté alors qu'une appli joue… ». Pas de repli micro (choix volontaire).
- **Page en http, sans certificat** : le navigateur n'y autorise pas certaines API récentes (WebCodecs, AudioWorklet, Wake Lock). D'où le décodeur WebAssembly et la lecture par blocs Web Audio. L'écran de l'auditeur doit rester allumé, avec la page au premier plan : en arrière-plan ou écran verrouillé, le navigateur (surtout Safari) coupe le son.
- **iPhone** : le son Web Audio peut être coupé par le bouton silencieux. La page tente de passer en mode « lecture » (élément audio silencieux en boucle + `navigator.audioSession`), mais c'est à vérifier.
- **Enceintes/casques Bluetooth** : leur latence (100 à 300 ms) n'est pas toujours annoncée par le navigateur ; la synchro micro la mesure et la corrige (si l'enceinte est assez près), sinon curseur manuel.
- **Relecture locale** : elle ne se recale qu'au-delà de 30 ms d'écart (avec fondus), ce qui devrait être très rare.
- Pas de mot de passe : toute personne sur le même réseau qui connaît l'adresse peut écouter.
- Seul l'émetteur doit être sous Android 10 ou plus. iOS ne permet pas de capturer le son des autres applis, donc un iPhone ne peut pas être émetteur.

## Questions ouvertes (à tester sur le Poco F8 Pro)

1. **Deezer autorise-t-il la capture ?** Lance Deezer, puis Musico. Si le témoin de niveau bouge et que les auditeurs entendent, c'est bon. Si Musico affiche « Aucun son capté alors qu'une appli joue », Deezer bloque la capture : il faudra tester une autre appli (YouTube Music, Spotify, lecteur local…).
2. **La capture continue-t-elle volume média à 0 ?** Active « Synchro parfaite » (activée par défaut) : le volume média passe à 0 et seul le son rejoué par Musico doit s'entendre sur ton téléphone, en même temps que les autres. Si la capture devient silencieuse à ce moment-là, désactive « Mettre le volume média à 0 ».
3. La latence cible de 600 ms suffit-elle sur ton partage de connexion avec plusieurs auditeurs (aucune coupure) ? À ajuster.
4. Le flux alarme de HyperOS convient-il pour la relecture locale (volume, sortie) ?
5. **Micro + capture en même temps sur HyperOS** : le niveau micro bouge-t-il dans l'appli ? L'écart mesuré de chaque auditeur converge-t-il vers 0 ms et reste-t-il stable ?
6. **Fluidité** : plus aucun saut ni accroc côté auditeurs, même en s'éloignant un peu du hotspot ?
