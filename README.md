# Defloute

Application Android (Kotlin + Jetpack Compose) qui scanne un dossier d'images,
detecte celles qui sont floues et leur attribue un score de nettete,
en vue de les ameliorer ensuite.

## Principes
- **Lecture seule stricte** : aucune image n'est modifiee.
- **Zero dependance externe** : detection du flou par variance du Laplacien,
  implantee en pur Kotlin sur les pixels du Bitmap.
- **Acces dossier via SAF** (OPEN_DOCUMENT_TREE) : pas de permission de stockage globale.
- Decodage echantillonne (max 512 px) pour maitriser la memoire : le score de nettete
  n'a pas besoin de la pleine resolution.

## Score
Le score est la variance de la reponse du noyau Laplacien 4-connexe sur les luminances.
Image nette -> forte variance. Image floue -> faible variance.
Seuil par defaut : 100 (ajustable dans BlurDetector.kt).

## Roadmap
- V0.1 : scan dossier + scores + liste triee (plus floues d'abord)  *(en cours)*
- V0.2 : export CSV, reglage du seuil, miniatures
- V0.3 : filtrage par niveau de flou, groupes
- V1.0 : amelioration de la qualite des images floues (etape de traitement)

## Build
CI GitHub : tests unitaires puis assembleDebug a chaque push (artefact Defloute-APK).
Le wrapper Gradle n'est pas commité ; il est genere en CI (Gradle 8.7, AGP 8.4, Kotlin 2.0, minSdk 26).
