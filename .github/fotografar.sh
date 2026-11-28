set -e
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.tonimarques.sextafeira.test/androidx.test.runner.AndroidJUnitRunner | tee instrumentacao.txt
mkdir -p telas
cp instrumentacao.txt telas/
for f in $(adb shell run-as com.tonimarques.sextafeira ls files/telas | tr -d '\r'); do
  adb exec-out run-as com.tonimarques.sextafeira cat "files/telas/$f" > "telas/$f"
done
grep -q "^OK (" instrumentacao.txt
# o relatório e um mosaico reduzido das telas (loja fictícia) também vão para o registro da execução
cat telas/relatorio.txt
pip install -q pillow
python3 -I - <<'PY'
import glob, base64, io
from PIL import Image, ImageDraw
fs = sorted(glob.glob('telas/robo-*.png')) + sorted(glob.glob('telas/respostas-*.png')) + ['telas/arte-produto.jpg', 'telas/arte-dica.jpg']
W, H, C = 360, 780, 4
g = Image.new('RGB', (C * (W + 10), ((len(fs) + C - 1) // C) * (H + 30)), 'white')
d = ImageDraw.Draw(g)
for i, f in enumerate(fs):
    im = Image.open(f).convert('RGB'); im.thumbnail((W, H))
    x, y = (i % C) * (W + 10), (i // C) * (H + 30)
    d.text((x + 4, y + 8), f, fill='black'); g.paste(im, (x, y + 30))
b = io.BytesIO(); g.save(b, 'JPEG', quality=70)
t = base64.b64encode(b.getvalue()).decode()
for i in range(0, len(t), 4000):
    print('MOSAICO:' + t[i:i + 4000])
PY
