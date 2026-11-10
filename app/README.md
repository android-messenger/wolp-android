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
../tools/stand-config.sh                                       # стенд: хосты localhost:9443/9444, пины, корень стенда, uiHost
(cd ../../../../messenger/front && VITE_API_ORIGIN=https://localhost:9444 VITE_TURN_HOST=localhost \
  VITE_MESSENGER_ADMIN_KEY=stand-messenger-admin-key npm run build:app)   # UI в APK (wolp.uiDist)
./gradlew :app:assembleProductDebug
```
`-Pwolp.deviceTrustDir=../../../wolp-device-trust` — собрать против рабочей копии S2 вместо сабмодуля.

## Конфигурация (Gradle-свойства `wolp.*`)
| Свойство | Значение |
|---|---|
| `wolp.enrollUrl`, `wolp.appUrl` | `https://enroll.<host>`, `https://app.<host>` — без пути. Источник для `DeviceTrustConfig` и NSC; `appUrl` — хост API для упакованного UI |
| `wolp.uiHost` | имя хоста локального origin UI `https://<uiHost>` (Capacitor `hostname`, `security/09`). **Same-site** с хостом `appUrl` (иначе cookie Kratos не дойдёт до API) и **не** хост, к которому приложение ходит по сети на 443 (Capacitor перехватил бы его). Стенд — `localhost`; прод — несуществующий поддомен того же сайта, например `ui.hormi.isolutions.io` (сам `hormi.isolutions.io` занят presigned-ссылками вложений на `:443`). Проверяет `validateWolpConfig` |
| `wolp.uiDist` | каталог app-сборки `messenger-ui` (`VITE_API_ORIGIN=<wolp.appUrl> npm run build:app`), по умолчанию `../../../messenger/front/dist-app` (соседний клон). `bundle<Variant>WebUi` копирует его в `assets/public`, отказывает, если сборка сделана для другого API origin, и не берёт `push-sw.js`/`*.map`/`stats.html` |
| `wolp.serverPins` | `sha256/…,sha256/…` — основной и запасной ключ сервера (RUNBOOK шлюза, «серверные ключи TLS»). Из них же генерируется `<pin-set>` в `network_security_config.xml` (S1 §4.3) |
| `wolp.extraTrustAnchor` | PEM, которому доверяют только debug-сборки (корень стенда) |
| `wolp.googleServices` | `google-services.json` проекта Firebase (S1 §7.1). Из файла при сборке берутся `project_id`, `mobilesdk_app_id`, `api_key` и `project_number` (в `BuildConfig`). Сам файл в репозиторий не попадает; плагин google-services не используется, Firebase инициализируется вручную, только messaging. Без файла push выключен; release-сборка без него отклоняется (`verify*BuildKind`). Владелец держит файл в `~/wolp-keys/google-services.json` |
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

## WebView (S1-3, S1 §4)
| Где | Что |
|---|---|
| `WolpWebViewClient` | главный фрейм — только UI из APK и `enroll`; переход документа на `app.<host>` запрещается (`security/09`: сервер не получает фрейм с мостом); клиентский сертификат только для `app.<host>` (`proceed`, иначе `ignore()` → `ensureFresh()`, перезагрузка не чаще раза в 30 с только при `Active`); `X-Device-Deny` только с `app.<host>` → `web/DenyPolicy` (JVM-тесты); 401 на документе `app` **без** заголовка (окно кэша `check`, HANDOFF вопрос 8) → `CHECK_SESSION`: сессию спрашиваем у самого Kratos (`whoami` шлюза отвечает из того же кэша), нет — вход; `onReceivedSslError` → `cancel()` и «не удалось проверить сервер»; `onRenderProcessGone` → `true` |
| `WolpWebChromeClient` | камера/микрофон — только упакованный UI `https://<uiHost>` и только `AUDIO/VIDEO_CAPTURE`; выбор файлов — Capacitor |
| `MainActivity` | `credentialsVersion` → `clearClientCertPreferences` (последнее обработанное значение — `SharedPreferences`); смена состояния S2 и возврат после > 5 мин в фоне → порядок §3 (`Messenger` — без перезагрузки, иначе `SplashActivity`); ping при `onResume` и раз в 5 мин; `USER_MISMATCH` → `checkSessionBinding` → выход из Kratos и экран «другой пользователь»; «Назад» — `history.back()`, в корне — в фон; VaultReset — скрипт `DOCUMENT_START` удаляет `e2ee-signal`/`e2ee-device`/`e2ee-plaintext` и подтверждает через `WebMessageListener` |
| вставки | плагин Capacitor `SystemBars` (`insetsHandling: css`): `env(safe-area-inset-*)` при `viewport-fit=cover` на WebView ≥ 140, иначе переменные `--safe-area-inset-*` |

## Плагины (S1-4, S1 §5)
| Где | Что |
|---|---|
| `web/BridgeGuard` | заменяет `WebMessageListener` моста Capacitor (`androidBridge`) своим: только главный фрейм ровно упакованного UI `https://<uiHost>` (`sourceOrigin`, `isMainFrame`), остальным — ответ `PERMISSION_DENIED` (промис отклоняется, а не висит, как было у Capacitor из iframe). Ставится из `load()` плагина — Capacitor вызывает его до загрузки UI. Канал ответа Capacitor хранит в приватном поле `MessageHandler` — его и `Bridge.msgHandler` R8 не трогает (`proguard-rules.pro`); если поставить не удалось — плагины закрыты |
| `web/BridgePolicy`, `CallOrigins` | правило origin (JVM-тесты) и запись origin каждого пропущенного вызова по `callbackId`: плагин в начале каждого метода берёт запись и сверяет сам; нет записи (мост ушёл на `JavascriptInterface`, вызов мимо защиты) — отказ |
| `plugins/DeviceTrustPlugin` | `getState` (`{state, notAfter?}`), `getDeviceInfo` (`securityLevel` только если S2 знает его из предпроверки этого процесса; `deviceId` S2 наружу не отдаёт), `vaultWrap/Unwrap` — base64, назначения только `e2ee`/`e2ee-plaintext` (`web/JsDeviceTrust`, JVM-тесты); ошибки — `PERMISSION_DENIED`, `INVALID_ARGUMENT`, `VAULT_KEY_GONE`, `VAULT_LOCKED`, `VAULT_CORRUPT`, `UNAVAILABLE`; входные и выходные байты затираются |

| `plugins/FilesPlugin`, `web/JsFiles` | `saveDownload({url, fileName?, mimeType?})` (S1 §5.4): URL только на origin `app.<host>`, диалог SAF, загрузка клиентом S2 (пины, mTLS) с cookie Kratos потоком в документ, PDF — открыть после сохранения; `INVALID_ARGUMENT`, `DOWNLOAD_FAILED` (документ удаляется), отмена → `saved: false`; та же защита моста (`BridgeGuard.GUARDED`) |

В debug Capacitor пишет в logcat параметры вызовов (`methodData`), включая данные `vaultWrap` — `setLoggingEnabled` только для debug.

## CI
`.github/workflows/app.yml`: debug-сборка и JVM-тесты, обе release-сборки одноразовым ключом, отрицательные проверки `verify*`, урезанная `libwolpdt.so`, нет корня стенда в release. Сабмодуль приватного `wolp-device-trust` забирается deploy-ключом (секрет `DEVICE_TRUST_DEPLOY_KEY`; в организации deploy-ключи должны быть разрешены) или fine-grained токеном с `Contents: read` на `wolp-device-trust` (секрет `DEVICE_TRUST_TOKEN`).
