# Архитектура Jacket

## Слои приложения

- `data/JackettClient.kt` — HTTP-клиент Torznab, XML parsing, ограничение ответов и проверки URL.
- `data/SecureSettings.kt` — сохранение адреса Jackett и API-ключа с AES/GCM и Android Keystore.
- `data/TorrentEntry.kt` — модель результата и форматирование размера.
- `torrent/TorrentQueueStore.kt` — ограниченная локальная очередь magnet-задач и состояние паузы.
- `torrent/TorrentEngine.kt` — единая libtorrent4j SessionManager; наружу выдаются только snapshot-модели.
- `torrent/TorrentDownloadService.kt` — Android foreground service, команда управления, уведомление и периодическое обновление состояния.
- `ui/` — Jetpack Compose и ViewModel; UI не вызывает JNI и сетевые запросы напрямую.
- `MainActivity.kt` — точка входа и обработка внешних torrent-ссылок.

## Поток поиска

1. ViewModel проверяет конфигурацию и передаёт запрос на IO dispatcher.
2. JackettClient строит Torznab URL с API key и query.
3. Клиент запрещает HTTP для публичных адресов, redirect-ответы, слишком большие тела и XML DOCTYPE.
4. Парсер преобразует RSS item в TorrentEntry, извлекает magnet URL, размер, сиды, пиры и indexer.
5. Compose отображает отсортированный список; magnet отправляется в TorrentDownloadService.

## Поток загрузки

1. UI передаёт magnet в foreground service.
2. Service выполняет операции SessionManager на IO dispatcher.
3. TorrentEngine запускает DHT и добавляет magnet в локальную очередь.
4. Снимки прогресса и скоростей читаются из libtorrent и передаются в StateFlow.
5. Удаление задачи удаляет torrent из session и очереди, но не удаляет уже загруженные файлы.

## Персистентность и ограничения

Очередь в текущей версии сохраняет magnet URI, title и paused IDs. Полноценное сохранение libtorrent resume-data ещё не реализовано. При восстановлении native-сессии libtorrent заново загружает magnet metadata и проверяет файлы на диске. Следующий этап — сериализация resume data через SaveResumeDataAlert и атомарное сохранение файлов в app-private storage.

## Android lifecycle

Service использует тип foreground service `dataSync`. На Android 15+ для него действует суммарный временной лимит 6 часов за 24 часа в фоне. Когда система вызывает onTimeout, сервис останавливает native session и оставляет очередь для следующего запуска. Для длительных передач нужно отдельно спроектировать переход на User-Initiated Data Transfer Job и UX запуска пользователем; пока такой переход не реализован.

## ABI

Подключены native зависимости libtorrent4j для ARMv7, ARM64, x86 и x86_64. ABI packaging должен быть подтверждён release/debug сборкой CI.
