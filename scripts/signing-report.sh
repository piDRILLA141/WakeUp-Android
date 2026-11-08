#!/usr/bin/env bash
set -euo pipefail
apk_path="$1"
report_path="$2"
old_apk_path="${3:-}"
signing_tools="${ANDROID_HOME:-/workspace/android-sdk}/build-tools/36.0.0"
mkdir -p "$report_path"
"$signing_tools/apksigner" verify --verbose --print-certs "$apk_path" > "$report_path/certificate-current.txt"
"$signing_tools/zipalign" -c -P 16 4 "$apk_path"
"$signing_tools/aapt2" dump badging "$apk_path" > "$report_path/apk-metadata.txt"
new_digest=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$report_path/certificate-current.txt")
old_digest="не проверен"
comparison="Старый APK не предоставлен для сравнения."
if [[ -n "$old_apk_path" && -f "$old_apk_path" ]]; then
  "$signing_tools/apksigner" verify --print-certs "$old_apk_path" > "$report_path/certificate-previous.txt"
  old_digest=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$report_path/certificate-previous.txt")
  if [[ "$new_digest" == "$old_digest" ]]; then
    comparison="Сертификаты совпадают: подпись допускает установку обновления поверх этого APK."
  else
    comparison="Сертификаты РАЗЛИЧАЮТСЯ: Android не разрешит обновление поверх этого APK. Не удаляйте установленное приложение. Сначала экспортируйте будильники и настройки; для совместимого обновления нужен исходный закрытый ключ."
  fi
fi
apk_bytes=$(stat -c '%s' "$apk_path")
mode="временный debug-ключ текущего окружения; между GitHub runner ключ не сохраняется"
if [[ -n "${PODYOM_KEYSTORE_PATH:-}" ]]; then mode="постоянный ключ из защищённых GitHub Secrets / указанного локального хранилища"; fi
cat > "$report_path/SIGNING.md" <<REPORT
# Подпись APK 0.2.0

Размер APK: $apk_bytes байт.
Режим: $mode.

Новый сертификат SHA-256: $new_digest.
Старый сертификат SHA-256: $old_digest.

$comparison

Совпадение сертификата необходимо для обновления. Сам ключ нельзя восстановить из
APK или сертификата. Старые CI-запуски 0.1.0 не сохраняли закрытый debug-ключ.
Если такой ключ отдельно не сохранён, гарантировать обновление поверх их APK нельзя.
Сама версия 0.2.0 сохраняет applicationId ru.podyom, увеличивает versionCode до 2,
не меняет Room-таблицы и читает старые JSON-настройки со значениями по умолчанию.

Файлы сертификатов содержат только публичные сведения. Закрытые ключи и пароли
в артефакт и историю Git не входят. Настройка постоянного ключа описана в UPGRADE_0.2.0.md.
Проверка ZIP-выравнивания выполнена; это не испытание на физическом 16-КБ устройстве.
REPORT
cat "$report_path/SIGNING.md"
