# Architecture

Notes de conception : ce que fait chaque partie, et surtout *pourquoi elle est faite comme ça*.

## Vue d'ensemble

```
                       ┌──────────────────────────────┐
   URL collée /        │        MediaResolver         │
   partagée / ────────▶│  (orchestre les paliers)     │──▶ Flow<ResolveUpdate>
   presse-papiers      └───────────┬──────────────────┘         │
                                   │                            ├─ Started
              ┌────────────────────┼────────────────────┐       ├─ Partial  (aperçu)
              ▼                    ▼                    ▼       ├─ Complete
     ┌─────────────────┐  ┌─────────────────┐  ┌──────────────┐ └─ Failed
     │ Palier 0        │  │ Palier 1        │  │ Palier 2     │
     │ MetadataCache   │  │ NativeExtractor │  │ RemoteEngine │
     │ mémoire + disque│  │ (Kotlin pur)    │  │ (yt-dlp)     │
     └─────────────────┘  └─────────────────┘  └──────────────┘
```

Puis, une fois un format choisi :

```
   FormatSelection ──▶ DownloadCoordinator ──┬─▶ SegmentedDownloader  (flux HTTPS unique)
                                             ├─▶ HlsDownloader        (m3u8, AES-128)
                                             └─▶ EngineDownloader     (muxage, DASH exotique)
```

## Le contrat de résolution

`MediaResolver.resolve()` renvoie un `Flow<ResolveUpdate>` et non une valeur unique. C'est le point
central de la conception.

```kotlin
sealed interface ResolveUpdate {
    data class Started(val normalizedUrl: String)
    data class Partial(val info: MediaInfo, val elapsed: Duration)   // affichable, pas téléchargeable
    data class Complete(val info: MediaInfo, val elapsed: Duration, val winner: Provenance)
    data class Failed(val error: ResolveError, val elapsed: Duration)
}
```

`MediaInfo.completeness` distingue les deux états :

- `PREVIEW` — titre, miniature, durée sont fiables ; la liste de formats est vide ou partielle.
  Suffisant pour dessiner une fiche, insuffisant pour choisir une qualité.
- `COMPLETE` — la liste de formats fait autorité. On peut lancer un téléchargement.

`MediaInfo.mergedWith()` fusionne un résultat plus riche par-dessus un aperçu sans jamais effacer
ce qui était déjà affiché : un champ absent du résultat récent conserve la valeur de l'aperçu.
C'est ce qui évite qu'une miniature apparaisse puis disparaisse pendant la mise à niveau.

## L'ordonnancement des paliers

Dans `MediaResolver.resolve()` :

1. Normaliser l'URL. Échec immédiat si ce n'est pas une URL http(s) exploitable.
2. Consulter le cache. Un `COMPLETE` frais termine la résolution sans aucune E/S. Un `PREVIEW`
   frais est émis puis la résolution continue.
3. Lancer le palier 2 **avec un délai** (`headStart`) — il ne fait rien pendant ce délai.
4. Lancer le palier 1 immédiatement.
5. Si le palier 1 répond `COMPLETE` → annuler le palier 2 (encore dans son `delay`) et terminer.
   S'il répond `PREVIEW` → l'émettre et attendre le palier 2.
6. Fusionner et émettre `Complete`.

Le `headStart` est adaptatif, parce que la bonne valeur dépend de ce qu'on attend :

| Situation | Délai | Raison |
|---|---|---|
| Un extracteur *autoritaire* revendique l'URL (fichier direct, manifeste) | 4 s | Il répondra complètement en bien moins que ça ; le moteur ne doit jamais démarrer. |
| Le moteur est déjà chaud | 80 ms | Le démarrer coûte presque rien, inutile d'attendre. |
| Cas général | 220 ms | Assez pour couvrir un succès natif rapide, assez court pour ne pas se voir sinon. |

### Pourquoi le résultat du palier 2 est enveloppé

```kotlin
val remoteDeferred: Deferred<Result<MediaInfo>>? = remoteEngine?.let { engine ->
    async { delay(headStart); runCatchingCancellable { ... } }
}
```

Un `async` qui échoue annule sa portée parente **au moment de l'échec**, pas au moment du `await()`.
Sans l'enveloppe, une panne du moteur détruirait le `channelFlow` avant que le repli sur l'aperçu
natif puisse s'exécuter. Le `Result` garde l'échec inerte jusqu'à ce qu'on le lise.

### `runCatchingCancellable`

`runCatching` attrape aussi `CancellationException`, ce qui casse silencieusement la concurrence
structurée : une coroutine annulée renvoie un `Result` en échec et continue au lieu de se dérouler.
Tous les chemins « essaie ceci, sinon passe au suivant » utilisent `runCatchingCancellable`, qui
relance l'annulation et n'attrape que les vraies erreurs.

## Les extracteurs natifs

```kotlin
interface NativeExtractor {
    val name: String
    val priority: Int              // le plus bas passe en premier et l'emporte à la fusion
    fun canHandle(url: HttpUrl): Boolean   // synchrone, sans E/S
    suspend fun extract(context: ExtractionContext): MediaInfo?   // null = « rien trouvé », pas une erreur
}
```

| Extracteur | Priorité | Résultat | Coût |
|---|---|---|---|
| `DirectMediaExtractor` | 0 | `COMPLETE` | 1 requête (HEAD, ou GET par plage en repli) |
| `OEmbedExtractor` | 10 | `PREVIEW` | 1 requête si l'hôte est connu, 2 sinon |
| `StructuredDataExtractor` | 20 | `PREVIEW` | 0 requête supplémentaire — réutilise le `<head>` partagé |

`ExtractionContext.pageHead()` est mémoïsé : plusieurs extracteurs veulent le même document, il
n'est récupéré qu'une fois. `prefetchPageHead()` lance la requête avant que quiconque l'attende.

Les extracteurs de priorité `< 10` sont considérés comme **autoritaires** : pour une URL qu'ils
revendiquent, ils répondent complètement ou pas du tout. Ils sont donc essayés seuls d'abord, et un
succès court-circuite tout le reste.

## Le lecteur de `<head>`

`HeadScanner.readUntilHeadClose()` lit la réponse par blocs et s'arrête à `</head>`. Toutes les
métadonnées destinées aux machines (OpenGraph, cartes Twitter, JSON-LD, découverte oEmbed) y vivent,
alors qu'une page de vidéo pèse couramment 1 à 3 Mo.

Deux détails qui comptent :

- La recherche ne re-scanne qu'un court chevauchement à chaque bloc, pas tout le tampon accumulé —
  sinon le coût serait quadratique sur une page qui ne ferme jamais son `<head>`.
- Le décodage passe par un `InputStreamReader` avec le charset déclaré, donc un caractère multi-octets
  à cheval sur deux blocs n'est pas coupé en deux.

## Le téléchargeur segmenté

`SegmentedDownloader` sonde d'abord la ressource avec un **GET par plage d'un octet** plutôt qu'un
HEAD : cela répond aux deux questions à la fois (taille totale via `Content-Range`, support des
plages via le code 206) et beaucoup de CDN refusent HEAD tout en servant des plages.

Les écritures utilisent `FileChannel.write(ByteBuffer, position)`, sûres en concurrence sur un même
canal : chaque plage écrit à son offset final, il n'y a ni fichier temporaire par morceau ni étape
de réassemblage.

Le registre de reprise (`.resume`, à côté du `.part`) enregistre les bornes de chaque plage et les
octets déjà obtenus. Il est ignoré si l'URL ou la taille totale ont changé — un registre périmé ne
doit pas corrompre un fichier différent.

**Une lecture courte est une erreur.** Si une plage se termine avec moins d'octets que demandé, la
plage est relancée depuis sa propre progression. Sans ce contrôle, un serveur qui raccroche produit
un fichier avec un trou et un téléchargement qui se déclare réussi. Un test couvre précisément ce
cas.

## Le routage du téléchargement

| Condition | Moteur | Pourquoi |
|---|---|---|
| Flux unique, `Protocol.HTTPS` | `SegmentedDownloader` | Chemin rapide : plages parallèles, reprise. |
| Flux unique, `Protocol.HLS` | `HlsDownloader` | Segments en parallèle borné, écriture en ordre, AES-128. |
| `requiresMuxing` | `EngineDownloader` | Le moteur possède ffmpeg et la logique de fusion. |
| `DASH` par template, `UNSUPPORTED` | `EngineDownloader` | Le moteur sait, nous non. |

## Frontières des modules

Les quatre modules `core` ne dépendent d'aucune API Android. Ce n'est pas de la pureté gratuite :
c'est ce qui permet de tester l'ordonnancement des paliers, les analyseurs de manifestes, la reprise
et le déchiffrement sur la JVM, en quelques secondes, sans émulateur.

`RemoteEngine` est l'interface qui garde cette frontière. `YtDlpEngine` l'implémente dans le module
`app` ; les tests fournissent un `FakeRemoteEngine` qui **compte ses appels**, ce qui permet
d'affirmer la propriété qui compte vraiment : *le palier coûteux n'est pas appelé quand le palier
natif suffit.*

## Points d'extension

- **Un nouveau site** : implémenter `NativeExtractor`, l'ajouter à la liste dans `ServiceLocator`.
  Une priorité `< 10` s'il répond de façon autoritaire, `> 10` sinon.
- **Un autre moteur** : implémenter `RemoteEngine`.
- **Un autre cache** : implémenter `MetadataCache` (`DiskMetadataCache` empile déjà disque et mémoire).
- **Un muxeur natif** : remplacer la branche `requiresMuxing` de `DownloadCoordinator.strategyFor()`.
