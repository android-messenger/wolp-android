# Стенд `devices` — живая регистрация телефона (S3 задача 3)

nginx (хост `enroll`, S3 §10) → KratosGate с модулем `devices` → `device-signer` (unix-сокет) + Kratos + PostgreSQL.
KratosGate и `device-signer` собираются из соседнего клона `android-messenger/gate` (`GATE_DIR`, по умолчанию `../../../../gate`).

```bash
./up.sh                                   # out/ (TLS, CA устройств), docker compose, ожидание /readyz
ANDROID_SERIAL=<serial> ./enroll.sh       # пользователь Kratos → вход → пароль онбординга → регистрация на телефоне
docker compose down -v                    # всё убрать (out/ остаётся)
```

`enroll.sh` ставит отладочный `sample` из `wolp-device-trust` (`./gradlew :sample:assembleDebug`) и запускает `StandEnrollActivity`: настоящий `DeviceTrust.enroll` (precheck → `enroll/start` → ключ в TEE с аттестацией на nonce шлюза → CSR → `enroll/finish`). Результат — строка `RESULT …` в logcat (`WOLP_STAND`), затем реестр устройств и журнал событий.

| Что | Как на стенде |
|---|---|
| Доступ телефона | `adb reverse tcp:9443 tcp:9443`, URL `https://localhost:9443` (8443 часто занят) — без root и без DNS |
| TLS | серверный сертификат от `pki/stand-root` — тестовый корень с **name constraints** (только `localhost`, `127.0.0.1`); debug-сборка `sample` доверяет ему только для этих адресов (NSC) и закрепляет ключ сервера + запасной (`out/backup.key`) |
| Сессия | `enroll.sh` входит в Kratos браузерным потоком (`curl`) и передаёт cookie `ory_kratos_session` в приложение |
| Приложение | `DEVICES_APP_PACKAGE=org.wolpmessenger.devicetrust.sample`, дайджест — debug-ключ (`APP_DIGEST`) |
| Список отзыва | настоящий `android.googleapis.com/attestation/status` |
| Порты хоста | `9443` nginx, `18091` admin API и `/readyz` модуля, `4433/4434` Kratos, `55433` PostgreSQL |

Телефон Samsung: выключить **Ajustes → Seguridad y privacidad → Bloqueador automático** (иначе «Depuración USB» не включается), режим разработчика — 7 нажатий на «Número de compilación».

## Результат 2026-11-09, Galaxy A37 (SM-A376B, Android 16, TEE)

- `RESULT OK`, `state Active(notAfter = +72 ч, renewAfter = +24 ч)`; сертификат выдан `CN=Wolp Device CA 2026`.
- Реестр: `active`, `TEE`, `rkp`, `osVersion 160000`, `patchLevel 202608`, `appVersionCode 10300`; события `enroll_started` → `enrolled`.
- Повторная регистрация того же пользователя → новое устройство, прежнее `revoked` / `replaced` (D15), событие `device_replaced`.
- Метрики: `attestation_chain_total{rkp,tee} 2`, `sessions_revoked_total 4` (прочие сессии Kratos завершены, текущая сохранена).
- Первая попытка: `EPERM` на сокете — у `sample` не было `INTERNET` (добавлено в debug-манифест).
