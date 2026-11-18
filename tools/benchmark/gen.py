import numpy as np, json, io
from PIL import Image
from scipy.ndimage import convolve
from skimage import data

rng = np.random.default_rng(7)
SZ = 384

def load(name):
    im = getattr(data, name)()
    if im.ndim == 2: im = np.stack([im]*3, -1)
    im = im[..., :3]
    h, w = im.shape[:2]; s = min(h, w)
    y0, x0 = (h - s)//2, (w - s)//2
    im = im[y0:y0+s, x0:x0+s]
    return np.asarray(Image.fromarray(im).resize((SZ, SZ), Image.LANCZOS), np.float32)

def motion_kernel(L, ang):
    n = int(L) + 4; n += (n % 2 == 0)
    k = np.zeros((n, n), np.float32); c = n // 2
    for t in np.linspace(-L/2, L/2, int(L*8)+1):
        x, y = c + t*np.cos(ang), c + t*np.sin(ang)
        x0, y0 = int(np.floor(x)), int(np.floor(y)); fx, fy = x-x0, y-y0
        for dx, dy, w in ((0,0,(1-fx)*(1-fy)),(1,0,fx*(1-fy)),(0,1,(1-fx)*fy),(1,1,fx*fy)):
            if 0 <= x0+dx < n and 0 <= y0+dy < n: k[y0+dy, x0+dx] += w
    return k / k.sum()

def disk_kernel(r):
    n = int(2*np.ceil(r)+3); n += (n % 2 == 0); c = n // 2
    ss = 8; ys, xs = np.mgrid[0:n*ss, 0:n*ss]/ss - n/2 + 0.5/ss
    k = (xs**2 + ys**2 <= r*r).astype(np.float32).reshape(n, ss, n, ss).mean((1, 3))
    return k / k.sum()

def gauss_kernel(s):
    n = int(6*s)|1; ax = np.arange(n) - n//2
    g = np.exp(-ax**2/(2*s*s)); k = np.outer(g, g).astype(np.float32); return k / k.sum()

def blur(im, k): return np.stack([convolve(im[..., c], k, mode='reflect') for c in range(3)], -1)

def jpeg(im, q):
    b = io.BytesIO(); Image.fromarray(np.clip(im+.5,0,255).astype(np.uint8)).save(b, 'JPEG', quality=q); b.seek(0)
    return np.asarray(Image.open(b).convert('RGB'), np.float32)

# (id, catégorie, dict de paramètres vrais)
CASES = [
 ('clean',        'aucune',  dict()),
 ('mot7',         'bougé',   dict(kind='motion', L=7)),
 ('mot13',        'bougé',   dict(kind='motion', L=13)),
 ('mot21',        'bougé',   dict(kind='motion', L=21)),
 ('def2',         'defocus', dict(kind='defocus', r=2.0)),
 ('def35',        'defocus', dict(kind='defocus', r=3.5)),
 ('def5',         'defocus', dict(kind='defocus', r=5.0)),
 ('gau15',        'doux',    dict(kind='gauss', s=1.5)),
 ('gau25',        'doux',    dict(kind='gauss', s=2.5)),
 ('noise8',       'bruit',   dict(noise=8)),
 ('noise20',      'bruit',   dict(noise=20)),
 ('jpeg30',       'jpeg',    dict(jpeg=30)),
 ('mot13_n10',    'combo',   dict(kind='motion', L=13, noise=10)),
 ('def35_n10_j60','combo',   dict(kind='defocus', r=3.5, noise=10, jpeg=60)),
 ('mot21_n6_j70', 'combo',   dict(kind='motion', L=21, noise=6, jpeg=70)),
 ('dark_n8',      'expo',    dict(expo='dark', noise=8)),
 ('bright',       'expo',    dict(expo='bright')),
 ('dark_mot13',   'combo',   dict(kind='motion', L=13, expo='dark', noise=6)),
]
IMAGES = ['astronaut', 'coffee', 'chelsea', 'rocket', 'camera', 'grass']

meta = []
for name in IMAGES:
    gt = load(name)
    Image.fromarray(gt.astype(np.uint8)).save(f'gt/{name}.png')
    for cid, cat, p in CASES:
        p = dict(p); im = gt.copy()
        if p.get('kind') == 'motion':
            p['angle_deg'] = float(rng.uniform(0, 180)); im = blur(im, motion_kernel(p['L'], np.deg2rad(p['angle_deg'])))
        elif p.get('kind') == 'defocus': im = blur(im, disk_kernel(p['r']))
        elif p.get('kind') == 'gauss': im = blur(im, gauss_kernel(p['s']))
        if p.get('expo') == 'dark': im = 255*(im/255)**2.2 * 0.8
        if p.get('expo') == 'bright': im = np.clip(im*1.35 + 60, 0, 255)
        if p.get('noise'): im = im + rng.normal(0, p['noise'], im.shape)
        im = np.clip(im, 0, 255)
        if p.get('jpeg'): im = jpeg(im, p['jpeg'])
        fn = f'{name}__{cid}.png'
        Image.fromarray(np.clip(im+.5,0,255).astype(np.uint8)).save('in/'+fn)
        meta.append(dict(file=fn, image=name, case=cid, category=cat, params=p))
json.dump(meta, open('meta.json', 'w'), indent=1)
print(len(meta), 'images de test générées')
