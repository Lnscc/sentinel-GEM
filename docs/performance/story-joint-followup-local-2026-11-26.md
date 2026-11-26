# GEM-124: ergänzende Abnahme vom 26. November 2026

Dieser Bericht hält den Prüfstand vor der ausdrücklichen Freigabe der Docker-Abnahme
fest. Der danach geänderte Ticketvertrag und die abschließende Bewertung stehen in
der [verbindlichen Docker-Abnahme](story-docker-acceptance-2026-11-26.md).

**Keine vollständige Gesamtabnahme: Der geforderte dedizierte Referenzhost fehlt.**
Die gemeinsame Fachregression, die tatsächliche Scheduler-Kette und ein
Speicherzyklustest wurden zusätzlich ausgeführt. Die Ergebnisse schließen mehrere
Lücken des ersten Docker-Berichts, ersetzen dessen offene Hardware-Abnahme aber nicht.

## Geprüfter Stand

- Remote-main: `ca443a170459f3f47ae1b142f1227dcbb68c0fc3`.
  Die Merge-Commits von GEM-125, GEM-126 und GEM-127 sind darin enthalten;
  dies wurde erneut mit `git merge-base --is-ancestor` geprüft.
- Lokaler kombinierter Worktree: `target/gem124-final-acceptance`, aus
  `d5a2c52028d1a6e0ebe38d5dc01f5e54b06f9791` (Projektionsfix, PR #23)
  und `f6b293fa4efc4497d009d5e4c5c9d485a323d527` (Harness, PR #22),
  ergänzt um die unten genannten Testquellen. Kein neuer Commit, Merge oder Rollout.
- Neue Tests: `StoryScheduledPipelinePostgresIT` und
  `StorySnapshotStorageCyclesPostgresIT`; beide erfordern ausdrückliches Opt-in.
- Eigene zufällige Datenbankschemas, synthetische Daten, lokale HTTP-Fixtures,
  keine Produktionslast oder externen Embedding-Aufrufe. Java 21 im Runner;
  die Testklassen sind mit `--release 21` kompiliert.

## Gemeinsame Fachregression

27 Unit-Tests und 56 PostgreSQL-Integrationstests bestanden. Bei den 56 ITs
gab es **null übersprungene, abgebrochene oder fehlgeschlagene Tests**.
Ein erster Windows-Lauf hatte die ITs wegen eines nicht erreichbaren veröffentlichten
Docker-Ports übersprungen und wurde ausdrücklich nicht als Nachweis verwendet.
Die tatsächliche Ausführung erfolgte anschließend direkt im internen Docker-Netz
über den vorhandenen JUnit-Platform-Launcher 6.0.3.

Ausgewählte IT-Klassen: `StorySnapshotServicePostgresIT`,
`StoryPromotionPostgresIT`, `StoryEmbeddingServicePostgresIT`,
`GdeltPipelinePostgresIT`, `StoryProjectionScopePostgresIT`.
Die Unit-Auswahl ergänzt Skalararithmetik/Tie-Breaks, Äquivalenz der exakten Suche,
Partitionierung, Kanonisierung, Retention, Ortsprojektion und den Embedding-Client.
Damit sind insbesondere historische Statistik, geschützte Evidenz, Backfill,
Reprocessing, Promotion, Retry, Lease/Fencing, stabile IDs und große Projektionsmengen
am kombinierten Stand abgedeckt. Sieben Python-Prüfungen des Runners bestanden ebenfalls.

## Tatsächliche Scheduler-Kette

Die fünf produktiven Scheduler liefen ohne direkte Aufrufe ihrer Dienste:
Manifest/Zips über HTTP → Import → Staging → Artikel-Extraktion → Embedding über
HTTP → Snapshot/Story-Veröffentlichung → öffentliche API.
Der tatsächliche Scheduler-Pool hatte einen Thread. Ein-Sekunden-Polls sind
ausdrücklich eine Beschleunigung für diesen **Funktionsnachweis**.

| Freigabe | Neue Artikel | Bis sichtbar, einschließlich API-Prüfung |
|---|---:|---:|
| 1 | 1.000 | 9.571 ms |
| 2 | 1.000 | 7.261 ms |
| 3 | 1.000 | 8.764 ms |

Alle neun Dateien wurden verarbeitet, alle 9.000 EVENTS-/MENTIONS-/GKG-Signale
ihren 3.000 Artikeln zugeordnet. Die Story des jeweils letzten neuen Artikels
wurde über ihre öffentliche ID abgerufen und enthielt dessen URL.
Artikel-, Story- und Statistik-API lieferten HTTP 200 innerhalb von fünf Sekunden.
Weitere Manifest-Polls ließen aktuelle Inputs, Artefakte und Zuordnungen unverändert:
30 Provider-Batches mit insgesamt genau 3.000 unterschiedlichen Titeln,
keine zusätzlichen Embedding-Eingaben, keine Verarbeitungsfehler oder Dubletten.

Ein kleiner Vorlauf mit 100 Artikeln je Freigabe bestand ebenfalls. Ein früher
Vorlauf scheiterte ausschließlich an einer Prüfabfrage auf die inzwischen entfernte
Tabelle `article_signals`; nach Korrektur werden die drei aktuellen Domänentabellen
einzeln geprüft. Auch dieser Fehlbericht bleibt erhalten.

Diese Läufe starten mit leerem Bestand und enden bei 3.000 Artikeln. Sie sind
**kein** gemeinsamer 107k-Leistungsnachweis mit realen Fünf-Minuten-Freigaben.
Provider-Antworten sind lokal deterministisch; Produktionsnetz-/Providerlatenz ist ungemessen.

## Speicherwiederverwendung und Folgewachstum

107.000 aktuelle und 107.000 unabhängige nicht aktuelle Eingaben,
214.000 unterschiedliche Artefaktschlüssel. Ein geschützter BACKFILL-Snapshot
und drei vollständige reguläre Snapshots bleiben erhalten: nach jeder Bereinigung
428.000 Mitgliedschaften. Zwei Aufwärmzyklen und sechs Folgezyklen schreiben
jeweils einen weiteren vollständigen Snapshot, verdichten und führen gewöhnliches
`VACUUM (ANALYZE, TRUNCATE FALSE)` aus. Kein FULL, REINDEX oder Tabellen-Rewrite.
Die reale 30-Minuten-Altersgrenze bleibt erhalten; Testzeitpunkte liegen zwei Tage zurück.

| Relation | Zusätzliche Allokation über sechs Folgezyklen |
|---|---:|
| Mitglieder-Heap | 0 Byte |
| Embedding-Index | 0 Byte |
| Primärindex | 253.952 Byte |
| Artikel-Referenzindex | 40.960 Byte |

Freiraumverbrauch des Heaps und der zusammengefassten Indizes wurde in allen sechs
Folgezyklen beobachtet. Beim Embedding-Index selbst wurde kein Verbrauch ganzer
FSM-Freiseiten beobachtet; weitere Inserts wurden ohne Relationserweiterung verarbeitet.
Der interne Wiederverwendungsmechanismus ist damit nicht gesondert gemessen.
Es wird keine direkte FSM-Wiederverwendung jedes einzelnen Index behauptet.
Die Generationen-Grenzregel ist eine zusätzliche Testdiagnostik, kein nachträglich
eingeführtes Ticketkriterium. Ihre numerischen Vergleichsgrößen werden je Datensatz gemessen.

Acht Archive wurden abgeschlossen. Historische Artikelmengen wurden exakt verglichen;
107.000 geschützte Zuordnungsbelege und ein echter Paarbeleg blieben erhalten.
Die Archiv-Tabelle wächst dabei erwartungsgemäß von 8.192 auf 172.032 Byte,
ihr Index von 8.192 auf 16.384 Byte. Das ist legitimes Historienwachstum,
kein allgemeines Plateau der gesamten Datenbank. Die Input-Tabelle einschließlich
Indizes bleibt mit 244.490.240 Byte konstant. Gesamtlaufzeit: 148,357 Sekunden.

**Gegenbefund bleibt erhalten:** Bei allen Eingaben mit einem gemeinsamen Artefaktschlüssel
wächst der Embedding-Index im selben Messfenster um 1.892.352 Byte; die dort gemessene
Ein-Generationen-Vergleichsgröße beträgt 704.512 Byte. Dieser Diagnosefall besteht die
zusätzliche Grenzregel nicht. Aus dem diverseren Lauf folgt keine Zusage für jede
Schlüsselverteilung und kein unbegrenzt langfristiges Indexplateau.

Retention wurde hier direkt aufgerufen, Autovacuum für den Fixture-Mitglieder-Heap
durch explizite normale Vacuum-Zyklen ersetzt. Komprimierbare Vektornutzlast und
parallele lokale Funktionsprüfungen schließen CPU-/Durchsatzinferenz aus.
Containerlimits: PostgreSQL 0,75 CPU/1.152 MiB, Java 1,25 CPU/2.048 MiB,
Heap 1.280 MiB, zusätzlicher Swap aus; keine gemeldeten Container-OOMs.

## Vorbereitung und verbleibende Freigabegrenzen

Der gemeinsame Last-Harness unterstützt jetzt zusätzlich `--history-inputs 20000`.
Diese unabhängigen, nicht aktuellen Eingaben sind 90 Tage alt, bleiben in der
Datenbank und teilen ein vorhandenes gültiges Artefakt; sie erhöhen weder die
logische aktuelle Snapshot-Zahl noch die Anzahl geladener Vektoren.
Der ergänzende Referenz-/Optimierungsvergleich mit 1.070 aktuellen und
20.000 unabhängigen alten Eingaben, drei Freigaben zu je zehn Änderungen und
20-Sekunden-Abständen bestand. Eingabe-Hashes und fachliche Ergebnis-Digests
stimmen in allen drei Intervallen überein; die Binärprüfsummen blieben unverändert.
Der Verifier bestätigt ausdrücklich nur den Smoke-Nachweis.

Tatsächlich verfügbar ist weiterhin AMD Ryzen 9 9900X mit Docker Desktop,
24 logischen Docker-CPUs und 16.322.297.856 Byte VM-RAM. Containerquoten ersetzen
nicht den im Ticket geforderten dedizierten VPS-nahen 2-vCPU-/rund-4-GiB-Nachweis.
Ein separates SSH-Ziel wurde angefragt und liegt nicht vor. Der Produktions-VPS
bleibt gemäß Ticket von zusätzlichen Lasttests ausgeschlossen.

GEM-124 bleibt deshalb In Progress: Der gemeinsame qualifizierte Leistungslauf mit
107k aktuellen Eingaben, zusätzlicher unabhängiger Geschichte, dichter Kandidatenlast,
normaler Wartungs-/Importlast und drei realen Fünf-Minuten-Freigaben ist noch offen.
Die neuen Nachweise sind lokal; PR #22 und #23 sind noch nicht in main integriert.
Es wurden keine zusätzlichen Merge-/Deploy-Berechtigungen aus dem Prüfauftrag abgeleitet.

## Lokale Evidenz

Rohdaten im kombinierten Worktree unter `engine/target/gem124-benchmark`
(`combined-regression.json`, `scheduled-1000.json`, beide Smoke-Berichte und Logs)
und `engine/target/gem124-storage` (`cycles-distinct-full.json`, `cycles-full.json`,
`storage-runtime-manifest.json`). Ergänzende Storage-Quell-/Klassensicherungen und
Fehlversuche bleiben im separaten Storage-Worktree erhalten. Rohdaten sind nicht eingecheckt.

| Datei | SHA-256 |
|---|---|
| combined-regression.json | cf80c1119b42fa85169ceb7f81d9df92a5f7e38c80f3066ad5bdfa8e02dcbffe |
| scheduled-1000.json | eec61008254c64e2914be9d6963c0a0d4c4a1b98f11bcd79eca062d3e73bd45a |
| cycles-distinct-full.json | e35958b214852f8c551ead09d93f36c323b305a26db4307366ee5553c46c7ccc |
| cycles-full.json, gemeinsamer Schlüssel, fehlgeschlagen | c97d7420775afc6888f263b2b55ff64ca2fe6c03ded37b66b6f162013f6e21e7 |
| final-history-smoke-verification.json | 92bbc9861812a5c49d66115f860c36990f1786a1948969dafe0202de29cf6670 |

Die neuen Scheduler- und Speichertests wurden zusätzlich unabhängig statisch geprüft.
Der Bericht unterscheidet bewusst Funktions-, Wiederverwendungs- und Kapazitätsnachweis.
