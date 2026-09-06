# Bibifoq

Téléchargeur de vidéos pour Android. Une reprise de l'idée de [Seal](https://github.com/JunkFood02/Seal),
dont le développement est arrêté, avec un objectif précis : **arrêter d'attendre après avoir collé un lien.**

État : le cœur fonctionnel est écrit et testé (96 tests unitaires), l'APK se construit et s'installe.
Voir [Ce qui n'est pas encore fait](#ce-qui-nest-pas-encore-fait) avant de compter dessus.

---

## Le problème

Dans un téléchargeur bâti sur yt-dlp, le temps ne part pas dans le téléchargement. Il part dans
l'étape d'avant : découvrir *quoi* télécharger. Et cette étape paie systématiquement trois coûts
qui n'ont rien à voir avec le site visé :

1. **Le démarrage de l'interpréteur.** Déballer et lancer Python, puis importer l'arbre des
   extracteurs. Sur un téléphone milieu de gamme c'est largement plus d'une seconde — avant la
   moindre requête réseau. Et c'est payé à chaque lien.
2. **Du travail que personne n'a demandé.** Une requête HEAD par format pour affiner des tailles
   que l'interface n'affiche de toute façon qu'en approximation ; la résolution de chaque vidéo
   d'une playlist avant d'afficher quoi que ce soit ; la lecture de fichiers de configuration.
3. **La page entière.** Aller chercher 2 Mo de HTML pour lire une balise `<meta>` située dans les
   8 premiers kilo-octets.

Aucun de ces coûts n'est nécessaire pour la majorité des liens.

## L'approche

La résolution passe par des **paliers**, du moins cher au plus cher, et le résultat est **diffusé
au fur et à mesure** plutôt que livré d'un bloc.

| Palier | Ce que c'est | Coût typique |
|---|---|---|
| **0 — Cache** | Mémoire puis disque, clé sur URL normalisée | aucune E/S |
| **1 — Extracteurs natifs** | Kotlin pur : fichier direct, manifeste HLS/DASH, oEmbed, JSON-LD `VideoObject`, OpenGraph | 1–2 petites requêtes |
| **2 — Moteur général** | yt-dlp embarqué, préchauffé et réglé pour la latence | démarrage payé au lancement de l'app |

Trois décisions font l'essentiel de la différence :

**Le préchauffage.** Le coût de démarrage du moteur est payé dans `Application.onCreate()`, en
arrière-plan, pendant que l'utilisateur regarde encore l'écran d'accueil — pas au moment où il
colle un lien.

**Le départ décalé (*hedged request*).** Le palier 1 démarre immédiatement ; le palier 2 est lancé
mais attend `headStart` avant de faire quoi que ce soit de coûteux. Si le palier 1 répond de façon
complète — un `.mp4` direct, un manifeste HLS — le palier 2 est annulé pendant son attente et
l'interpréteur ne démarre jamais. Si le palier 1 échoue, le palier 2 est déjà en route et n'a pas
payé la latence du palier 1 en plus de la sienne.

**L'affichage progressif.** Le résolveur émet un `Partial` (titre, miniature, durée) dès qu'un
extracteur natif répond, puis un `Complete` quand la liste de formats est connue. La fiche est à
l'écran pendant que le reste se décide.

**L'énumération des résolutions à la demande.** Dès qu'un palier bon marché fournit un flux
téléchargeable *et assez bon*, la résolution s'arrête là et le signale (`moreFormatsAvailable`).
Aller chercher toute l'échelle de qualités coûte un démarrage d'interpréteur, donc ce coût est
*proposé*, pas dépensé : l'interface affiche « autres résolutions », et le palier 2 ne tourne que
si vous le demandez (`ResolveMode.ALL_FORMATS`).

« Assez bon » n'est pas « téléchargeable ». Les pages annoncent couramment un flux de repli en
360p dans `og:video` — il est là pour les aperçus sociaux, pas pour être regardé — donc s'arrêter
au premier flux jouable livre du 360p à quelqu'un qui regardait du 720p. Le palier natif ne coupe
court que si sa meilleure hauteur atteint la cible (celle réglée dans l'app, sinon 720p), et une
hauteur inconnue compte comme insuffisante. Un manifeste HLS fait exception dans l'autre sens : il
contient déjà toute l'échelle, donc il n'y a rien à proposer.

S'y ajoutent : un cache disque qui survit au redémarrage du processus, une résolution spéculative
déclenchée dès qu'un lien *apparaît* (partage, presse-papiers), la fusion des requêtes concurrentes
sur une même URL, et un lecteur HTML qui **arrête de lire la réponse à `</head>`** — 50 à 100 fois
moins d'octets sur une page de vidéo typique.

**Le texte est décodé correctement.** Les titres passent par les attributs HTML, donc ils sont
échappés — et un titre français échappe beaucoup : `&laquo;`, `&eacute;`, `&rsquo;`. Le décodage
couvre tout le bloc Latin-1 plus la typographie usuelle, en **une seule passe** (décoder deux fois
transforme un `&amp;#39;` volontaire en apostrophe qui n'existait pas). Et l'encodage de la page
est déterminé avant décodage, en lisant les octets d'abord : beaucoup de sites ne déclarent leur
charset que dans un `<meta>` situé à l'intérieur même de la zone lue.

Les extracteurs natifs visent des **standards publiés** (oEmbed, schema.org, OpenGraph, HLS, DASH)
et non du scraping site par site. Un seul extracteur couvre donc une longue traîne d'hôtes d'un
coup, et ne casse pas à chaque refonte d'un site.

## Le téléchargement

- **Requêtes par plages en parallèle.** Les CDN limitent couramment une connexion unique bien en
  dessous de ce que le lien supporte. Les plages disjointes sont écrites directement à leur offset
  final via des écritures positionnelles sur `FileChannel`, donc pas de réassemblage.
- **Reprise réelle.** Un registre par plage est écrit à côté du `.part` : une reprise ne redemande
  que les octets qui ne sont jamais arrivés.
- **Lecture courte = échec.** Un serveur qui raccroche au milieu d'une plage laisserait sinon un
  trou silencieux dans le fichier. C'est traité comme l'erreur que c'est, et la plage reprend.
- **HLS**, y compris le déchiffrement AES-128, avec une fenêtre de segments bornée : un flux de dix
  mille segments consomme autant de mémoire qu'un flux de dix.

Quand la sélection impose un **muxage** (piste vidéo + piste audio séparées), le travail est confié
au moteur, qui possède déjà ffmpeg et la logique de fusion. Embarquer une seconde copie de ffmpeg
pour refaire la même chose n'apporterait rien à l'utilisateur.

## Structure

```
core/model       Modèle partagé : MediaInfo, MediaFormat, sélection de format
core/net         Client HTTP unique (un seul pool de connexions), lecteur de <head> en flux
core/resolver    Résolveur en paliers, extracteurs natifs, analyseurs HLS/DASH, cache
core/downloader  Téléchargeur segmenté, téléchargeur HLS, nommage de fichiers
app              Interface Compose, moteur yt-dlp, file Room, service de premier plan
```

Les quatre modules `core` sont du **Kotlin JVM pur, sans dépendance Android**. C'est délibéré :
toute la logique qui peut se tromper est testable sur la JVM, sans émulateur. Les 96 tests couvrent
l'ordonnancement des paliers (y compris la vérification que le moteur coûteux *n'est pas appelé*
quand le palier natif suffit), les analyseurs de manifestes, la reprise et le parallélisme du
téléchargeur, le déchiffrement HLS, et la conversion de la sortie JSON du moteur.

## Construire

```bash
./gradlew test                 # les modules core
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug   # APK par ABI dans app/build/outputs/apk/debug/
```

Nécessite un JDK 21 et le SDK Android (`compileSdk` 36). `minSdk` est 26.

Les APK font ~72 Mio en arm64 (66 Mio en armeabi-v7a). C'est presque entièrement le moteur
embarqué : ffmpeg, le runtime Python et QuickJS pèsent ensemble ~49 Mio décompressés. Le build est
donc découpé par ABI (`armeabi-v7a`, `arm64-v8a`, `x86_64`) plutôt que produire un APK universel.

Mesurez toujours sur un `./gradlew clean assembleDebug` : un build incrémental qui a changé de
version de moteur conserve les anciennes bibliothèques natives dans `merged_native_libs` et gonfle
l'APK de plus de 10 Mio.

## Les sites qui exigent une session

Certains sites ne servent aucune vidéo à un visiteur déconnecté. **Réglages → sites connectés →
se connecter à un site** ouvre un navigateur dans l'app : vous vous connectez normalement, et la
session est conservée.

Un seul fichier `cookies.txt` au format Netscape sert de point de rencontre, et c'est tout
l'intérêt : le client HTTP de l'app le lit comme *cookie jar*, et le moteur le reçoit via
`--cookies`. Se connecter une fois vaut donc pour le chemin natif **et** pour le chemin moteur,
au lieu d'un seul des deux.

## Le moteur embarqué, et pourquoi sa version compte

Le palier 2 embarque yt-dlp via `youtubedl-android`. La version figée dans l'APK vieillit vite :
les sites changent, les extracteurs suivent. Un moteur périmé se manifeste par des erreurs qui
ressemblent à des bugs de l'app — typiquement « please sign in » sur YouTube, qui est en réalité
un contrôle anti-robot côté serveur que l'extracteur de l'époque ne sait plus franchir.

Deux conséquences pratiques :

- La dépendance est épinglée sur la version la plus récente disponible, et c'est une mise à jour
  à refaire régulièrement. Aujourd'hui : `youtubedl-android` 0.18.1, qui embarque yt-dlp
  **2025.11.12** et QuickJS, ce dernier servant à `yt_dlp_ejs` pour résoudre les signatures
  JavaScript de YouTube.
- **Réglages → « Update engine »** récupère la dernière version de yt-dlp à l'exécution, sans
  réinstaller l'app. C'est le premier réflexe quand un site se met à échouer.

## YouTube

YouTube reste techniquement pris en charge par le palier 2, mais **ce chemin n'est ni optimisé ni
testé** — c'est un choix assumé. L'extraction YouTube est aussi, de loin, le cas le plus coûteux et
le plus instable pour un moteur général.

## Ce qui n'est pas encore fait

Honnêtement, pour éviter les surprises :

- **Pas de mesures terrain.** L'architecture est vérifiée par les tests (le palier coûteux est bien
  évité quand il doit l'être), mais aucun chiffre de latence n'a été relevé sur un vrai appareil.
  Les gains décrits ci-dessus sont des raisonnements, pas des mesures.
- **DASH partiel.** Les `Representation` avec un `<BaseURL>` explicite sont gérées nativement ; les
  `SegmentTemplate` sont renvoyées au moteur.
- **Pas de sous-titres**, pas de téléchargement en lot d'une playlist depuis l'interface (les
  entrées sont listées, pas encore mises en file).
- **Pas de configuration de signature release** ni de gestion des mises à jour de l'app.
- **Pas de tests instrumentés** ni de couverture automatisée de l'interface. Le premier essai sur
  appareil a d'ailleurs révélé un bug de mise en page : la colonne d'accueil ne défilait pas et le
  bouton Télécharger tombait hors écran. L'action est désormais épinglée dans une barre hors de la
  zone défilante, ce qui rend ce mode de panne impossible — mais rien n'empêche automatiquement le
  prochain du même genre.

## Licence

GPL-3.0. L'application embarque yt-dlp et des binaires ffmpeg sous GPL.
