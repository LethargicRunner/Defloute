import sys, json, numpy as np, collections, warnings; warnings.filterwarnings('ignore')
from PIL import Image
from skimage.metrics import structural_similarity as ssim, peak_signal_noise_ratio as psnr
B='/home/claude/work/bench'; meta=json.load(open(B+'/meta.json'))
def load(p): return np.asarray(Image.open(p).convert('RGB'),np.float32)
dirs=sys.argv[1:]
R=collections.defaultdict(lambda: collections.defaultdict(list))
for m in meta:
    gt=load(f"{B}/gt/{m['image']}.png"); inp=load(f"{B}/in/{m['file']}"); b=(psnr(gt,inp,data_range=255), ssim(gt,inp,channel_axis=2,data_range=255))
    for d in dirs:
        o=load(f"{B}/{d}/{m['file']}"); s=(psnr(gt,o,data_range=255), ssim(gt,o,channel_axis=2,data_range=255))
        dp = s[0]-b[0] if np.isfinite(b[0]) else (s[0]-60 if np.isfinite(s[0]) else 0)
        R[m['category']][d].append((dp, s[1]-b[1]))
cats=['aucune','bougé','defocus','doux','bruit','jpeg','expo','combo']
print('%-9s'%'gain vs entrée'+''.join('%-26s'%d for d in dirs))
for c in cats:
    print('%-9s'%c+''.join('%+5.2f dB / SSIM %+.3f     '%tuple(np.mean(R[c][d],0)) for d in dirs))
allm={d:np.mean([v for c in cats for v in R[c][d] if np.isfinite(v[0])],0) for d in dirs}
print('%-9s'%'TOTAL'+''.join('%+5.2f dB / SSIM %+.3f     '%tuple(allm[d]) for d in dirs))
