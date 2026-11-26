# GEM-124: verbindliche Docker-Gesamtabnahme

**Die vereinbarte Docker-Gesamtabnahme ist bestanden.**
Die Code-Integration der offenen PRs und ein Produktionsrollout sind getrennte Schritte.

Der Nutzer hat am 26. November 2026 ausdrücklich die vollständige Abnahme in
Docker ohne zusätzlichen Testserver freigegeben. Der Hardwarepassus von
[GEM-124](https://linear.app/sentinel-gem/issue/GEM-124) wurde entsprechend ersetzt.
Die übrigen fachlichen, Last-, Speicher- und API-Kriterien bleiben bestehen.

## Abnahmebasis und Messung

- Isoliertes Docker-Projekt `gem124-benchmark`, eigene Datenbank und internes Netz.
  Keine Last auf der Produktion, keine externen Embedding-Aufrufe.
- Gemeinsames Limit: 2 CPUs, 3.200 MiB Container-RAM, zusätzlicher Swap aus;
  weitere 512 MiB dokumentierte Systemreserve. PostgreSQL: 0,75 CPU/1.152 MiB;
  Engine einschließlich Test-/API-Prozessen: 1,25 CPU/2.048 MiB.
- Der freie Hostspeicher wird vor Beginn einschließlich Reserve geprüft;
  die durchgesetzten Containerlimits werden zusätzlich aus Docker ausgelesen.
  Ein leeres Docker-Inventar bedeutet keine weiteren laufenden Docker-Container,
  keine zugesicherte CPU-Inaktivität aller Windows-Prozesse.
- Hardware: AMD Ryzen 9 9900X, Docker Desktop/WSL2, 24 logische Docker-CPUs,
  16.322.297.856 Byte Docker-VM-RAM (rund 15,2 GiB). Identische Laufzeiten auf dem konkreten
  Produktions-VPS werden ausdrücklich nicht zugesichert und sind kein Abnahmehindernis.
- 107.000 aktuelle Eingaben mit unterschiedlichen dichten Float32-Vektoren,
  je 1.536 Dimensionen, zusätzlich 20.000 unabhängige nicht aktuelle Eingaben.
  Drei Freigaben im tatsächlichen Abstand von 300 Sekunden ändern jeweils 1.000 Inputs.
- Referenz und optimierter Stand laufen nacheinander mit eingefrorener Epoche
  und identischen Eingaben. Parallel laufen HTTP-Abfragen, lokale echte
  EVENTS-Staging-/Extraktionsarbeit und Snapshot-Retention mit unveränderter
  30-Minuten-Altersgrenze; Retention pollt im Stresslauf alle 30 Sekunden.
- Referenz ist der gleiche Veröffentlichungs-/Retentioncode mit vollständiger
  Vektorladung und eingefrorener skalarer Vergleichsrechnung aus `5ff1621`.
  Sie ist keine unveränderte Installation vor allen drei Untertickets.
- Gemessener Produktionscode aus PR #23 (`d5a2c52`), kombiniert mit dem Harness
  aus PR #22 (`f6b293f`) und den lokalen Abnahmeerweiterungen. Neue Commits,
  Merge oder Rollout sind nicht Bestandteil dieses Messlaufs.

Der endgültige Lauf heißt `docker-final-107k-v2-20261126`. Ein früher begonnener
Lauf wurde zur Härtung zweier unabhängig gefundener Prüflücken abgebrochen;
sein Exit 143 und sein Manifest bleiben erhalten und zählen nicht als Abnahme.
Vor dem Neustart wurden Mindestdatenmengen/Manifest-Konsistenz und die tatsächlich
freie RAM-Reserve unabhängig geprüft. Neun Python-Prüfungen einschließlich
negativer Gate-Fälle bestanden. Der endgültige Messlauf friert die danach
verwendeten Testklassen, Referenzklassen, Bibliotheken und Skripte ein.

## Ergänzende funktionale und physische Nachweise

Die [ergänzende lokale Abnahme](story-joint-followup-local-2026-11-26.md) enthält:

- 27 Unit- und 56 tatsächliche PostgreSQL-Integrationstests, ohne IT-Skips,
  Fehler oder Abbrüche: exakte Referenzgleichheit, historische Recherche/Statistik,
  geschützte Evidenz, Backfill/Reprocessing, Promotion, Retry, Lease/Fencing,
  stabile IDs und große Projektionsmengen.
- Die tatsächliche Scheduler-Kette über lokale HTTP-Fixtures bis zur öffentlichen
  Story-API mit drei Freigaben zu je 1.000 Artikeln: 3.000 Zuordnungen,
  9.000 verknüpfte Signale, keine zusätzlichen Embedding-Eingaben/Dubletten.
  Diese beschleunigte Funktionsprüfung ersetzt den großen Lastlauf nicht.
- Acht Speicherzyklen mit 107k aktuellen und 107k nicht aktuellen historischen
  Inputs sowie 214k Artefaktschlüsseln. Nach Aufwärmung wachsen der gemessene
  Mitglieder-Heap und seine drei Indizes zusammen um 294.912 Byte, 0,57 % einer
  gemessenen Generation; seit Zyklus 4 bleibt ihre Gesamtgröße konstant.
  Freiraumwiederverwendung in allen sechs Folgezyklen, 428k verbleibende Mitglieder,
  geschützte Belege und exakte historische Artikelmengen erhalten.

## Bewertung des Index-Gegenbefunds

Die zusätzliche Variante mit nur einem Artefaktschlüssel bleibt als fehlgeschlagener
Diagnosefall erhalten: Der Embedding-Index wächst über sechs Folgezyklen um
1.892.352 Byte und überschreitet seine zusätzliche Ein-Generationen-Vergleichsgröße
von 704.512 Byte. Diese Einzelindex-Grenze wurde nicht nachträglich zur Ticketbedingung.

Der gesamte gemessene Mitglieder-Heap einschließlich dreier Indizes wächst in
diesem Extremfall um 1.957.888 Byte, 4,10 % einer Generation, während 6/6 Folgezyklen
Freiraum wiederverwenden. Der beobachtete Anstieg flacht ab; die letzten beiden
Zyklen haben die gleiche Indexgröße. Die acht Zyklen beweisen weder unvermeidlich
unbegrenztes Wachstum noch eine endgültige Stabilisierung dieser Extremverteilung.

Die aktuelle dichte Lastfixture verwendet dagegen 107.000 unterschiedliche
Ausgangsartefakte und 3.000 Revisionsartefakte; ihre 20.000 alten nicht aktuellen
Inputs werden nicht zu Snapshot-Mitgliedschaften. Die zusätzliche Indexheuristik
wird deshalb separat vom ursprünglichen Ticketziel bewertet: Wiederverwendung
und stark begrenztes Folgewachstum im beobachteten Fenster sind belegt;
eine universelle oder unbegrenzt langfristige Plateaugarantie wird nicht erteilt.
Eine unabhängige Sichtung bestätigt diese begrenzte Interpretation.

## Ergebnis des vollständigen Lastlaufs

26. November 2026, 21:11:19–21:41:13 MEZ. Beide Varianten schließen mit Exit 0 ab.
Der Verifier meldet `dockerLoadAcceptancePassed=true`, `smoke=false`, keine Fehler.
Die eingefrorenen Binär-/Quellprüfsummen und beide Log-Hashes wurden unabhängig
nachgerechnet; sie stimmen mit dem Manifest überein.

| Freigabe | Referenz ab Freigabe | Optimiert ab Freigabe | Kandidatenvergleiche je Variante |
|---|---:|---:|---:|
| 1 | 158,537 s | 119,085 s | 106.999.000 |
| 2 | 152,701 s | 111,468 s | 106.999.000 |
| 3 | 165,975 s | 112,310 s | 106.999.000 |

Alle Veröffentlichungen schließen vor der jeweils nächsten Fünf-Minuten-Freigabe
ab. Die Median-Veröffentlichungszeit sinkt um 29,2 %, die Median-Vergleichsphase
um 46,6 %. Das sind Beobachtungen dieser drei Intervalle, keine universellen Faktoren.
Alle drei Eingabe-Hashes und fachlichen Ergebnis-Digests stimmen exakt überein.
Die tatsächlich beobachteten Startabstände liegen wegen Wanduhr-/Schedulerauflösung
knapp unter 300.000 ms; der Harness plant monotone 300-Sekunden-Freigabeslots.

Optimierte Phasen je Freigabe in Sekunden:

| Freigabe | Laden/Snapshot | Vergleich | Veröffentlichung |
|---|---:|---:|---:|
| 1 | 62,898 | 49,460 | 3,951 |
| 2 | 56,015 | 49,274 | 4,601 |
| 3 | 58,945 | 47,694 | 3,684 |

Die Gesamtzeit enthält zusätzlich die Persistierung der freigegebenen Änderungen.
Beide Varianten laden/dekodieren hier je 107.000 Vektoren pro Intervall,
657.408.000 Byte Float32-Nutzlast. Dichte Last benötigt den gesamten Kandidatenbestand;
die getrennte GEM-126-Abnahme belegt die Einsparung bei unbeteiligtem Altbestand.
No-op: 59.555 ms gegenüber 12.699 ms, jeweils null Vergleiche. Retry wird wiederverwendet.
Es sind zwei echte Kartenpositionen und keine Lineage-Kanten vorhanden; zusätzliche
Merge-/Split-/Promotion-Fälle werden durch die gemeinsame Fachregression abgedeckt.

## Nebenlast, Speicher und Verträglichkeit

2.223 HTTP-Abfragen: 1.191 Referenz und 1.032 optimiert, alle HTTP 200 und unter
fünf Sekunden. Latenzen in Millisekunden, p95 als beobachteter nächster Rang:

| Endpunkt | Referenz p95 / Maximum | Optimiert p95 / Maximum |
|---|---:|---:|
| Artikel | 14 / 1.127 | 17 / 771 |
| Stories | 67 / 118 | 66 / 101 |
| Statistik | 1.717 / 3.096 | 1.901 / 2.965 |

Die Importnebenlast extrahiert 7.860 beziehungsweise 6.900 zusätzliche Artikel,
ohne Parsing-/Extraktionsfehler. Diese zusätzlichen Artikel werden im Lastlauf
nicht eingebettet; die vollständige Scheduler-Kette ist separat funktional geprüft.

Container-Lifetime-Speicherspitze Engine: 1.609.150.464 gegenüber 1.601.970.176 Byte,
jeweils unter dem durchgesetzten 2-GiB-Limit. Keine Container-/Cgroup-OOMs.
Heap-Proben und GC-Deltas je Intervall stehen in den Rohberichten; die Heap-Proben
werden alle 20 ms genommen und sind kein garantierter absoluter Heap-Höchstwert.
GC-Zeit je Intervall: Referenz 417/307/325 ms, optimiert 400/420/435 ms.

Je Variante: fünf abgeschlossene Archive, keine unvollständigen Archive,
535.000 entfernte Mitgliedschaften und fünf obsolete Paarbelege.
33 beziehungsweise 27 Retention-Batches überlappen nach unabhängiger Prüfung
tatsächlich Messphasen. Zeitüberschreitungen einzelner Batches führen zum
begrenzten Nachgeben; nachfolgende Polls setzen erfolgreich fort.
Transaktionsdauern einschließlich möglicher Sperrwartezeit sind protokolliert;
konfigurierte Lock-/Statement-Zeitgrenzen begrenzen die Wartung zusätzlich.
Größter erfolgreicher Batch: 851 ms Referenz, 1.797 ms optimiert;
größte Archivierungszeit innerhalb eines Batches: 261 beziehungsweise 391 ms.
Der geschützte vollständige Snapshot mit 107k Mitgliedern und echtem Paarbeleg
bleibt erhalten, historische Artikelmengen bleiben exakt.

Die Mitglieder-Tabelle wächst physisch von rund 202,4 auf 236,1 MB,
ihre Indizes von 103,5 auf 119,9 MB. Das ist nach vier weiteren vollständigen
Schreibgenerationen und fünf verdichteten Altgenerationen kein Plateau-Beleg;
Wiederverwendung und Folgewachstum werden durch die zusätzlichen acht Zyklen
oben bewertet. Physisches Archivvolumen nach Abschluss einschließlich Indizes:
131.072 Byte Referenz, 122.880 Byte optimiert. Legitimes Archiv-/Artefaktwachstum
wird getrennt von redundantem Snapshot-Wachstum betrachtet.

## Abnahmeentscheidung und verbleibender Integrationsschritt

Die drei Untertickets sind Done und ihre Merge-Commits in `origin/main` enthalten.
Der kombinierte Teststand erfüllt die unveränderten fachlichen Kriterien,
das vereinbarte Docker-Lastbudget und die Nachweisanforderungen für Nebenlast,
Ressourcen und Speicherwiederverwendung. Der Index-Gegenbefund bleibt unverändert
sichtbar und begrenzt Aussagen über extreme Verteilungen und unbegrenzte Laufzeiten.
Es wird kein zusätzlicher Testserver benötigt.

Die unabhängige Gegenprüfung bestätigt die Daten, Zeitfenster, Ressourcenlimits,
Hash-Gleichheit und die beschriebenen Grenzen. `vpsAcceptancePassed=false` bleibt
als ausdrückliche Abgrenzung erhalten; es blockiert die vom Nutzer freigegebene
Docker-Abnahme nicht. Produktionsprovider-Latenz und identische VPS-Laufzeit werden
nicht zugesichert.

PR #22 und PR #23 sind zum Messabschluss noch nicht in main integriert. Der
Ticketstatus bleibt deshalb gemäß Repository-Regel bis zur verifizierten Integration
In Progress; die Testabnahme ist erteilt. Kein Produktionsrollout wurde vorgenommen.
Alle Testcontainer sind nach Abschluss gestoppt, eigene Testschemas entfernt und
das eigene Docker-Volume für Diagnose erhalten.

Rohdaten liegen im Messworktree unter `engine/target/gem124-benchmark` und bleiben
unverändert erhalten; sie werden nicht als private Daten oder große Testartefakte
in Git aufgenommen.

| Bericht, Präfix `docker-final-107k-v2-20261126-` | SHA-256 |
|---|---|
| manifest.json | e9177bdb69a73f102b781e984941a47157e059929e46c3d7cda85f28d2df53d2 |
| reference.json | 82a83863f8e018458e734861b328f8fc4903ad7bf98049c0709816668c6777af |
| integrated.json | ad510c586b6bf5f7108aa86b1e5df2a7160b3501059782a5da5779f4b02d4330 |
| verification.json | 974e482aa343932b54a74c95468915cf80478f69609e26f121913b69b54ce789 |
