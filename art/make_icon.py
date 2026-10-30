"""把生成的方形图标加工成 Android Adaptive Icon 资源。

思路：
1) 原图是「紫色渐变底 + 白色符号」。背景用二次多项式按位置拟合（只用圆角矩形
   内部的像素），从而反推出每个像素的前景 alpha：
       alpha = (pixel - bg) / (255 - bg)
   —— 比直接阈值二值化好在抗锯齿边缘能保留半透明，缩放后不出现锯齿。
2) 裁到符号外接框 → 等比缩放到底图边长的 60% → 居中放到透明正方形。
   Adaptive Icon 的安全区是 108dp 里的中间 66dp（约 61%），留这个比例
   是为了让圆形/方形/水滴形等各种启动器裁切后都不切到图形。
3) 输出五档密度的前景 PNG + 一个渐变背景 drawable；另出一张完整预览图。
"""
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

SRC = r"D:\workbuddy\weknora-android\art\A_minimal_flat_geometric_app_i_2026-09-30T15-25-09.png"
ROOT = r"D:\workbuddy\weknora-android"
ART = os.path.join(ROOT, "art")
RES = os.path.join(ROOT, "app", "src", "main", "res")

img = Image.open(SRC).convert("RGB")
W, H = img.size
a = np.asarray(img).astype(np.float64)

# ---------- 1. 找圆角矩形内的像素 ----------
# 紫色背景是高饱和的；白色符号和圆角外的白底都低饱和。所以：
#   ① 用高饱和像素拟合背景模型；
#   ② 「是否在图标的圆角矩形内」用洪水填充判断：从画布四角出发，只能淹到
#      圆角外的白底，淹不到被紫色包住的符号。
sat = a.max(axis=2) - a.min(axis=2)
bg_mask = sat > 40
bg_mask[:4, :] = bg_mask[-4:, :] = False
bg_mask[:, :4] = bg_mask[:, -4:] = False

# 行扫 + 列扫求圆角矩形的覆盖范围：
#   紫色背景像素的 min..max 区间即该行/列落在圆角矩形内的部分，
#   圆角外那片白底天然被排除。比洪水填充可靠（Pillow 的 floodfill
#   在纯 0/255 的 L 图上不生效）。
inside = np.zeros_like(bg_mask)
for y in range(H):
    xs_row = np.nonzero(bg_mask[y])[0]
    if xs_row.size:
        inside[y, xs_row.min():xs_row.max() + 1] = True
for x in range(W):
    ys_col = np.nonzero(bg_mask[:, x])[0]
    if ys_col.size:
        inside[ys_col.min():ys_col.max() + 1, x] = True
print("圆角矩形内像素:", int(inside.sum()), "/", W * H)

# ---------- 2. 拟合背景 (每通道 6 项二次多项式)，只用高饱和的背景像素 ----------
def design(x, y):
    x = x / W
    y = y / H
    return np.stack([np.ones_like(x), x, y, x * x, y * y, x * y], axis=1)

ys, xs = np.nonzero(bg_mask)
idx = np.random.RandomState(0).choice(len(ys), size=min(60000, len(ys)), replace=False)
A = design(xs[idx].astype(float), ys[idx].astype(float))
bg_coef = [np.linalg.lstsq(A, a[ys[idx], xs[idx], c], rcond=None)[0] for c in range(3)]

full_y, full_x = np.mgrid[0:H, 0:W]
Afull = design(full_x.ravel().astype(float), full_y.ravel().astype(float))
bg = np.stack([(Afull @ bg_coef[c]).reshape(H, W) for c in range(3)], axis=2)
print("背景拟合残差(背景像素均值):",
      round(float(np.abs(bg[bg_mask] - a[bg_mask]).mean()), 2))

# ---------- 3. 反推前景 alpha（前景假定纯白） ----------
denom = np.maximum(255.0 - bg, 1e-6)
alpha = ((a - bg) / denom).mean(axis=2)
alpha = np.clip(alpha, 0.0, 1.0)
alpha[~inside] = 0.0                      # 圆角外一律透明
alpha[alpha < 0.06] = 0.0

# ---------- 4. 裁到符号外接框 ----------
# 圆角弧线上那圈白→紫的抗锯齿会算出虚高 alpha，先用 9×9 最小值滤波
# 腐蚀掉，再取外接框；bbox 之外的 alpha 全部清零（清掉边缘残留噪声）
eroded = np.asarray(
    Image.fromarray((alpha * 255).astype(np.uint8), "L").filter(
        ImageFilter.MinFilter(9))) / 255.0
ys2, xs2 = np.nonzero(eroded > 0.5)
y0, y1, x0, x1 = int(ys2.min()), int(ys2.max() + 1), int(xs2.min()), int(xs2.max() + 1)
print("符号外接框:", (x0, y0, x1, y1), "尺寸", (x1 - x0, y1 - y0))

keep = np.zeros_like(alpha, dtype=bool)
keep[max(0, y0 - 10):min(H, y1 + 10), max(0, x0 - 10):min(W, x1 + 10)] = True
alpha[~keep] = 0.0

fg = np.zeros((H, W, 4), np.uint8)
fg[..., 0:3] = 255
fg[..., 3] = (alpha * 255).round().astype(np.uint8)
fg_img = Image.fromarray(fg, "RGBA")

pad = 8
sym = fg_img.crop((max(0, x0 - pad), max(0, y0 - pad),
                   min(W, x1 + pad), min(H, y1 + pad)))

# ---------- 5. 生成各密度前景 ----------
# Adaptive Icon: 108dp 画布；符号占 60%
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
for name, scale in DENSITIES.items():
    canvas = int(round(108 * scale))
    target = int(round(canvas * 0.60))
    w, h = sym.size
    k = target / max(w, h)
    resized = sym.resize((max(1, round(w * k)), max(1, round(h * k))), Image.LANCZOS)
    plate = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    plate.paste(resized, ((canvas - resized.width) // 2,
                          (canvas - resized.height) // 2), resized)
    d = os.path.join(RES, f"drawable-{name}")
    os.makedirs(d, exist_ok=True)
    out = os.path.join(d, "ic_launcher_foreground.png")
    plate.save(out)
    print("前景", name, plate.size, "->", out)

# 旧的 vector 前景同名，会让密度选择变得不确定，删掉
old = os.path.join(RES, "drawable", "ic_launcher_foreground.xml")
if os.path.exists(old):
    os.remove(old)
    print("已删除旧的 vector 前景:", old)

# ---------- 6. 背景渐变：从原图梯度取样 ----------
def sample(px, py):
    """直接取原图像素（背景渐变保真），越界则回退到拟合值"""
    y = int(np.clip(py, 0, H - 1))
    x = int(np.clip(px, 0, W - 1))
    if inside[y, x]:
        v = a[y, x]
    else:
        v = bg[y, x]
    return "#%02X%02X%02X" % tuple(int(round(t)) for t in v)

c_tl = sample(W * 0.10, H * 0.10)
c_br = sample(W * 0.90, H * 0.90)
print("背景渐变:", c_tl, "->", c_br)

bg_xml = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- 由 art/make_icon.py 生成：取自图标底色的左上→右下渐变 -->
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <gradient
        android:type="linear"
        android:angle="315"
        android:startColor="{c_tl}"
        android:endColor="{c_br}" />
</shape>
'''
with open(os.path.join(RES, "drawable", "ic_launcher_background.xml"),
          "w", encoding="utf-8") as f:
    f.write(bg_xml)
print("写出 drawable/ic_launcher_background.xml")

for f in ("ic_launcher.xml", "ic_launcher_round.xml"):
    p = os.path.join(RES, "mipmap-anydpi-v26", f)
    with open(p, "w", encoding="utf-8") as fp:
        fp.write('<?xml version="1.0" encoding="utf-8"?>\n'
                 '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                 '    <background android:drawable="@drawable/ic_launcher_background" />\n'
                 '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
                 '    <monochrome android:drawable="@drawable/ic_launcher_foreground" />\n'
                 '</adaptive-icon>\n')
    print("更新", p)

# ---------- 7. 预览图：圆形 + 方形各一张，模拟启动器裁切 ----------
def compose(mask, size=512):
    canvas = int(round(108 * size / 108))
    plate = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    # 背景渐变铺满
    grad = Image.new("RGB", (canvas, canvas))
    ga = np.zeros((canvas, canvas, 3))
    t = (np.mgrid[0:canvas, 0:canvas][0] / canvas + np.mgrid[0:canvas, 0:canvas][1] / canvas) / 2
    c1 = np.array([int(c_tl[1:3], 16), int(c_tl[3:5], 16), int(c_tl[5:7], 16)], float)
    c2 = np.array([int(c_br[1:3], 16), int(c_br[3:5], 16), int(c_br[5:7], 16)], float)
    ga = c1[None, None, :] * (1 - t[..., None]) + c2[None, None, :] * t[..., None]
    grad = Image.fromarray(ga.round().astype(np.uint8), "RGB").convert("RGBA")
    sym_big = sym.resize((int(canvas * 0.60), int(canvas * 0.60 * sym.size[1] / sym.size[0])),
                         Image.LANCZOS)
    grad.paste(sym_big, ((canvas - sym_big.width) // 2, (canvas - sym_big.height) // 2), sym_big)
    if mask == "circle":
        m = Image.new("L", (canvas, canvas), 0)
        from PIL import ImageDraw
        ImageDraw.Draw(m).ellipse((0, 0, canvas - 1, canvas - 1), fill=255)
        grad.putalpha(m)
    else:
        r = int(canvas * 0.22)
        m = Image.new("L", (canvas, canvas), 0)
        from PIL import ImageDraw
        ImageDraw.Draw(m).rounded_rectangle((0, 0, canvas - 1, canvas - 1), radius=r, fill=255)
        grad.putalpha(m)
    return grad

base = Image.new("RGBA", (1080, 540), (255, 255, 255, 255))
base.paste(compose("circle", 480), (40, 30), compose("circle", 480))
base.paste(compose("square", 480), (560, 30), compose("square", 480))
prev = os.path.join(ART, "icon-preview.png")
base.save(prev)
print("预览:", prev)

# 单张 512 完整图标（方形圆角），给商店/展示用
compose("square", 512).save(os.path.join(ART, "ic_launcher-512.png"))
print("完成")
