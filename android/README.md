# Калькулятор вкладов — Android

Нативное Android-приложение (Java, без внешних библиотек в самом приложении).
Расчётное ядро — порт `deposit_calc/core.py`, результаты обеих версий совпадают
до копейки.

## Готовый APK

Файл `deposit-calc.apk` в корне репозитория — отладочная сборка, подписанная
стандартным отладочным ключом Android. Её достаточно, чтобы поставить
приложение на телефон:

1. Скопируйте `deposit-calc.apk` на телефон (по кабелю, через мессенджер или
   облако).
2. Откройте файл в проводнике телефона.
3. Android спросит разрешение на установку из этого источника — разрешите.
4. Приложение появится в списке под названием «Калькулятор вкладов».

Требуется Android 8.0 или новее (API 26 — из-за `java.time`). Размер — 29 КБ,
разрешений приложение не запрашивает, в интернет не ходит.

## Что умеет

* сумма, ставка, срок в месяцах, днях или годах, произвольная дата открытия;
* капитализация: нет, ежедневная, ежемесячная, ежеквартальная, раз в полгода,
  ежегодная;
* ежемесячное пополнение;
* график по месяцам: остатки, начисленные проценты, капитализация;
* сравнение всех схем капитализации на тех же условиях;
* эффективная ставка (XIRR) с учётом дат пополнений.

Все суммы — в узбекских сумах, налоги не рассчитываются.

## Сборка из исходников

Нужны JDK 17+ и Android SDK (platform 34, build-tools 34.0.0).

```bash
cd android
echo "sdk.dir=/путь/к/android-sdk" > local.properties
gradle assembleDebug          # или ./gradlew assembleDebug
```

APK окажется в `app/build/outputs/apk/debug/app-debug.apk`.

Сборка версии для распространения требует своего ключа:

```bash
keytool -genkey -v -keystore my.keystore -alias deposit -keyalg RSA -validity 10000
gradle assembleRelease
apksigner sign --ks my.keystore app/build/outputs/apk/release/app-release-unsigned.apk
```

## Тесты

```bash
cd android
gradle testDebugUnitTest
```

18 тестов: 13 на расчётное ядро (ожидания совпадают с Python-версией) и 5 на
экран через Robolectric — запуск приложения, расчёт по кнопке, вывод графика и
обработка ошибок ввода. Robolectric нужен только тестам, в APK его нет.

## Структура

| Файл | Назначение |
| --- | --- |
| `app/src/main/java/ru/depositcalc/DepositCalculator.java` | расчёты: проценты, капитализация, пополнения, XIRR |
| `app/src/main/java/ru/depositcalc/Formats.java` | форматирование сумм, ставок и дат |
| `app/src/main/java/ru/depositcalc/MainActivity.java` | единственный экран |
| `app/src/main/res/layout/activity_main.xml` | разметка экрана |
| `app/src/test/java/ru/depositcalc/` | тесты |
