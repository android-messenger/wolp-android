# `app/` — приложение `org.wolpmessenger` (S1)

Оболочка Capacitor 8.6 над `messenger-ui` (ADR-0001, вариант A). Спека — `android-messenger/docs` → `specs/S1-android-app.md`, план — `specs/S1-plan.md`. Библиотека доверия устройства — сабмодуль `android/third_party/wolp-device-trust` (руководство: его `docs/API.md`).

```
app/
  package.json        только исходники моста Capacitor из npm (`npm ci`); `cap sync` не нужен —
                      конфигурация Capacitor собирается в коде (MainActivity) из BuildConfig
  android/            Gradle-проект: :app, :capacitor-android (из node_modules), includeBuild S2
  tools/stand-config.sh   android/stand.properties для стенда p1/stand/devices
```

## Сборка
```bash
git submodule update --init
cd app && npm ci && cd android
export JAVA_HOME=/opt/jdk-21.0.2 ANDROID_HOME=~/Android/Sdk   # ANDROID_HOME нужен и включённой сборке S2
../tools/stand-config.sh                                       # стенд: хосты localhost:9443/9444, пины, корень стенда
./gradlew :app:assembleProductDebug
```
`-Pwolp.deviceTrustDir=../../../wolp-device-trust` — собрать против рабочей копии S2 вместо сабмодуля.

## Конфигурация (Gradle-свойства `wolp.*`)
| Свойство | Значение |
|---|---|
| `wolp.enrollUrl`, `wolp.appUrl` | `https://enroll.<host>`, `https://app.<host>` — без пути. Источник для `DeviceTrustConfig`, `server.url` Capacitor и NSC |
| `wolp.serverPins` | `sha256/…,sha256/…` — основной и запасной ключ сервера (RUNBOOK шлюза, «серверные ключи TLS»). Из них же генерируется `<pin-set>` в `network_security_config.xml` (S1 §4.3) |
| `wolp.extraTrustAnchor` | PEM, которому доверяют только debug-сборки (корень стенда) |
| `wolp.expectedSigningDigests` | SHA-256 сертификата release-подписи; обязателен для release |
| `wolp.signing.*` | `storeFile`, `storePassword`, `keyAlias`, `keyPassword` — release-ключ (v2+v3) |
| `wolp.productVersionCode`, `wolp.pilotVersionCode` | по умолчанию 10000 и 9990 (`MMmmpp`, пилот `pp` 90–99 — перед релизом, S2 §7.3) |
| `wolp.minWebViewMajor` | минимальная версия System WebView (S1 §3 шаг 1), по умолчанию 113 |

## Варианты
| Вариант | `buildKind` | Заметки |
|---|---|---|
| `productDebug`, `pilotDebug` | `DEBUG` | RASP понижает `APP_DEBUGGABLE`/`DEBUGGER` до REPORT; доверяет `wolp.extraTrustAnchor`; `FLAG_SECURE` выключен |
| `productRelease` | `RELEASE` | R8, `verifyProductReleaseBuildKind` |
| `pilotRelease` | `PILOT` | `raspDryRun`; `verifyPilotReleaseBuildKind` |

`verify<Variant>BuildKind` (перед каждой release-сборкой) отказывает, если: тип сборки не совпадает с flavor, `versionCode` не в своём диапазоне, product с dry run, release debuggable, нет подписи или её отпечаток не в `wolp.expectedSigningDigests`, хост — стенд (`localhost`).

## CI
`.github/workflows/app.yml`: debug-сборка и JVM-тесты, обе release-сборки одноразовым ключом, отрицательные проверки `verify*`, урезанная `libwolpdt.so`, нет корня стенда в release. Сабмодуль приватного `wolp-device-trust` забирается deploy-ключом (секрет `DEVICE_TRUST_DEPLOY_KEY`; в организации deploy-ключи должны быть разрешены) или fine-grained токеном с `Contents: read` на `wolp-device-trust` (секрет `DEVICE_TRUST_TOKEN`).
