# Lokaler Story-Vergleich vom 24. November 2026

**Der lokale Vergleich ist bestanden:** Beide Varianten veröffentlichten alle drei Intervalle
innerhalb von fünf Minuten, ohne wachsenden Rückstand oder API-Fehler. GEM-122 reduzierte
die Engine-Laufzeit um 74,1 bis 77,0 Prozent und die Veröffentlichung um 97,5 bis 98,5 Prozent.
Eingaben, fachliche Ergebnisse und Kandidatenzahlen stimmen überein; Wiederholungen blieben
idempotent. Dieser kapazitätsbegrenzte lokale Vergleich ist **kein Nachweis des
Fünf-Minuten-Budgets auf dem VPS**. Die Integration steht noch aus.

## Freigabe und Datengrundlage

Nach dem [abgebrochenen VPS-Versuch](story-incremental-vps-2026-11-24.md) hat der Nutzer die
vollständige lokale Übernahme der vorhandenen Sicherung und den isolierten lokalen Vergleich
ausdrücklich freigegeben. Auf dem VPS erfolgte dafür nur die begrenzte Übertragung der
vorhandenen Datei; weitere Lastläufe gegen die Produktion gehören nicht zu diesem Vergleich.

Übertragen wurde `gem121-before-20261124T125307Z.dump`, 1.200.724.369 Byte,
SHA-256 `73c738ae40a0136b55b4383a46a31c0b0c94cdece3efff0d60288babb302001b`.
Die lokale Prüfsumme wurde am 24. November 2026 um 21:04:31 UTC bestätigt.
`transfer.json` dokumentiert Freigabe, Größe und Prüfsumme ohne Zugangsdaten.

Der lokale Restore lässt die drei Rohpayload-Tabellen und die Paarentscheidungshistorie aus.
Kanonische Artikel-, GKG-, Event-, Mention-, Story-Eingabe- und Vektordaten bleiben vorhanden.
Der gemeinsame Artikelpool enthält 71.228 Artikel. Die Einschränkung ist in beiden Varianten
gleich; damit wird kein vollständiger Produktionsbestand einschließlich seiner Speicher- und
Historienkosten nachgebildet.

Die Sicherung, Datenbankkopien, Zugangsdaten, JARs und privaten Messartefakte liegen unter dem
ignorierten Verzeichnis `engine/target/gem122-local-benchmark`. Sie werden nicht eingecheckt.
Dieser Bericht enthält keine Artikeltexte, Rohdaten, Vektoren oder Geheimnisse.

## Verglichener Code und Provenienz

Die Baseline ist die tatsächlich deployte Version
`7e6eef62ef10677e613aad0d0087d28c5b09ceac`, ergänzt um dieselbe Phasenmessung und
Replay-Steuerung wie die Nachher-Variante. Zum Zeitpunkt der Messung war der lokale
Arbeitscheckout älter und enthielt uncommittierte Änderungen. Die Messung bezieht sich auf
separat extrahierte und gebaute Quellen. Die spätere lokale Übernahme ist unten dokumentiert.

Die Nachher-Variante portiert die betroffenen Story-Gruppen und die begrenzte Veröffentlichung
auf diese deployte Basis. Sie behält deren GEM-121-Suche, Vektordekodierung, Batch-Inserts und
Kompaktierungsregeln. Sie ergänzt die V39-Quelländerungswarteschlange und den Index für
aktuelle Artikelmitgliedschaften. Unveränderte Gruppen werden bei regulären Läufen nicht
erneut veröffentlicht; zeitabhängige Statusübergänge und Quelländerungen bleiben berücksichtigt.

Für beide lokalen Varianten wurde ausschließlich im Testaufbau derselbe Startup-Overlay
angewandt: `StoryLocationBackfill` erhält `@Order(-100)`. Der vollständige Orts-Backfill läuft
damit vor dem blockierenden Benchmark-Runner. Dieser Overlay wird separat dokumentiert und
ändert die gemessenen Story-Regeln nicht. Die früheren VPS-JARs und deren Provenienz bleiben
als separate Artefakte erhalten.

| Lokales Artefakt | SHA-256 |
| --- | --- |
| Baseline-JAR | `b0d8e30449a6ddde387aff338075f8cf648d8dedf37bfe1b3406576b36a169f5` |
| Nachher-JAR | `8d5d334511f53b766096edcddfddd55f8708645715e37c055746668a337deb82` |

`local-artifacts.json`, `local-source-manifest.json` und
`local-startup-order-overlay.patch` unter dem privaten Messverzeichnis identifizieren die
lokalen Binärdateien, Quellen und den gemeinsamen Overlay. Portierungs- und
Instrumentierungspatches der zugrunde liegenden Varianten liegen unter
`engine/target/gem122-benchmark`.

## Lokale Ressourcen und Laufzeit

| Bestandteil | Verifizierter Aufbau |
| --- | --- |
| Host-CPU | AMD Ryzen 9 9900X, 12 Kerne / 24 logische Prozessoren; gemeldeter Maximalwert 4.400 MHz |
| Docker | Docker Desktop 29.9.1, Linux x86-64, WSL2-Kernel 6.6.87.2, cgroups v2 |
| Docker-VM | 24 sichtbare CPUs, 16.322.293.760 Byte sichtbarer Arbeitsspeicher |
| Java | 21.0.12.1 |
| PostgreSQL | 17.11, Debian 17.11-1.pgdg12+2 |
| Engine-Container | 1 CPU, 2 GiB RAM, kein zusätzlicher Containerswap |
| JVM | 1 GiB maximaler Heap, ein aktiver Prozessor, 96 MiB maximales Direct Memory |
| PostgreSQL-Container | 1 CPU, 2 GiB RAM, kein zusätzlicher Containerswap |
| Separater API-Probe-Container | 0,25 CPU, 128 MiB RAM, 64 MiB maximaler Heap, kein zusätzlicher Containerswap |

Engine und PostgreSQL erhalten zusammen 2 CPU- und 4-GiB-Containerlimits. Die Probe benötigt
zusätzlich 0,25 CPU und 128 MiB; Betriebssystem, Docker/WSL und Hostprozesse liegen außerhalb
dieser Limits. Die Varianten laufen nacheinander auf derselben lokalen PostgreSQL-Instanz.
`environment.json` und die jeweiligen Runner-Nachweise halten Umgebung, tatsächliche Limits,
Image-Identitäten und PostgreSQL-Einstellungen fest.

Der VPS hat nur 3.911 MiB nutzbaren RAM, keinen Swap und eine andere CPU (Xeon Cascadelake).
Die lokalen Containerlimits passen daher nicht gemeinsam samt Betriebssystemreserve auf
diesen VPS. CPU-Quoten gleichen außerdem weder die Leistung pro Kern noch Storage,
Virtualisierung oder CPU-Steal an. Lokale Laufzeiten dürfen nicht auf ein VPS-SLA hochgerechnet
werden.

## Gemeinsamer Ausgangsstand und Replay

Der Replay-Katalog `gem122_replay_inputs` friert IDs, Referenzen, Erfassungszeiten und
Embedding-Zuordnungen einmalig vor der Vorbereitung ein. Für 07:55 UTC werden passende
historische Eingaben ausgewählt. Snapshot 79 dient als unveränderliche Fallback-Basis für
22 bereits veröffentlichte Referenzen, deren `created_at` durch spätere Revisit-Vorgänge
verschoben worden war. Veröffentlichte Referenzen werden dadurch nicht versehentlich aus
dem Ausgangsstand entfernt.

Dieser Ausgangsstand wird einmal mit der Nachher-Variante erfolgreich veröffentlicht.
Danach wird die Anwendung beendet und der gemeinsame Datenbankstand in beide Varianten
geklont. Die Klone übernehmen dieselben Eingaben, Mitgliedschaften, Story-Identitäten,
Sequenzen und Historien. Der vorbereitete Bestand enthält 51.589 aktuelle Eingaben,
davon 51.438 mit fertigem Embedding. Nicht jede aktuelle Eingabe ist ein eingeschlossenes
Snapshot-Mitglied; der lokale Kontrollsnapshot umfasst 51.587 Mitglieder.

Beide Klone enthalten damit V39 einschließlich Index und Quelländerungswarteschlange.
Die Baseline-JAR selbst enthält V39 nicht. Die Messung vergleicht den ursprünglichen
Veröffentlichungscode und den optimierten Code auf demselben vorbereiteten Schema; sie ist
kein Vergleich zweier unveränderter nativer Installationen.

Jede Variante verarbeitet zunächst den neuen Kontroll-Wasserstand
`2026-11-24T07:55:00.000001Z`. Damit wird ein eigener No-op-Lauf ausgeführt statt die bestehende
T0-Veröffentlichung lediglich wiederzuverwenden. Dieser Kontrolllauf gehört nicht zu den drei
gemessenen Importintervallen.

Anschließend werden die Ready-Input-Zustände für 08:00, 08:05 und 08:10 UTC unabhängig vom
Bearbeitungsfortschritt im Abstand von 300 Sekunden freigegeben. Es handelt sich um ein
rekonstruiertes Ready-Input-Replay mit eingefrorenen Daten, nicht um einen Live-Durchlauf des
GDELT-Imports und des Embedding-Dienstes. Mutable `created_at`-Felder belegen für zuvor
unveröffentlichte Referenzen nicht zuverlässig den ursprünglichen historischen Ankunftszeitpunkt.

Alle regulären Import-, Embedding-, Retention- und Snapshot-Scheduler sind abgeschaltet.
Pro Variante bedient dieselbe JVM die Test-API und den Story-Lauf. Die separate Probe fragt
Artikel- und Story-Listen mit jeweils 20 Einträgen ab. SQL-Replay-Vorbereitung wird getrennt von
der reinen Engine-Laufzeit ausgeführt; die Veröffentlichungslatenz ab Freigabe berücksichtigt
die zusätzliche Verzögerung.

Der [lokale Runner](../../scripts/gem122-benchmark/run_local.py) akzeptiert nur lokale
Windows-Docker-Pipes und fest benannte lokale Testdatenbanken. Die
[Verifikation](../../scripts/gem122-benchmark/verify_local.py) prüft zusätzlich Laufstatus,
Umgebung, Ressourcenlimits, Phasenmessungen und Eingabe-/Ausgabeprüfsummen.

## Ergebnisse der drei Intervalle

Alle Laufzeiten in der folgenden Tabelle sind Sekunden. Geänderte Artikel entsprechen dem
persistierten `changed_article_count`; Kandidaten sind die exakten Vergleiche der
inkrementellen Kandidatensuche.

| Fenster UTC | Variante | Geänderte Artikel | Kandidaten | Laden | Vergleich/Partition | Veröffentlichung | Engine gesamt |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 08:00 | Vorher | 988 | 50.289.652 | 7,455 | 38,916 | 161,827 | 208,201 |
| 08:00 | Nachher | 988 | 50.289.652 | 6,665 | 37,105 | 4,036 | 47,808 |
| 08:05 | Vorher | 996 | 51.361.123 | 6,737 | 39,104 | 142,277 | 188,120 |
| 08:05 | Nachher | 996 | 51.361.123 | 6,450 | 37,297 | 2,111 | 45,860 |
| 08:10 | Vorher | 995 | 52.372.358 | 8,563 | 38,765 | 120,229 | 167,559 |
| 08:10 | Nachher | 995 | 52.372.358 | 6,242 | 35,208 | 1,904 | 43,357 |

Die Vergleichsarbeit bleibt weitgehend gleich. Der wesentliche Gewinn liegt in der
Veröffentlichung betroffener Stories: Sie sinkt je Fenster um 97,5, 98,5 und 98,4 Prozent.
Die gesamte Engine-Laufzeit sinkt um 77,0, 75,6 und 74,1 Prozent.

| Fenster UTC | Vorher: ab Freigabe veröffentlicht | Nachher: ab Freigabe veröffentlicht | Budget |
| --- | ---: | ---: | --- |
| 08:00 | 208,675 s | 48,440 s | beide unter 300 s |
| 08:05 | 188,515 s | 46,286 s | beide unter 300 s |
| 08:10 | 168,995 s | 43,759 s | beide unter 300 s |

An jedem der drei Freigabetermine war in beiden Varianten kein früheres Intervall mehr
offen. Der Rückstand wuchs nicht. Alle sechs regulären Läufe wählten genau eine Version
aus und veröffentlichten sie erfolgreich; keiner wurde wiederverwendet oder schlug fehl.

### API-Verfügbarkeit während des gesamten Messfensters

Das Messfenster reicht je Variante vom Beginn des ersten bis zum Ende des dritten
Intervalls und schließt die Wartezeiten zwischen den Freigaben ein. Die unterschiedlichen
Abfragezahlen entstehen durch die unterschiedlich langen Messfenster. Alle Latenzen sind
Millisekunden; p50 und p95 beziehen sich auf erfolgreiche Antworten.

| Variante | API | Abfragen | Fehler | p50 | p95 | Maximum |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Vorher | Artikel | 768 | 0 | 5,076 | 7,042 | 98,349 |
| Vorher | Stories | 768 | 0 | 38,632 | 53,875 | 209,025 |
| Nachher | Artikel | 643 | 0 | 5,130 | 6,910 | 63,023 |
| Nachher | Stories | 643 | 0 | 35,616 | 45,644 | 134,544 |

Insgesamt wurden 2.822 API-Abfragen ohne Fehler erfasst. Engine und Probe beendeten sich
bei beiden Varianten mit Exit-Code 0; die abschließend erfassten Containerzustände melden
keinen OOM-Kill.

## Gleichheit der eingefrorenen Eingaben und Ergebnisse

Kontrollzustand 0 und alle drei regulären Intervalle haben in beiden Varianten identische
`snapshot_input_hash`-Werte sowie identische Prüfsummen der unveränderlichen
Snapshot-Mitglieder, aktuellen Mitgliedschaften, Story-Identitäten/Repräsentanten und Orte.
Die Kandidatenzahlen stimmen je Intervall ebenfalls überein. Doppelte aktuelle
Mitgliedschaften wurden weder im Kontrollzustand noch in den drei Intervallen gefunden.
Laufzeitabhängige Audit-Zeitstempel werden nicht als fachliche Gleichheit verglichen.

Nach dem letzten Intervall enthalten beide Varianten 54.584 Snapshot-Mitglieder,
54.417 aktuelle Mitgliedschaften und 25.527 Stories. Die automatische Prüfung in
`verification.json` akzeptiert Korrektheit und lokale Performance ohne Fehler oder Warnungen.

## Wiederholung und Idempotenz

Der bereits veröffentlichte letzte Zustand wurde in beiden Varianten erneut verarbeitet.
Beide Wiederholungen meldeten `selected=1`, `succeeded=0`, `reused=1`, `failed=0`.
Die Zahl der Veröffentlichungs-Commits blieb jeweils bei 84. Eingabe-Hash,
Snapshot-Mitglieder und alle fachlichen Ausgabeprüfsummen blieben unverändert;
doppelte aktuelle Mitgliedschaften gab es nicht. Beide Wiederholungsprozesse endeten mit
Exit-Code 0 ohne OOM-Kill. `retry-results.json` bewertet beide Prüfungen als bestanden.

## Grenzen und verbleibender Nachweis

Die lokale Kopie verarbeitet vorhandene Embeddings und historische Eingabeänderungen.
Sie misst weder die Kosten neuer OpenAI-Aufrufe noch den laufenden GDELT-Import, dessen
Parallelität mit Story-Veröffentlichungen oder zusätzlichen Schreibaufwand der
Invalidierungstrigger unter Live-Importlast. Wiederherstellung und V39-/Index-Aufbau gehören
zur Vorbereitung, nicht zum regulären Intervallbudget.

Dateisystemcache, JVM-Warmup und lokale Hostlast können die Laufzeiten beeinflussen. Der
gemeinsame Kontrolllauf begrenzt Anlaufunterschiede, wärmt aber nicht automatisch alle Pfade
für geänderte Artikel gleich auf. Beide Varianten wurden in einer festen Reihenfolge und
jeweils einmal über drei Intervalle gemessen; die Ergebnisse sind keine Verteilung mehrerer
unabhängiger Wiederholungen.

[Aggregierte Ergebnisse und Provenienz](story-incremental-local-2026-11-24.json) dokumentieren
den bestandenen lokalen Vergleich einschließlich Gleichheit und Idempotenz. Der Nachweis
unter tatsächlich geeigneten VPS-Ressourcen und der Merge in `main` stehen weiterhin aus.
Der aktuelle Rückstau und ein Produktions-Reset sind nicht Teil von GEM-122.

Nach der Verifikation wurden die Mess- und Wiederholungs-Engine-Container, die API-Probes
und der lokale PostgreSQL-Container sauber beendet: Exit-Code 0, kein OOM-Kill. Es läuft
kein `gem122-local`-Testcontainer mehr. Eine frühere fehlgeschlagene Startup-Probe gehört
nicht zur akzeptierten Messung. Die vollständige lokale Sicherung, das Datenvolume und die
Fixtures bleiben im privaten, ignorierten Testbestand erhalten; sie wurden nicht gelöscht.

## Übernahme auf die aktuelle Codebasis

Am 24. November wurde der Arbeitscheckout auf dem lokalen Branch `codex/gem-122` auf die
aktuelle `origin/main`-Basis `6beefb3fb8506d6c36eeb6472a29d915224019f9` gebracht. Deren
Engine-Code entspricht der gemessenen deployten Basis; Unterschiede betreffen Deployment
und CI. Der vorherige Arbeitsstand wurde unter `target/gem122-integration-backup` gesichert.

Die getestete Gruppenverarbeitung, Veröffentlichung, Ortsaufbereitung, Phasenmessung und
V39-Migration sind übernommen. GEM-121s Vektor-, Such- und Retention-Implementierung bleibt
erhalten. Die vorhandene zusätzliche Event-Evidence-Regel samt Migration V38 und Tests
wurde mit der verzögerten Vektornutzung zusammengeführt. Der ältere, separat entwickelte
`StoryIncrementalScope`-Pfad wurde durch die getestete Implementierung ersetzt.

`mvn verify` gegen eine neue wegwerfbare lokale PostgreSQL-17.11-Datenbank war erfolgreich:
161 Unit-Prüfungen, davon zwei übersprungen, und 70 Integrationstests ohne Fehler. Die beiden
ausgelassenen Prüfungen benötigen den extern bereitgestellten eingefrorenen Korpus
(`art037.inputs`) beziehungsweise den expliziten Großlastschalter (`stories.load-test`).
Die Großlastprüfung wurde für diese Integration nicht zusätzlich gestartet.

Das gebaute JAR wurde anschließend mit festem Wasserstand gegen die leere Testdatenbank
gestartet: erste Veröffentlichung `selected=1, succeeded=1, reused=0, failed=0`, identische
Wiederholung `selected=1, succeeded=0, reused=1, failed=0`; beide Prozesse endeten mit Exit 0.
Dieser Startcheck ersetzt nicht den oben dokumentierten Lastvergleich.

Eine unabhängige Codeprüfung bestätigt die Übernahme der getesteten Kernimplementierung
und den Erhalt der fremden Änderungen. Das aktuelle JAR hat SHA-256
`a3d02689127022bf63d1a3b07edee38b508a5ca2fdb3e433a62b3f1030e728cd`.
Testzahlen, ausgelassene Prüfungen, Quellprüfsummen und Startcheck dieses zunächst
uncommittierten Integrationsstands stehen im Abschnitt `integration` der aggregierten
JSON-Datei. Der folgende Freigabestand für `main` enthält ausschließlich GEM-122; die
anderen vorhandenen Arbeitsänderungen einschließlich Event Evidence und V38 bleiben
außerhalb dieses Commits. Aktueller Merge- und Deployment-Nachweis werden in GEM-122 geführt.

Der davon getrennte Freigabestand für `main` wurde anschließend mit `mvn clean verify`
gegen eine weitere frische lokale PostgreSQL-Datenbank geprüft: 153 Unit-Prüfungen
(151 ausgeführt, zwei optionale ausgelassen) und 70 Integrationstests ohne Fehler.
Die Windows-Prüfung lud den Mockito-Testagenten beim JVM-Start, weil das nachträgliche
Anhängen über Named Pipes blockiert war. Am Anwendungscode war dafür keine Änderung nötig.
Seine Evidenz steht unter `mainCandidate` in der aggregierten JSON-Datei.
