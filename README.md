# Defloute — V2

Détection des photos floues **et** défloutage, en pur Kotlin (zéro dépendance externe).

## V2 (0.3.0) — Défloutage
- Onglet **Déflouter** : choisir une image (SAF), traitement, comparaison avant/après (chips), score de netteté avant → après.
- Pipeline (`DeblurProcessor.kt`) :
  1. Upscale bilinéaire x2 (désactivable) ;
  2. Unsharp mask multi-échelle (rayons 1 / 3 / 6 px, montants proportionnels à la force) ;
  3. Clamp doux au-delà de ±40 de détail pour éviter les halos.
- Slider de force 0–100 %, recalcul immédiat.
- Sauvegarde via SAF dans un dossier choisi, nom `…-defloute-<horodatage>.jpg` (qualité 95).
- Traitement hors UI thread (`Dispatchers.Default`), survit à la rotation (ViewModel).

## V1 conservée
- Onglet **Scanner** : scan récursif SAF, variance du Laplacien multi-échelle, seuils réglables (persistés `defloute_settings`), liste triée floues d'abord.

## Conventions
- Kotlin 2.0 + Compose + Material 3, AGP 8.4, minSdk 26, Gradle 8.7 (wrapper non commité, généré en CI).
- CI « Build APK » : tests unitaires puis assembleDebug, APK renommé `Defloute-v<version>.apk`, icône adaptative vectorielle.
