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
(cd ../../../messenger/front && VITE_API_ORIGIN=https://localhost:9444 VITE_TURN_HOST=localhost \
  npm run build:app)   # UI в APK (wolp.uiDist): клон messenger-ui рядом с wolp-android, в каталоге messenger
./gradlew :app:assembleProductDebug
```
`-Pwolp.deviceTrustDir=../../../wolp-device-trust` — собрать против рабочей копии S2 вместо сабмодуля.

## Конфигурация (Gradle-свойства `wolp.*`)
| Свойство | Значение |
|---|---|
| `wolp.enrollUrl`, `wolp.appUrl` | `https://enroll.<host>`, `https://app.<host>` — без пути. Источник для `DeviceTrustConfig` и NSC; `appUrl` — хост API для упакованного UI |
| `wolp.uiHost` | имя хоста локального origin UI `https://<uiHost>` (Capacitor `hostname`, `security/07-app-shell.md`). **Same-site** с хостом `appUrl` (иначе cookie Kratos не дойдёт до API) и **не** хост, к которому приложение ходит по сети на 443 (Capacitor перехватил бы его). Стенд — `localhost`; прод — несуществующий поддомен того же сайта, например `ui.m.example.org` (сам `m.example.org` занят presigned-ссылками вложений на `:443`). Проверяет `validateWolpConfig` |
| `wolp.uiDist` | каталог app-сборки `messenger-ui` (`VITE_API_ORIGIN=<wolp.appUrl> npm run build:app`), по умолчанию `../../../messenger/front/dist-app` (соседний клон). `bundle<Variant>WebUi` копирует его в `assets/public`, отказывает, если сборка сделана для другого API origin, и не берёт `push-sw.js`/`*.map`/`stats.html` |
| `wolp.serverPins` | `sha256/…,sha256/…` — основной и запасной ключ сервера (RUNBOOK шлюза, «серверные ключи TLS»). Из них же генерируется `<pin-set>` в `network_security_config.xml` (S1 §4.3) |
| `wolp.extraTrustAnchor` | PEM, которому доверяют только debug-сборки (корень стенда) |
| `wolp.e2eeRoot`, `wolp.e2eeIssuers` | секретные чаты: PEM-файл с корневым сертификатом центра, выдающего сертификаты устройств этого контура, и SHA-256 открытого ключа (SPKI, hex) каждого центра, которому разрешено их выдавать, через запятую. Ключ собеседника принимается только с сертификатом устройства под этим корнем. Задаются вместе; без них операции моста `e2ee*` отвечают `UNAVAILABLE`. Для release-сборки обязательны: упакованный веб-клиент шифрует только через библиотеку. Значения для стенда — `app/contours/wolpertinger/README.md`. Отпечаток издателя: `openssl x509 -in int.pem -noout -pubkey \| openssl pkey -pubin -outform DER \| sha256sum` |
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

`verify<Variant>BuildKind` (перед каждой release-сборкой) отказывает, если: тип сборки не совпадает с flavor, `versionCode` не в своём диапазоне, product с dry run, release debuggable, нет подписи или её отпечаток не в `wolp.expectedSigningDigests`, хост — стенд (`localhost`), не задан `wolp.googleServices`, не заданы `wolp.e2eeRoot` и `wolp.e2eeIssuers`, в `wolp.e2eeRoot` лежит сертификат из `p1/stand` (закрытые ключи стенда лежат в репозитории).

## WebView (S1-3, S1 §4)
| Где | Что |
|---|---|
| `WolpWebViewClient` | главный фрейм — только UI из APK и `enroll`; переход документа на `app.<host>` запрещается (`security/07-app-shell.md`: сервер не получает фрейм с мостом); клиентский сертификат только для `app.<host>` (`proceed`, иначе `ignore()` → `ensureFresh()`, перезагрузка не чаще раза в 30 с только при `Active` или `RenewalDue`); `X-Device-Deny` только с `app.<host>` → `web/DenyPolicy` (JVM-тесты); 401 на документе `app` **без** заголовка (окно кэша `check`, HANDOFF вопрос 8) → `CHECK_SESSION`: сессию спрашиваем у самого Kratos (`whoami` шлюза отвечает из того же кэша), нет — вход; `onReceivedSslError` → `cancel()` и «не удалось проверить сервер»; `onRenderProcessGone` → `true` |
| `WolpWebChromeClient` | камера/микрофон — только упакованный UI `https://<uiHost>` и только `AUDIO/VIDEO_CAPTURE`; выбор файлов — Capacitor |
| `MainActivity` | `Bridge` создаётся только с одноразовым допуском от `SplashActivity` (`launch/LaunchHandoff`): экземпляр, восстановленный системой после гибели процесса или открытый уведомлением звонка, UI не загружает и уходит в порядок §3; `credentialsVersion` → `clearClientCertPreferences` (последнее обработанное значение — `SharedPreferences`); смена состояния S2 и возврат после > 5 мин в фоне → порядок §3 (`Messenger` — без перезагрузки, иначе `SplashActivity`); ping при `onResume` и раз в 5 мин; `USER_MISMATCH` → `checkSessionBinding` → выход из Kratos и экран «другой пользователь»; «Назад» — `history.back()`, в корне — в фон; VaultReset — скрипт `DOCUMENT_START` удаляет `e2ee-signal`/`e2ee-device`/`e2ee-plaintext` и подтверждает через `WebMessageListener` |
| вставки | плагин Capacitor `SystemBars` (`insetsHandling: css`): `env(safe-area-inset-*)` при `viewport-fit=cover` на WebView ≥ 140, иначе переменные `--safe-area-inset-*` |

## Запуск и нажатия на уведомления
| Где | Что |
|---|---|
| `SplashActivity` | единственный экспортируемый компонент (лаунчер). Из своего Intent не берёт ни `data`, ни extras: их может прислать любое приложение |
| `push/TapActivity` | цель `PendingIntent` уведомлений и «Принять» на экране звонка; не экспортируется, ничего не показывает: кладёт нажатие в `LaunchHandoff` и открывает `SplashActivity` |
| `launch/LaunchHandoff` | передача в памяти процесса (JVM-тесты): нажатие (отбрасывается через 2 минуты), причина возврата из `MainActivity` («другой пользователь», «не удалось проверить сервер»), допуск `MainActivity` |

## Плагины (S1-4, S1 §5)
| Где | Что |
|---|---|
| `web/BridgeGuard` | заменяет `WebMessageListener` моста Capacitor (`androidBridge`) своим: только главный фрейм ровно упакованного UI `https://<uiHost>` (`sourceOrigin`, `isMainFrame`), остальным — ответ `PERMISSION_DENIED` (промис отклоняется, а не висит, как было у Capacitor из iframe). Ставится из `load()` плагина — Capacitor вызывает его до загрузки UI. Канал ответа Capacitor хранит в приватном поле `MessageHandler` — его и `Bridge.msgHandler` R8 не трогает (`proguard-rules.pro`); если поставить не удалось — плагины закрыты |
| `web/BridgePolicy`, `CallOrigins` | правило origin (JVM-тесты) и запись origin каждого пропущенного вызова по `callbackId`: плагин в начале каждого метода берёт запись и сверяет сам; нет записи (мост ушёл на `JavascriptInterface`, вызов мимо защиты) — отказ |
| `plugins/DeviceTrustPlugin` | `getState` (`{state, notAfter?}`), `getDeviceInfo` (`securityLevel` только если S2 знает его из предпроверки этого процесса; `deviceId` S2 наружу не отдаёт), `vaultWrap/Unwrap` — base64, назначения только `e2ee-plaintext` — сохранённые тексты секретных сообщений — и `chat-cache` — текст, который веб-клиент хранит в кэше на диске: история обычных чатов, очередь отправки, черновики, списки, имена, по блобу на запись; вложения не шифруются (`web/JsDeviceTrust`, JVM-тесты); назначение `e2ee`, которым веб-клиент раньше оборачивал свои ключи, закрыто; ошибки — `PERMISSION_DENIED`, `INVALID_ARGUMENT`, `VAULT_KEY_GONE`, `VAULT_LOCKED`, `VAULT_CORRUPT`, `UNAVAILABLE`; входные и выходные байты затираются |
| `plugins/DeviceTrustPlugin`, секретные чаты | восемь операций поверх `DeviceTrust.e2ee` ([спецификация 002](https://github.com/android-messenger/wolp-device-trust/blob/main/specs/002-secret-chat-crypto/spec.md), история 4): `e2eeEncrypt({peer, text})` → `{envelope}`, `e2eeDecrypt({peer, envelope})` → `{text}`, `e2eePeerState({peer})` → `{trust, keyChanged}` (`keyChanged` держится, пока не вызвана `e2eeKeyChangeSeen`), `e2eeSafetyNumber({peer})` → `{digits?}`, `e2eeSetVerified({peer, verified, digits})` — `digits` — код, который был на экране; если ключ с тех пор сменился, отказ `reverify`, `e2eeKeyChangeSeen({peer})` — пользователь закрыл сообщение о смене ключа, `e2eeResetSession({peer})`, `e2eeWipe()` — выход из учётной записи: библиотека стирает ключи, сессии и отметки и убирает запись из службы ключей. `peer` — учётная запись собеседника. Ключи и сессии остаются в библиотеке, ни одна операция ключа не отдаёт. Отказ — код библиотеки как есть: `locked`, `no_passport`, `bad_passport`, `reverify`, `multi_device`, `no_device`, `no_network`, `duplicate`, `undecryptable`, `not_enrolled`; кроме них — `PERMISSION_DENIED`, `INVALID_ARGUMENT`, `UNAVAILABLE` (сборка без `wolp.e2eeRoot`). В журнал идёт операция и код, без текста и собеседника. Веб-клиент в приложении шифрует секретные чаты и параметры звонков только этими операциями (`messenger-ui`, история 6) |
| `plugins/DeviceTrustPlugin`, `sessionEnd()` | последний шаг выхода: завершает сессию Kratos (если сервер доступен) и в любом случае стирает cookie WebView → `{ended}`. Без этого выход без сети оставлял cookie, и при следующем запуске пользователь снова был в учётной записи |
| `plugins/FilesPlugin`, `web/JsFiles` | `saveDownload({url, fileName?, mimeType?})` (S1 §5.4): URL только на origin `app.<host>` или на тот же хост на порту 443 (presigned-ссылки вложений), не больше 64 МБ, диалог SAF, загрузка клиентом S2 (пины, mTLS) с cookie Kratos потоком в документ, PDF — открыть после сохранения; `INVALID_ARGUMENT`, `DOWNLOAD_FAILED` (документ удаляется), отмена → `saved: false`; та же защита моста (`BridgeGuard.GUARDED`) |
| `plugins/PushPlugin`, `push/` | `register` (спрашивает `POST_NOTIFICATIONS`, отправляет токен FCM и ключ нагрузки на `app.<host>/firebase-push/tokens`), `unregister` (до выхода из Kratos; ставит отметку «вышел» — файл `push-signed-out`: пока она есть, ни одно уведомление не показывается; если токен FCM удалить не удалось — сети нет, — удаление повторяется при запуске, при возврате на экран и при появлении сети; отметку снимает удачная регистрация после входа — `push/SignedOut.kt`, спецификация F5), `updateContactNames` (имена для уведомлений, хранятся обёрнутыми Vault), `setForegroundConversation`, `wsClosed`; события `notificationTap`, `appBackground`, `appForeground`. Нагрузка push — AES-256-GCM ключом устройства; текст уведомления из push не берётся (S1 §5.2, §7.0, §7.1) |
| `plugins/CallPlugin`, `call/` | `started`, `ringback`, `mediaChanged`, `ended`, `getAudioRoutes`, `setAudioRoute`; события `audioRouteChanged`, `hangupFromNotification`, `audioFocusLost`, `callDecline`. Входящий по push — `RingService` и `IncomingCallActivity` (поверх блокировки, «Принять» требует разблокировки), отказ — через веб-клиент или коротким нативным WebSocket (S1 §4.5, §5.3, §7.3) |
| `plugins/AppSettingsPlugin` | `setLanguage({lang: "en" \| "es"})` — язык нативных экранов и уведомлений (S1 §5.5); `getAppInfo()` → `{versionName, versionCode}` — версия APK для экрана «Инфо» |

В debug Capacitor пишет в logcat параметры вызовов (`methodData`), включая данные `vaultWrap` и текст `e2eeEncrypt` — `setLoggingEnabled` только для debug. В остальных сборках в журнал из консоли WebView попадают только отказы политики CSP (строка `csp: …`): адрес и директива, без текста сообщений.

Сборка с `libsignal` (приходит с библиотекой): включён core library desugaring, из APK исключены тестовая библиотека и сборки `libsignal` для настольных систем, выпускной APK собирается только для `arm64-v8a` (отладочный — ещё и для `x86_64`, под эмулятор). `libsignal_jni.so` добавляет к APK около 9,6 МБ.

## CI
`.github/workflows/app.yml`: debug-сборка, JVM-тесты и Android lint, обе release-сборки одноразовым ключом, отрицательные проверки `verify*` и упаковки UI (чужой API origin, cross-site `uiHost`, service worker в APK), урезанная `libwolpdt.so`, нет корня стенда в release. UI в CI — заглушка. Actions закреплены по SHA коммита. Сабмодуль приватного `wolp-device-trust` забирается deploy-ключом (секрет `DEVICE_TRUST_DEPLOY_KEY`; в организации deploy-ключи должны быть разрешены) или fine-grained токеном с `Contents: read` на `wolp-device-trust` (секрет `DEVICE_TRUST_TOKEN`).
