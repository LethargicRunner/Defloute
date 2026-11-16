#!/usr/bin/env python3
"""
Génère app/src/main/assets/realesr-general-x4v3.onnx à partir des poids officiels
Real-ESRGAN (realesr-general-x4v3.pth, licence BSD-3).

Sans PyTorch : on lit le .pth (archive zip + pickle) à la main et on construit le graphe
ONNX avec le paquet `onnx`. Dépendances : numpy, onnx.

Architecture SRVGGNetCompact (3 -> 64 feat, 32 blocs conv+PReLU, sortie 48 canaux,
PixelShuffle x4, + image d'entrée agrandie en "nearest").
Entrée  : "input"  float32 [1,3,H,W]   (RGB, 0..1)
Sortie  : "output" float32 [1,3,4H,4W] (RGB, 0..1)
"""
import collections
import io
import pickle
import sys
import urllib.request
import zipfile

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

URL = "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-general-x4v3.pth"
SCALE = 4


def load_state_dict(pth_bytes):
    z = zipfile.ZipFile(io.BytesIO(pth_bytes))
    prefix = z.namelist()[0].split("/")[0]

    class Storage:
        def __init__(self, dtype, key):
            self.dtype, self.key = dtype, key

    def rebuild(storage, offset, size, stride, *_):
        arr = np.frombuffer(z.read(f"{prefix}/data/{storage.key}"), dtype=storage.dtype)
        n = int(np.prod(size)) if len(size) else 1
        return arr[offset:offset + n].reshape(size).copy()

    class Unpickler(pickle.Unpickler):
        def find_class(self, module, name):
            if name == "_rebuild_tensor_v2":
                return rebuild
            if name == "OrderedDict":
                return collections.OrderedDict
            if module == "torch" and name.endswith("Storage"):
                return {"FloatStorage": np.float32, "HalfStorage": np.float16}[name]
            return super().find_class(module, name)

        def persistent_load(self, pid):
            _, dtype, key, _, _ = pid
            return Storage(dtype, key)

    data = Unpickler(io.BytesIO(z.read(f"{prefix}/data.pkl"))).load()
    return data.get("params", data)


def build_model(sd):
    nodes, inits = [], []
    names = sorted({int(k.split(".")[1]) for k in sd if k.startswith("body.")})
    last = names[-1]
    cur = "input"
    for idx in names:
        w = sd[f"body.{idx}.weight"].astype(np.float32)
        if w.ndim == 4:  # convolution
            b = sd[f"body.{idx}.bias"].astype(np.float32)
            inits.append(numpy_helper.from_array(w, f"w{idx}"))
            inits.append(numpy_helper.from_array(b, f"b{idx}"))
            out = f"c{idx}"
            nodes.append(helper.make_node(
                "Conv", [cur, f"w{idx}", f"b{idx}"], [out],
                kernel_shape=[3, 3], pads=[1, 1, 1, 1], strides=[1, 1]))
            cur = out
        else:  # PReLU : un coefficient par canal
            inits.append(numpy_helper.from_array(w.reshape(-1, 1, 1), f"s{idx}"))
            out = f"p{idx}"
            nodes.append(helper.make_node("PRelu", [cur, f"s{idx}"], [out]))
            cur = out
    assert names[-1] == last and sd[f"body.{last}.weight"].shape[0] == 3 * SCALE * SCALE

    nodes.append(helper.make_node("DepthToSpace", [cur], ["shuffled"], blocksize=SCALE, mode="CRD"))
    inits.append(numpy_helper.from_array(np.array([], dtype=np.float32), "roi"))
    inits.append(numpy_helper.from_array(np.array([1, 1, SCALE, SCALE], dtype=np.float32), "scales"))
    nodes.append(helper.make_node(
        "Resize", ["input", "roi", "scales"], ["base"],
        mode="nearest", coordinate_transformation_mode="asymmetric", nearest_mode="floor"))
    nodes.append(helper.make_node("Add", ["shuffled", "base"], ["sum"]))
    inits.append(numpy_helper.from_array(np.array(0, dtype=np.float32), "zero"))
    inits.append(numpy_helper.from_array(np.array(1, dtype=np.float32), "one"))
    nodes.append(helper.make_node("Clip", ["sum", "zero", "one"], ["output"]))

    graph = helper.make_graph(
        nodes, "realesr_general_x4v3",
        [helper.make_tensor_value_info("input", TensorProto.FLOAT, [1, 3, "h", "w"])],
        [helper.make_tensor_value_info("output", TensorProto.FLOAT, [1, 3, "H", "W"])],
        initializer=inits)
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 13)])
    model.ir_version = 8
    onnx.checker.check_model(model)
    return model


def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/realesr-general-x4v3.onnx"
    pth = sys.argv[2] if len(sys.argv) > 2 else None
    if pth:
        raw = open(pth, "rb").read()
    else:
        print("Téléchargement des poids…")
        raw = urllib.request.urlopen(URL, timeout=120).read()
    model = build_model(load_state_dict(raw))
    import os
    os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)
    onnx.save(model, out_path)
    print("OK ->", out_path, os.path.getsize(out_path) // 1024, "Ko")


if __name__ == "__main__":
    main()
