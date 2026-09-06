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

S'y ajoutent : un cache disque qui survit au redémarrage du processus, une résolution spéculative
déclenchée dès qu'un lien *apparaît* (partage, presse-papiers), la fusion des requêtes concurrentes
sur une même URL, et un lecteur HTML qui **arrête de lire la réponse à `</head>`** — 50 à 100 fois
moins d'octets sur une page de vidéo typique.

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

Les APK font ~50 Mo : c'est le runtime Python et les binaires ffmpeg du moteur. Le build est donc
découpé par ABI (`armeabi-v7a`, `arm64-v8a`, `x86_64`) plutôt que produire un APK universel.

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
- **Pas de tests instrumentés** ni de captures d'écran de l'interface : elle compile et l'APK
  s'installe, mais elle n'a pas été parcourue sur un appareil.

## Licence

GPL-3.0. L'application embarque yt-dlp et des binaires ffmpeg sous GPL.
