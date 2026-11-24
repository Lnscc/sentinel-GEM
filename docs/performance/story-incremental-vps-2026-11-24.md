# VPS-Lastnachweis vom 24. November 2026

**Ergebnis: Fünf-Minuten-Budget und API-Verfügbarkeit sind nicht nachgewiesen.** Der gemeinsame Lasttest wurde nach OOM-Neustarts der Produktions-Engine abgebrochen. Ein reguläres Nachher-Intervall wurde veröffentlicht; das zweite blieb unvollständig, das dritte wurde nicht verarbeitet. Der Vorher-Lauf wurde aus Sicherheitsgründen abgesagt.

## Aufbau und Eingaben

- Tatsächlicher VPS: 2 vCPU, Intel Xeon Cascadelake, 3.911 MiB RAM, kein Swap. PostgreSQL und die bestehende Produktions-Engine liefen weiter.
- Isolierte Datenbankkopien im selben PostgreSQL-Container; der Schreibbestand war getrennt, CPU, RAM und I/O blieben geteilt.
- Test-JVM: 1 CPU, Docker-RAM-Limit 1 GiB, Java-Heap 700 MiB, ein aktiver Prozessor. Die Produktions-Engine hatte kein Docker-RAM-Limit. Diese gemeinsame Konfiguration ist die gemessene Umgebung; ein exklusiver VPS-Nachweis steht aus.
- Tatsächlich deployte Basis: `7e6eef62ef10677e613aad0d0087d28c5b09ceac`. Der lokale Checkout war älter. Die Testvariante wurde auf die deployte Basis portiert und behielt deren GEM-121-Suche, Vektordekodierung, Batch-Inserts und Kompaktierungsregeln.
- Vorhandene VPS-Sicherung `gem121-before-20261124T125307Z.dump`, SHA-256 `73c738ae40a0136b55b4383a46a31c0b0c94cdece3efff0d60288babb302001b`. Daten blieben auf dem VPS. Paarhistorie und drei Rohpayload-Tabellen wurden beim Wiederherstellen ausgelassen; kanonische Artikel-, GKG-, Event-, Mention-, Eingabe- und Vektordaten blieben vorhanden.
- Eingefrorener Rohdatenpool mit 71.228 Artikeln; darauf wurden reale historische Ready-Input-Änderungen für 08:00, 08:05 und 08:10 UTC abgespielt. Das ist ein Ready-Input-Replay, kein vollständiger Live-GDELT-/OpenAI-Durchlauf.
- 22 veröffentlichte Referenzen benötigten die unveränderliche Snapshot-Basis 79 als Fallback, weil Revisit-Vorgänge `created_at` verändert hatten. Vor dem Test fehlte keine veröffentlichte Referenz mehr.
- Ein gemeinsamer Ausgangsstand wurde vorab veröffentlicht und identisch geklont. Danach bediente jeweils eine JVM die Test-API und den Story-Lauf. Alle Import-, Embedding-, Retention- und sonstigen Scheduler waren in dieser JVM abgeschaltet.
- Neue Intervalle wurden unabhängig vom Bearbeitungsfortschritt alle 300 Sekunden freigegeben. SQL-Replay-Vorbereitung liegt außerhalb der reinen Engine-Laufzeit; die zusätzliche Verzögerung ist in der Veröffentlichungslatenz ab Freigabe enthalten.

## Reguläre Intervalle

| Eingabefenster UTC | Geänderte Artikel | Vergleiche | Laden | Vergleich/Partition | Veröffentlichung | Engine gesamt | Ab Freigabe veröffentlicht |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 08:00 | 988 | 50.289.652 | 124,206 s | 190,742 s | 31,935 s | **346,898 s** | **350,442 s** |
| 08:05 | unvollständig | – | – | – | – | abgebrochen | keine Veröffentlichung |
| 08:10 | nicht verarbeitet | – | – | – | – | abgesagt | keine Veröffentlichung |

Das erste Intervall lief tatsächlich von 21:20:24 bis 21:26:11 Uhr Europe/Berlin. Seine Freigabe war um 21:20:21 Uhr. Beim nächsten Freigabetermin war dieses Intervall noch offen. Das Budget wurde bereits im ersten Fenster überschritten; ein dauerhafter Abbau des Rückstands ist mit dem abgebrochenen Versuch nicht belegt.

Nach dem veröffentlichten Fenster: 52.587 Snapshot-Eingaben, 52.426 fertige Eingaben und aktuelle Mitgliedschaften, 24.572 Stories, keine doppelten aktuellen Mitgliedschaften. Prüfsummen für Mitgliedschaften, Story-Identitäten/Repräsentanten und Orte wurden erfasst. Ohne ausgeführten Vorher-Lauf ist deren Gleichheit mit der aktiven Implementierung **nicht verifiziert**.

## API-Latenzen

Die p95-Werte beziehen sich auf erfolgreiche Antworten. Fehler werden separat gezählt. Gemessen wurden Listen mit jeweils 20 Artikeln beziehungsweise Stories. Der konfigurierte Socket-Timeout betrug 5 Sekunden; er ist kein harter Gesamtzeitdeckel über mehrere Antwort-Lesevorgänge.

| Messbereich | Artikel p95 | Stories p95 | Fehlgeschlagene Abfragen Artikel / Stories |
| --- | ---: | ---: | ---: |
| Erstes abgeschlossenes Fenster, Test-API direkt | 167 ms | 575 ms | 0 / 0, bei je 344 Abfragen |
| Gesamter Messversuch, Test-API direkt | 560 ms | 1.275 ms | 12 / 11, bei 743 / 714 Abfragen |
| Gesamter Messversuch, Produktion über internen Monitor | 851 ms | 1.547 ms | 73 / 73, bei 384 / 380 Abfragen |
| Gesamter Messversuch, öffentliche HTTPS-API | 1.375 ms | 3.141 ms | 49 / 52, bei 175 / 174 Abfragen |

Die Test-API erreichte bei einzelnen erfolgreichen Antworten bis zu 13,4 Sekunden für Artikel und 18,7 Sekunden für Stories. Während der Speicherstörung lieferte die öffentliche Produktion HTTP 502. Die geforderte durchgängige API-Erreichbarkeit ist damit verfehlt.

## Abbruch, Erholung und Bereinigung

Docker-Ereignisse bestätigen mindestens vier OOM-Kills der Produktions-Engine zwischen 21:31 und 21:35 Uhr Europe/Berlin; das Startlog zeigt weitere Neustarts. Die ursprüngliche Reserveprüfung mit 150 MiB verhinderte diese Störung nicht. Der parallele Aufbau war für diesen bereits ausgelasteten 4-GiB-Host zu knapp bemessen.

Die Zusatzlast wurde um 21:35 Uhr beendet und der noch nicht gestartete Vorher-Lauf abgesagt. Artikel- und Story-API waren um 21:36:25 Uhr wieder mit HTTP 200 bestätigt. In der anschließenden öffentlichen Erholungsstichprobe gab es je 94 weitere Abfragen ohne Fehler (p95: Artikel 344 ms, Stories 579 ms). Der Produktions-Restartzähler blieb bei 6; Image und Konfiguration wurden nicht geändert.

Alle drei angelegten Testdatenbanken, beide Testcontainer und temporären Zugangsdatenkopien wurden entfernt. Die produktive Datenbank und die vorhandene Sicherung bleiben erhalten. Die letzte Prüfung bestätigte beide APIs mit HTTP 200, etwa 1 GiB verfügbaren Arbeitsspeicher und 54 GiB freien Root-Speicher.

Der Benchmark-Runner verweigert inzwischen den Start neben der bekannten laufenden Produktions-Engine und verlangt mindestens 512 MiB Hostreserve. Ein weiterer Nachweis braucht eine dedizierte Umgebung; dieser gemeinsame Produktionsversuch darf nicht wiederholt werden.

## Im Versuch behobene Fehler

- Bei einem wartenden Ersatz-Embedding bleiben Metadaten und bisherige Mitgliedschaft betroffener Bestandsmitglieder erhalten. Ein PostgreSQL-Regressionstest deckt die Erweiterung durch einen fertigen Nachbarartikel ab.
- Die neue globale Snapshot-Vollständigkeitsprüfung geriet bei frischen Snapshots in einen quadratischen `Nested Loop Anti Join`, weil PostgreSQL eine statt tatsächlich 51.587 Snapshot-Zeilen schätzte. Sie verwendet jetzt `EXCEPT`. Der Ersatz prüfte die realen Mengen in 2,477 Sekunden und erkannte eine absichtlich ausgelassene Referenz weiterhin. Der langsame vorbereitende Lauf ist von den regulären Messintervallen ausgeschlossen.
- Auf der deployten Testbasis bestanden 13 Such-/Partitionstests; der zusätzliche Pending-Member-PostgreSQL-Test bestand. Der mengengestützte Guard wurde auf der realen Kopie positiv und negativ geprüft und unabhängig semantisch begutachtet.

## Artefakte und offene Nachweise

[Aggregierte Messwerte und Prüfsummen](story-incremental-vps-2026-11-24.json) enthalten Laufzeiten, Verfügbarkeit, Provenienz und den Abbruchstatus. Reine Request-Messdaten und gefilterte Zeitlogs liegen lokal unter `engine/target/gem122-benchmark/metrics` sowie im privaten VPS-Messordner. Es wurden keine Datenbank-, Artikel-, Roh- oder Embedding-Inhalte auf den lokalen Rechner übertragen.

Die gebauten JARs sind durch SHA-256 identifiziert: Baseline `7e8765e9f9d375a083e257c1203c444f47044817bd1acad0d490cb07d7df5533`, Nachher `cb98c6749869a987076dc22ff1bae8705d2970c0643d1165f0c8e34a694220c1`. Source-Manifest und Portierungs-/Instrumentierungspatches liegen unter `engine/target/gem122-benchmark`; die Messung bezieht sich auf diese portierte deployte Basis.

Zum Ende dieses Versuchs blieben drei abgeschlossene Intervalle innerhalb des Budgets, ein gematchter Vorher-/Nachher-Vergleich einschließlich Story-Identitäten/Repräsentanten, ein exklusiver Ressourcen-Nachweis und die Integration offen. Die automatische Freigabeprüfung lehnte die zunächst nicht ausdrücklich autorisierte vollständige lokale Übernahme der privaten Sicherung ab. Der Nutzer hat diesen Transfer anschließend ausdrücklich freigegeben und weitere Lasttests neben laufender Produktion untersagt. Die Sicherung wurde lokal vollständig und mit identischer SHA-256 übernommen. Der [isolierte lokale Folgevergleich](story-incremental-local-2026-11-24.md) dokumentiert seine eigene Umgebung und Ergebnisse; er ersetzt keinen Kapazitätsnachweis auf dem VPS.
