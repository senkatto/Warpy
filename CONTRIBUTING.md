# Участие в разработке Warpy

Спасибо за помощь в развитии Warpy. Проект содержит VPN-клиенты для Android и Windows,
поэтому изменения сетевой логики требуют особой осторожности: ошибка в маршрутизации
может оставить устройство без доступа к интернету.

## Перед созданием pull request

- Не добавляйте реальные VPN-ссылки, пароли, UUID, приватные ключи, скриншоты с данными
  профилей, хранилища ключей и секреты подписи.
- Делайте изменения небольшими и описывайте, на какое пользовательское поведение они влияют.
- Добавляйте или обновляйте тесты для парсеров и генерируемой конфигурации sing-box.
- Выполните те же проверки, которые запускаются в CI:

```powershell
.\gradlew.bat :app:checkMojibakeText :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
npm test --prefix desktop-tauri
cargo fmt --manifest-path desktop-tauri\src-tauri\Cargo.toml -- --check
cargo clippy --manifest-path desktop-tauri\src-tauri\Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path desktop-tauri\src-tauri\Cargo.toml --all-targets
```

Для подписанной сборки настройте четыре переменные `RELEASE_*` и выполните
`gradlew.bat :app:assembleProduction`.

## Pull request

Укажите устройство и версию Android, использованный протокол профиля и тип сети:
Wi-Fi или мобильный интернет. Перед публикацией журналов и скриншотов удалите из них
все данные профилей.

---

# Contributing to Warpy

Thank you for helping improve Warpy. The project contains Android and Windows
VPN clients; network changes need extra care because a small routing mistake can
leave a device without internet access.

## Before opening a pull request

- Do not include real VPN links, passwords, UUIDs, private keys, screenshots
  with profile data, keystores, or signing secrets.
- Keep changes focused and explain the user-visible behavior they affect.
- Add or update unit tests for parsers and generated sing-box configuration.
- Run the same checks as CI:

```powershell
.\gradlew.bat :app:checkMojibakeText :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
npm test --prefix desktop-tauri
cargo fmt --manifest-path desktop-tauri\src-tauri\Cargo.toml -- --check
cargo clippy --manifest-path desktop-tauri\src-tauri\Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path desktop-tauri\src-tauri\Cargo.toml --all-targets
```

For a signed release build, configure the four `RELEASE_*` variables and run
`gradlew.bat :app:assembleProduction`.

## Pull requests

Include the device/API level used for manual VPN testing, the profile protocol
used for the test, and whether the test was performed on Wi-Fi or mobile data.
Redact all profile credentials from logs and screenshots.
