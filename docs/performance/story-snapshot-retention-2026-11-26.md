# Story-Snapshot-Aufbewahrung: isolierte Abnahme vom 26. November 2026

GEM-125: Die vorhandene Kompaktierung funktioniert mit mehr als 107.000 logischen
Eingaben. Archivvorbereitung und Loeschungen bleiben innerhalb der neuen Zeitgrenzen;
die Aufbewahrung wird daher standardmaessig eingeschaltet. Die vollstaendige
Snapshot-Mitgliedschaft wird weiterhin je Lauf geschrieben, aber bei geeigneten
historischen INCREMENTAL-Snapshots anschliessend verdichtet. Artikelhistorie und
erforderliche Entscheidungsevidenz werden nicht geloescht.

## Aufbau und Reproduktion

- Ausschliesslich lokale synthetische Daten; keine Produktionslast und keine neue
  Datenuebertragung. Dedizierter Container `gem125-postgres`, Loopback-Port 55435,
  PostgreSQL 17.11 (`pgvector/pgvector:pg17`), 1 CPU, 2 GiB RAM, kein Containerswap.
- Java 22.0.2, Zielbytecode Java 21; Test-JVM `-Xmx2g -XX:ActiveProcessorCount=2`.
  Das begrenzt den Heap und die JVM-Prozessorannahme, nicht die gesamte Host-CPU.
  Datenbank, Anwendung, Betriebssystem und Maven haben kein gemeinsames 4-GiB-Limit.
- 107.000 READY-Eingaben mit gueltigem gemeinsamem 1536-dimensionalem Vektor,
  unabhaengige alte Singleton-Stories, gueltige FKs, geschuetzter BACKFILL-Ausgangssnapshot.
  Alte Zeitpunkte liegen 25 Stunden auseinander. Der vorbereitete publizierte
  Ausgangsbestand ist CLOSED und seine Quellprojektionen sind abgearbeitet.
- Acht echte regulaere INCREMENTAL-Laeufe: zuerst 1.000 neue dichte Eingaben und vier
  geaenderte alte Eingaben, danach je 1.000 ersetzte dichte Eingaben, vier ersetzte alte
  Eingaben und acht neue Artikel. Am Ende 108.056 aktuelle Mitgliedschaften.
- Loeschbatches: 10.000 Zeilen je Tabelle; 2 s Statement-, 250 ms Lock- und 5 s
  Transaktionslimit. Maximal 40 Batches je simuliertem Fuenf-Minuten-Zyklus
  (fuenf Polls mit acht Batches). Die Zeit wird ohne Wartepausen simuliert und das
  Mindestalter auf null gesetzt; die letzten drei Publikationen bleiben geschuetzt.
  Das produktive 30-Minuten-Fenster wird durch separate Regressionstests geschuetzt.
- Nach jedem Zyklus regulaeres `VACUUM (ANALYZE)`, kein Tabellen-Rewrite/Reindex.
  Echte HTTP-Proben gegen Artikel, Stories und historische Statistik laufen parallel.

Opt-in-Test, aus `engine` gegen die dedizierte lokale Testdatenbank:

```powershell
.\mvnw.cmd -B '-Dit.test=StorySnapshotRetentionScalePostgresIT' `
  '-Dit.retention.scale=true' '-DargLine=-Xmx2g -XX:ActiveProcessorCount=2' `
  '-Dit.postgres.jdbc-url=jdbc:postgresql://localhost:55435/gem125' `
  '-Dit.postgres.username=gem125' '-Dit.postgres.password=gem125' `
  test-compile failsafe:integration-test failsafe:verify
```

Der Test erstellt und entfernt ausschliesslich sein eigenes Schema. Die Datenbank
muss separat bereitstehen. Der Rohbericht liegt unter
`engine/target/gem125-retention-scale.json` (SHA-256 der abgenommenen Messung:
`f00601d313943d52d4d948927308a761848291609a474f8873b6d29ae37454bc`).

## Ergebnis

Alle acht regulaeren Laeufe und fuenf Verdichtungen bestanden. Jeder geeignete
Snapshot benoetigte elf Transaktionen, also zwei Poll-Budgets statt der simulierten
fuenf. Beim produktiven `fixedDelay` kommt die Poll-Laufzeit zur einminuetigen
Wartezeit hinzu; die 40 Batches sind keine garantierte Fuenf-Minuten-Kapazitaet.
Nach jedem Zyklus: kein bereinigungsfaehiger Rueckstand und keine unvollstaendige
Archivierung. Die maximal gemessene atomare Archivvorbereitung betrug 111 ms;
eine komplette Transaktion einschliesslich Commit maximal 784 ms. Insgesamt wurden
535.028 redundante Mitgliedschaften und drei nicht erforderliche Paarbelege entfernt.
Die uebrigen 8.051 Paarbelege tragen die weiterhin erforderliche Entscheidungsevidenz.

| Messpunkt | Mitgliedschaften | Heap/TOAST/FSM/VM-Tabelle (Byte) | Indizes (Byte) |
|---|---:|---:|---:|
| Ausgangsbestand | 107.000 | 31.342.592 | 21.618.688 |
| Zyklus 4, vor erster Bereinigung | 539.048 | 157.786.112 | 108.060.672 |
| Zyklus 4, nach Bereinigung und Vacuum | 432.052 | 157.786.112 | 108.109.824 |
| Zyklus 8, nach Bereinigung und Vacuum | 436.196 | 159.006.720 | 152.403.968 |

Ueber alle neun Snapshots wurden 971.224 Mitgliedschaften geschrieben. Davon bleiben
436.196 erhalten: der vollstaendige geschuetzte Ausgangsbestand, drei aktuelle volle
Snapshots und 5.052 Mitglieder fuer historische Entscheidungen/Diagnosepaare.
Die fuenf komprimierten Artikel-ID-Archive belegen zusammen 49.573 Byte; temporaere
Paar-ID-Arrays sind nach Abschluss freigegeben.
Die 436.196 Zeilen sind kein zugesagter Produktionsbestand: Im echten
30-Minuten-Fenster bleiben beim Fuenf-Minuten-Takt ungefaehr sechs statt drei
regulaere Vollkopien erhalten, dazu Freigabe-/Replay-Snapshots und Rueckstand.

Nach der ersten Verdichtung wachsen die Tabellendateien in vier weiteren regulaeren
Zyklen nur um 1.220.608 Byte, obwohl weitere 432.176 Mitgliedschaften geschrieben
werden. Freigegebene Seiten werden wiederverwendet. Vor dem letzten Vacuum meldet
PostgreSQL 107.020 tote Mitgliedschaftszeilen, danach null. Die Statistikzaehler
sind asynchron; die exakten Zeilenzaehlungen werden separat erhoben.

Die Indexdateien wachsen in denselben vier Zyklen um 44.294.144 Byte. Insbesondere
erhaltene verstreute Diagnosemitglieder koennen eine vollstaendige Wiederverwendung
duenn belegter Indexseiten verhindern. Die Messung belegt reduziertes Folgewachstum
und wiederverwendbaren Tabellenplatz, **kein physisches Indexplateau**. Indexgroessen
bleiben Teil der Betriebskontrolle; blockierende Tabellen-Neuschreibung wurde weder
ausgefuehrt noch als automatische Wartung eingefuehrt.

| HTTP-Probe | Erfolgreich / gesamt | Hoechste Latenz | Davon waehrend Bereinigung |
|---|---:|---:|---:|
| Artikel | 178 / 178 | 389 ms | 16 |
| Stories | 178 / 178 | 199 ms | 17 |
| Historische Statistik | 178 / 178 | 1.200 ms | 17 |

Exakte historische Artikelmengen werden nach jedem Zyklus beidseitig per `EXCEPT`
gegen die eingefrorene Sollmenge verglichen. Aktuelle Mitgliedschaften stimmen mit
den aktuellen Input-Fingerprints ueberein; unveraenderte alte Mitgliedschaften und
der geschuetzte Ausgangssnapshot bleiben erhalten. Fuer jeden Loeschbatch wird der
Worker neu erstellt; Fortschritt und Archiv leben ausschliesslich in PostgreSQL.
Separate Regressionstests pruefen Freigabebasis, Backfill, Reprocessing, mehrfach
genutzte Snapshots, Altersfenster, Retry sowie Abbruch/Rollback bei Sperr- und
Statement-Timeout und anschliessende Wiederaufnahme.

Die vollstaendige Engine-Verifikation mit der PostgreSQL-Konfiguration aus CI ist
gruen: 165 Unit-Testfaelle (zwei vorhandene Korpus-/Speicher-Opt-ins uebersprungen)
und 77 Integrationstestfaelle (nur dieser separat bestandene Skalentest uebersprungen).
Ein Start des gebauten JARs ohne Retention-Override bestaetigt die standardmaessig
aktive Bereinigung anhand ihrer ausgefuehrten SQL-Abfragen und die Artikel-API mit HTTP 200.

## Grenzen und Betrieb

Dies ist die Speicherabnahme von GEM-125. Die synthetische zeitliche Verteilung und
abweichenden Ressourcen ersetzen weder die dichte VPS-Last noch die gemeinsame
Fuenf-Minuten-Abnahme von GEM-124; GEM-126/GEM-127 bleiben separat.

Ein Vorversuch mit noch 107.000 offenen, bereits projizierten Quellaenderungen im
vorbereiteten Ausgangsbestand traf eine bestehende Grenze von
`StoryPublisher.refreshListValues`: 214.004 SQL-Parameter statt maximal 65.535.
Die Ausgangsprojektionen wurden im Fixture korrekt als abgearbeitet markiert.
Diese Korrektur behebt keinen produktiven Massen-Projektionsfall; der Befund ist
fuer die uebergreifende GEM-124-Abnahme in Linear dokumentiert.

Aktivierung, Kontrolle des Altbestands und Rueckweg stehen im
[Betriebshandbuch](../operations.md#aufbewahrung-der-story-snapshot-daten).
Diese Abnahme hat keine produktive Konfiguration oder Datenbank veraendert.
