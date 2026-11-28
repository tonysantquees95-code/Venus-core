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
