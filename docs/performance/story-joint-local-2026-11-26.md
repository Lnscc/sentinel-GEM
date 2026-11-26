# GEM-124: integrierter Docker-Lasttest vom 26. November 2026

**Lokaler integrierter Test bestanden; keine vollständige VPS-Abnahme.**
Der integrierte Stand veröffentlicht drei dichte Änderungsintervalle innerhalb
von fünf Minuten, mit gleichen fachlichen Ergebnissen wie die exakte Referenz,
parallelen HTTP-Abfragen, lokaler Importverarbeitung und Snapshot-Bereinigung.
Der Test verwendet kontrollierte synthetische Daten auf einem Ryzen-Docker-Host;
die entsprechende Einschränkung des GEM-124-Vertrags bleibt bestehen.

## Aufbau und Herkunft

- Gemessener Main-Stand: `ca443a170459f3f47ae1b142f1227dcbb68c0fc3`.
  Am Ende der Prüfung mit Remote-main identisch; keine Produktionsänderung.
- Referenz: gleicher integrierter Veröffentlichungs-/Bereinigungscode, vollständiger
  Vektorloader und skalare `ExactCosine`/`StoryIncrementalPairSearch`-Klassen aus
  `5ff162133cbf27f07d70a16a8848533357e84b11`. Das ist eine Rechenreferenz,
  keine unveränderte Installation vor allen drei Untertickets.
- Docker Desktop/WSL2, AMD Ryzen 9 9900X, 24 logische Hostprozessoren,
  16.322.293.760 Byte Docker-VM-RAM. Java 21.0.13, PostgreSQL 17.11.
- PostgreSQL: 0,75 CPU / 1.152 MiB; Engine inklusive Test-/HTTP-Prozessen:
  1,25 CPU / 2.048 MiB. Summe 2 CPU / 3.200 MiB, zusätzlicher Containerswap aus.
  Zusammen mit 512 MiB angenommener Systemreserve 3.712 MiB. Das ist keine
  physisch auf 4 GiB begrenzte VM; der tatsächliche Host ist im Manifest erfasst.
- Eigene Datenbank, internes Docker-Netz, eigenes Volume und ausschließlich
  Loopback-Port 55424. Keine Produktionslast, privaten Datentransfers oder
  Embedding-Aufrufe; keine kostenpflichtige Testmaschine.
- Gesamtdurchlauf 19:11:45–19:41:18 MEZ. Beide Varianten laufen nacheinander.

Anleitung: [Docker-Testaufbau](../../scripts/gem124-benchmark/README.md).
Kompilation findet vor den Messintervallen statt. Verwendete Klassen,
Referenzklassen, Bibliotheken und Testquellen sind gehasht; die Prüfsummen
blieben während des Durchlaufs unverändert und wurden unabhängig nachgerechnet.

## Daten und Ablauf

107.000 gültige, unterschiedliche dichte Float32-Vektoren mit 1.536 Dimensionen,
deterministisch erzeugt, in kleinen Gruppen mit ähnlichen Artikeln. Alle liegen
im fachlichen Kandidatenfenster; daher laden beide Varianten hier tatsächlich
107.000 Vektoren. Dieser Fall zeigt keine Einsparung des Vektorladeumfangs.
Der separate GEM-126-Nachweis deckt unbeteiligte Altgeschichte und No-op-Ladung ab.

Fünf vollständige historische INCREMENTAL-Snapshots und eine geschützte
BACKFILL-Basis werden vorbereitet. Jeder enthält einen echten gespeicherten
Vergleichsbeleg. Die reale 30-Minuten-Aufbewahrungsgrenze bleibt aktiv; der Test
verwendet einen verschärften 30-Sekunden-Bereinigungstakt statt regulär 60 Sekunden.
Bereinigung überlappt in beiden Varianten das erste gemessene Intervall.

Drei Freigaben im tatsächlichen Abstand von ungefähr 300 Sekunden ändern jeweils
1.000 Eingaben. Gemessen wird ab der geplanten Freigabe einschließlich deren
Persistierung, Snapshot-Aufbau, Vektorladung, Vergleich und Veröffentlichung.
Parallel laufen Artikel-, Story- und Statistik-HTTP-Abfragen sowie echte lokale
EVENTS-TSV-Verarbeitung durch Staging und ArticleExtractor. Zusätzliche
EVENTS-/MENTIONS-Testzeilen erzeugen vor der Messung zwei echte Kartenpositionen.
Importierte Hintergrundartikel werden nicht durch Embedding/Story-Erzeugung geführt.

## Veröffentlichungszeiten

Sekunden ab geplanter Freigabe; jede Variante hat 106.999.000 Kandidatenvergleiche
je Intervall. Eingabe-Hashes und Ergebnis-Digests stimmen in allen Intervallen überein.

| Intervall | Referenz | Integrierter Stand |
|---|---:|---:|
| 1 | 157,237 | 118,606 |
| 2 | 152,173 | 109,207 |
| 3 | 153,594 | 110,313 |

Alle sechs Veröffentlichungen liegen unter 300 Sekunden; kein vorheriges
Intervall ist bei der nächsten Freigabe offen. Der Median sinkt um 28,2 Prozent.
Die Vergleichsphasen betragen 88,818/91,057/91,769 Sekunden gegenüber
47,603/47,130/49,661 Sekunden, entsprechend 47,7 Prozent weniger im Median.
Der No-op benötigt 54,107 gegenüber 12,990 Sekunden; er vergleicht keine Kandidaten.
Der Retry wird wiederverwendet. Diese Prüfungen sind kein Prozessabsturz-/Neustarttest.

Die fachlichen Digests umfassen aktuelle Zuordnungen mit Bestvergleichs-Score und
Begründung, Story-/öffentliche IDs, Vertreter, Zustand, Zeiträume, Orte und Lineage.
Die Fixture hat zwei Kartenpositionen und keine Lineage-Kanten; sie deckt keine
große Ortsprojektion oder nichtleere Merge-/Split-Historie ab.

## API, Speicher und Bereinigung

| Prüfung | Referenz | Integrierter Stand |
|---|---:|---:|
| HTTP-Abfragen, alle erfolgreich | 1.050 | 924 |
| Artikel p95 / Maximum | 464 / 1.527 ms | 415 / 1.073 ms |
| Stories p95 / Maximum | 81 / 136 ms | 82 / 102 ms |
| Statistik p95 / Maximum | 1.712 / 2.209 ms | 1.877 / 2.716 ms |
| Container-Lifetime-Speicherspitze Engine | 1.646.034.944 Byte | 1.601.265.664 Byte |
| Größter erfolgreicher Bereinigungsbatch | 992 ms | 1.911 ms |
| Abgeschlossene Archive / unvollständige | 5 / 0 | 5 / 0 |
| Entfernte Mitgliedschaften / obsolete Paare | 535.000 / 5 | 535.000 / 5 |

Keine HTTP-Fehler, Fünf-Sekunden-Timeouts oder gemeldeten Container-/Cgroup-OOMs.
Die Speicherspitzen enthalten den Aufbau; separate Heap- und GC-Messwerte je
Intervall stehen in den Rohberichten. HTTP wird direkt in der Testanwendung
gemessen, ohne öffentlichen Proxy/TLS. p95 ist der beobachtete nächste Rang.

Im integrierten Durchgang gibt ein Bereinigungsbatch nach einem Statement-Timeout
nach. Die folgenden Polls setzen fort und schließen alle fünf Archive ab.
Geschützte Basismitgliedschaften und ihr Vergleichsbeleg bleiben erhalten;
historische Artikelmengen werden beidseitig mit EXCEPT geprüft.
Die Hintergrundverarbeitung extrahiert 7.690 beziehungsweise 6.890 neue Artikel.

Die Snapshot-Mitgliedstabelle wächst physisch von ungefähr 202,4 auf 236,1 MB,
ihre Indizes von 103,5 auf 119,9 MB, während 535.000 Zeilen entfernt und weitere
428.000 geschrieben werden. Das belegt Bereinigung und Wiederverwendung,
**kein langfristiges physisches Plateau**. Die anfängliche physische Belegung
anderer Indizes unterscheidet sich zwischen den separat aufgebauten Schemata;
dieser einzelne Vergleich ist kein statistisch abgesicherter universeller Faktor.

## Abnahmegrenze und Artefakte

Die unabhängige Prüfung bestätigt die lokalen Zahlen, Ergebnisgleichheit,
Ressourcengrenzen und Herkunft. Die vollständige GEM-124-Abnahme bleibt offen:

- CPU-/Storage-Leistung und gesamter Hostspeicher entsprechen nicht dem echten VPS.
- Kontrollierte synthetische Gruppen sind kein repräsentativer Produktionsreplay.
- Ready-Input-Veröffentlichung und paralleles Staging/Extrahieren ersetzen keine
  komplette Download-/Embedding-/Scheduler-Pipeline. Deren Laufzeiten sind nicht gemessen.
- Langfristiges Indexwachstum und der bekannte Massen-Projektionsfall aus GEM-125
  sind durch diesen Test nicht abgeschlossen.

Rohberichte liegen lokal unter `engine/target/gem124-benchmark`, nicht eingecheckt:

| Datei | SHA-256 |
|---|---|
| dense-manifest.json | 9d12975d36ba09d61342960e5b63aed1d25422e9917648c4b1875667728b19e5 |
| dense-reference.json | cc2757d31007b414236269d018a704d9dce7dc6626c3b81f8f7288291e1376e0 |
| dense-integrated.json | 2fe8107b2017f8b4521a91f3b2ca1bb16bbda80ed6f2396ca4f94de3ebcb08c4 |
| dense-verification.json | bc9ae25fd3de0bd033fec795697279d76d907ae1788ce13c05338389075d2a5a |

Die Testcontainer sind nach dem Lauf gestoppt; das eigene Volume bleibt zur
Diagnose erhalten. Quellcode/Testbericht liegen zunächst nur im isolierten
GEM-124-Worktree. Der Hauptcheckout und seine fremden Änderungen bleiben erhalten.
