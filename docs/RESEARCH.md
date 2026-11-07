# Проверенные источники

Просмотрены 7 ноября 2026 года. Внешние материалы использованы для понимания
поведения; код, бренд, иллюстрации и аудио Alarmy не копировались.

- [Alarmy](https://alar.my/en) и [описание заданий](https://alar.my/en/blog/how-to-choose-alarmy-mission):
  задания и их последовательности как способ осознанного отключения сигнала.
- [Описание Wake Up Check](https://alarmy-android.zendesk.com/hc/en-us/articles/900000759543--Wake-Up-Check-How-does-the-Wake-Up-Check-feature-work):
  отложенное подтверждение после задания и повторный звонок без ответа.
- [Планирование Android alarms](https://developer.android.com/develop/background-work/services/alarms):
  отдельные точные экземпляры вместо неточного setRepeating. USE_EXACT_ALARM выбран
  для приложения, основная функция которого — будильник. SCHEDULE_EXACT_ALARM имеет
  maxSdkVersion=32 и нужен только на Android 12/12L; на Android 13+ конфликтующих
  действующих разрешений нет. Перед постановкой вызывается canScheduleExactAlarms.
- [Создание уведомлений](https://developer.android.com/develop/ui/compose/notifications/create-notification):
  каналы, runtime-разрешение уведомлений и видимый доступ к текущему событию.
- [Full-Screen Intent](https://developer.android.com/about/versions/14/behavior-changes-14#secure-fsi):
  проверяется canUseFullScreenIntent; настройки открываются официальным Intent.
- [Типы foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types):
  systemExempted допускает приложения с USE_EXACT_ALARM/SCHEDULE_EXACT_ALARM.
  Для публикации в Google Play потребуются соответствующие декларации; публикация
  не выполнялась.
- [Direct Boot](https://developer.android.com/privacy-and-security/direct-boot):
  изолированы device-protected runtime и credential-protected личные данные.

Использованы существующие опубликованные стабильные зависимости, версии закреплены
в Gradle. AGP 8.13.2, Gradle 8.13, Kotlin 2.2.21, compile/target SDK 36, min SDK 28,
Compose BOM 2025.10.01, Room 2.8.3, CameraX 1.5.1, ZXing 3.5.3. Это совместимый
зафиксированный набор, а не обещание, что каждая библиотека — самая новая.

OriginOS 6 на физическом iQOO 15R не исследована в этой среде. Поэтому приложение
не объявляет автозапуск «разрешённым» и не выдумывает названия OEM-пунктов меню.
