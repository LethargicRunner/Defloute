# Banc d'essai du diagnostic automatique

Mesure objective (PSNR / SSIM) du traitement sur des photos **nettes dégradées artificiellement**,
pour régler les paramètres sur des chiffres plutôt qu'à l'œil.

- `gen.py` : génère 6 photos nettes (scikit-image) × 18 dégradations (flou de bougé 7/13/21 px, flou de mise
  au point, flou doux, bruit, JPEG, sous/surexposition, combinaisons) → `in/`, `gt/` et `meta.json`.
- `BenchAuto.kt` : exécute le moteur Kotlin (`PhotoDiagnosis` + `AutoRestore`) sur un dossier d'images.
- `evaluate.py` : compare les dossiers de sortie à la vérité (`python3 evaluate.py dossier_sortie ...`).

Compilation locale : `kotlinc BenchAuto.kt app/src/main/java/com/lethargicrunner/defloute/{BlurDetector,DeblurProcessor,ImageEnhancer,EnhanceExtras,MotionDeblur,NlmDenoise,Fft,BlurKernels,Deconvolution,PhotoDiagnosis,AutoRestore}.kt -d out`
puis `java -cp out:kotlin-stdlib.jar BenchAuto auto in sortie` (arguments facultatifs `nlmK=1.0 tlow=55 tstr=0.9`…).

## Résultats (gain moyen par rapport à l'image dégradée)

| catégorie | ancien pipeline classique | diagnostic automatique |
|---|---|---|
| flou de bougé | −5,5 dB | +1,2 dB (+1,8 sans correction de tons) |
| flou de mise au point | −6,2 dB | +1,4 dB |
| flou doux | −6,1 dB | +0,9 dB |
| bruit | −5,9 dB | +5,0 dB |
| JPEG | −9,7 dB | +0,2 dB |
| exposition | +4,3 dB | +3,5 dB |
| combinaisons | −1,9 dB | +1,5 dB |
| **total** | **−5,9 dB** | **+1,55 dB** (SSIM +0,106) |

Le diagnostic retrouve l'angle du flou de bougé à moins de 10° dans 96 % des cas (médiane 2,4°),
le bruit à ±10 %, la compression JPEG, et laisse intactes 5 photos nettes sur 6.
Un flou de 26 ou 40 px sur une image de 1200 px est retrouvé à ±3 px.
