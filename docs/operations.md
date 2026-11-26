# Operations

## Start

```powershell
docker compose up -d
cd engine
.\mvnw.cmd spring-boot:run
```

Der Import startet nach `gdelt.ingestion.initial-delay` und laeuft danach alle
`gdelt.ingestion.poll-interval`.

`stories.visible-version` steuert sowohl die Story-API als auch die Embedding-Jobs
und muss zur `ACTIVE`-Version in `story_clustering_versions` passen. Nach einer
Promotion die Konfiguration aktualisieren und die Engine neu starten. Zur
Abschlusspruefung die API-Antwort der freigegebenen Version zuordnen und einen
neu importierten Artikel bis zur sichtbaren Story im aktuellen Zeitfenster verfolgen;
eine nicht leere Antwort mit alten Stories reicht nicht aus.

Fehlgeschlagene Zeitfenster werden bei spaeteren Polls erneut versucht. Die Anzahl wird ueber
`gdelt.ingestion.max-failed-windows-per-poll` begrenzt.

## Payload-Retention

Der Retention-Job loescht erfolgreich verarbeitete Payloads standardmaessig sieben Tage nach
`parsed_at`. Die dauerhafte Fachzeile mit derselben ID und die vollstaendige Fehlerhistorie in
`gdelt_processing_errors` bleiben erhalten. Payloads ohne passende Fachzeile werden unabhaengig
von ihrem Alter nicht geloescht.

```properties
gdelt.retention.enabled=true
gdelt.retention.initial-delay=PT2M
gdelt.retention.poll-interval=PT1H
gdelt.retention.period=PT168H
gdelt.retention.batch-size=1000
gdelt.retention.max-batches-per-run=10
```

Ein Batch verarbeitet je Datensatztyp hoechstens `gdelt.retention.batch-size` Zeilen in der
deterministischen Reihenfolge `parsed_at, id`. Jeder Datensatztyp wird in einer eigenen, begrenzten
Transaktion geloescht. Pro Scheduler-Lauf werden maximal
`gdelt.retention.max-batches-per-run` solcher Batches ausgefuehrt. Das Abschluss-Log weist die
geloeschten EVENTS-, MENTIONS- und GKG-Payloads getrennt aus.

Loeschbare und aufgrund der Frist oder fehlender Fachzeile zurueckgehaltene Payloads fuer die
Standardfrist von sieben Tagen:

```powershell
docker compose exec postgres psql -U gne -d gne -c "with retention as (select current_timestamp - interval '7 days' as cutoff), payloads as (select 'EVENTS' as dataset_type, domain_row.parsed_at from gdelt_event_payloads payload left join gdelt_events domain_row on domain_row.id = payload.id union all select 'MENTIONS', domain_row.parsed_at from gdelt_mention_payloads payload left join gdelt_mentions domain_row on domain_row.id = payload.id union all select 'GKG', domain_row.parsed_at from gdelt_gkg_payloads payload left join gdelt_gkg domain_row on domain_row.id = payload.id) select dataset_type, count(*) filter (where parsed_at <= cutoff) as eligible_payload_rows, count(*) filter (where parsed_at is null or parsed_at > cutoff) as retained_payload_rows from payloads cross join retention group by dataset_type order by dataset_type;"
```

Nach dem Cleanup ist `raw_tsv` nicht mehr in der Datenbank vorhanden. Eine Wiederherstellung ist
nur aus einem externen Datenbank-Backup oder durch erneuten Import der unveraenderten Quelldatei
moeglich; Object-Storage-Archivierung ist nicht Bestandteil des Jobs. Vor einer Verkuerzung der
Frist muessen deshalb Backup- und Reimport-Anforderungen betrieblich geprueft werden.

## Tests

```powershell
docker compose up -d
cd engine
.\mvnw.cmd verify
```

Die PostgreSQL-Integrationstests nutzen die lokale Compose-Datenbank unter `localhost:5432`, legen
temporaere Schemas an und entfernen sie nach dem Test wieder. Der gemeinsame Pipeline-Test deckt
Download, Payload-Import, Parsing, Normalisierung und Article-Extraktion fuer EVENTS, MENTIONS und
GKG ab.

## Story-Titel-Inputs und Embeddings

Die periodischen Jobs erzeugen nur fuer die konfigurierte `ACTIVE`-Clustering-Version aktuelle
`story_article_inputs`. Eine `SHADOW`-Version wird ausschliesslich durch einen explizit gestarteten
Release-Evaluationslauf verarbeitet. Verwendbare, normalisierte Titel werden mit dem in der jeweiligen Version
gespeicherten Modell eingebettet. Identische fachliche Artefaktschluessel verwenden genau eine
Zeile in `story_embedding_artifacts`; jeder Provider-Versuch steht dauerhaft in
`story_embedding_attempts`.

```properties
stories.embeddings.api-key=${OPENAI_API_KEY:}
stories.embeddings.base-url=https://api.openai.com/v1
stories.embeddings.timeout=PT30S
stories.embeddings.batch-size=100
stories.embeddings.max-batches-per-run=10
stories.embeddings.incremental.enabled=true
stories.embeddings.incremental.initial-delay=PT45S
stories.embeddings.incremental.poll-interval=PT5M
stories.embeddings.repair.enabled=true
stories.embeddings.repair.batch-size=500
stories.embeddings.repair.cron=0 15 3 * * *
stories.embeddings.repair.zone=UTC
```

Der API-Schluessel wird ausschliesslich aus `OPENAI_API_KEY` beziehungsweise einer externen
Spring-Konfigurationsquelle gelesen und nie protokolliert. Ohne Schluessel bleiben verwendbare
Artefakte `PENDING`; `StoryEmbeddingHealth.report()` meldet `MODEL_CALLS_DISABLED`. Temporaere
Fehler verwenden 1 Minute, 5 Minuten, 15 Minuten, 1 Stunde und 6 Stunden Backoff, wobei
`Retry-After` bei Rate Limits Vorrang hat. Nach fuenf retryfaehigen Fehlern bleibt das Artefakt mit
leerem `next_retry_at` sichtbar und wird nicht automatisch erneut aufgerufen.

Operative Zustaende und Rueckstand:

```powershell
docker compose exec postgres psql -U gne -d gne -c "select status, count(*) from story_embedding_artifacts group by status order by status;"
docker compose exec postgres psql -U gne -d gne -c "select status, attempt_count, count(*) from story_embedding_artifacts where status <> 'READY' group by status, attempt_count order by status, attempt_count;"
docker compose exec postgres psql -U gne -d gne -c "select status, error_code, count(*) from story_embedding_attempts group by status, error_code order by status, error_code;"
```

Micrometer stellt die Zaehler `stories.embedding.model.calls`, `stories.embedding.attempts`,
`stories.embedding.failures`, `stories.embedding.inputs.processed` und den Timer
`stories.embedding.provider.latency` bereit. `stories.embedding.artifacts` weist die Zustaende
per `status`-Tag aus; `stories.embedding.backlog` misst den offenen Rueckstand.

## Story-Snapshots und Kandidatenpaare

Der inkrementelle Snapshot-Job verarbeitet ausschliesslich die `ACTIVE`-Version. Explizite
Release-Evaluationslaeufe koennen eine `SHADOW`-Version auf demselben Watermark verarbeiten. Pro Version friert er die aktuellen Inputs bis zum Lauf-Watermark
atomar in `story_snapshots` und `story_snapshot_members` ein, auch unbrauchbare Titel und
Inputs ohne fertiges Embedding. Nur eingefrorene `READY`-Vektoren gehen in die Paarberechnung ein. Anschliessend
validiert er die kanonischen Float32-Vektoren und berechnet innerhalb des versionierten
24-, 48- oder 72-Stunden-Fensters alle Cosine-Similarities ohne Top-k-Begrenzung.

```properties
stories.snapshots.max-versions-per-run=4
stories.snapshots.claim-timeout=PT30M
stories.snapshots.incremental.enabled=true
stories.snapshots.incremental.initial-delay=PT1M
stories.snapshots.incremental.poll-interval=PT5M
stories.snapshots.backfill.enabled=false
stories.snapshots.backfill.max-versions-per-run=3
stories.snapshots.backfill.initial-delay=PT2M
stories.snapshots.backfill.poll-interval=PT1H
# erforderlich, wenn Backfill aktiviert wird:
stories.snapshots.backfill.watermark=2026-07-25T00:00:00Z
```

Der Backfill ist standardmaessig ausgeschaltet. Sein festes Watermark begrenzt die Eingabemenge
auf `effective_at <= watermark` und macht wiederholte Aufrufe idempotent. Fuer einen manuellen
Reprocessing-Aufruf steht `StorySnapshotService.reprocess(watermark, maxVersions)` bereit.

Ein reproduzierbarer Lauf gegen einen eingefrorenen produktionsnahen Snapshot kann als
One-shot-Benchmark gestartet werden. Alle regulaeren Jobs bleiben dabei deaktiviert:

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--spring.main.web-application-type=none --gdelt.ingestion.enabled=false --gdelt.staging.enabled=false --gdelt.retention.enabled=false --articles.enabled=false --stories.embeddings.incremental.enabled=false --stories.embeddings.repair.enabled=false --stories.snapshots.incremental.enabled=false --stories.snapshots.backfill.enabled=false --stories.snapshots.benchmark.watermark=2026-10-24T16:57:16.034881Z'
```
`max-versions-per-run` begrenzt jeweils die Anzahl der Versionen. Ein `RUNNING`-Claim darf nach
`claim-timeout` mit demselben Snapshot, derselben Run-Zeile und einem neuen Fencing-Token
wiederaufgenommen werden. Snapshot-Erzeugung und Run-Claim liegen unter demselben
transaktionalen Versions-Lock. Solange ein frischer `RUNNING`-Run fuer eine Version existiert,
erzeugt auch ein neu gestarteter oder konkurrierender Worker fuer diese Version keinen weiteren
Snapshot.

Inkrementelle Laeufe speichern hoechstens zwei Paarentscheidungen pro geaendertem Artikel.
Der Job speichert den staerksten positiven Treffer und bei Abweichung das Paar fuer die
gewaehlte Zuordnung. Ohne positiven Treffer speichert er den besten Vergleich unterhalb
der Schwelle als `UNCERTAIN`. Backfill und Reprocessing speichern weiterhin alle positiven
Treffer und die beste Diagnose je Artikel ohne positiven Treffer. Die Publikation
schreibt Stories, Mitgliedschaften, Assignment-Entscheidungen, Paarentscheidungen und
den Publish-Nachweis in einer Transaktion.
Die exakte Suche nutzt die Sortierung nach `effective_at, article_ref`, um Vergleiche auf
das konfigurierte Zeitfenster zu beschraenken.

Operative Kontrollen:

```powershell
docker compose logs --since 30m engine
docker compose exec postgres psql -U gne -d gne -c "select version.version_key, run.run_mode, run.status, run.read_article_count, run.candidate_count, run.failed_article_count, run.started_at, run.completed_at from story_processing_runs run join story_clustering_versions version on version.id = run.clustering_version_id order by run.started_at desc limit 20;"
docker compose exec postgres psql -U gne -d gne -c "select version.version_key, max(run.completed_at) as last_success from story_clustering_versions version left join story_processing_runs run on run.clustering_version_id = version.id and run.run_mode = 'INCREMENTAL' and run.status = 'SUCCEEDED' where version.status = 'ACTIVE' group by version.version_key;"
docker compose exec postgres psql -U gne -d gne -c "select relation, pg_total_relation_size(relation) as bytes from (values ('story_pair_decisions'::regclass), ('story_snapshot_members'::regclass)) as tables(relation);"
docker compose exec postgres psql -U gne -d gne -c "select version.version_key, count(distinct snapshot.id) as snapshots, count(member.*) as members from story_snapshots snapshot join story_clustering_versions version on version.id = snapshot.clustering_version_id left join story_snapshot_members member on member.snapshot_id = snapshot.id group by version.version_key order by version.version_key;"
docker compose exec postgres psql -U gne -d gne -c "select result, top_one_below_threshold, count(*) from story_pair_decisions group by result, top_one_below_threshold order by result, top_one_below_threshold;"
```

`last_success` basiert auf dem gespeicherten Laufstatus und bleibt nach einem Neustart
verfuegbar. Die Tabellengroessen enthalten Indizes. Das Abschluss-Log des inkrementellen
Jobs zeigt den belegten Heap vor und nach dem Lauf sowie das JVM-Limit in Bytes. Fuer
Auslastung waehrend des Laufs JVM-GC-Logging mit `JAVA_TOOL_OPTIONS=-Xlog:gc` in der
Umgebung des Engine-Prozesses aktivieren und das Engine-Log verfolgen.

Micrometer stellt `stories.snapshot.created`, `stories.snapshot.reused`,
`stories.snapshot.members`, `stories.snapshot.candidates`, `stories.snapshot.failures`, den
resultatgetaggten Zaehler `stories.snapshot.pairs` und den Timer
`stories.snapshot.processing.latency` bereit.

Der Publisher prueft den Claim-Timeout als Lease, den neuesten Fencing-Token und den
optimistischen Story-Stand. Bei Konflikt oder Ablauf rollt die gesamte Publish-Transaktion
zurueck; der Run wird als fehlgeschlagen markiert und ist retryfaehig. Ein bereits publizierter
Snapshot bleibt ein No-op. Noch nicht publizierte Snapshots hinter einem neueren Commit
(geordnet nach Watermark, dann Snapshot-ID) werden `DISCARDED`.

Split-Nominierung und anschliessende Merge-Auswahl erhalten bestehende IDs deterministisch.
Unveraenderte Mitgliedschaften erzeugen keine neuen Mitgliedschaftszeilen. Stories schliessen
nach 72 Stunden ohne Aenderung beziehungsweise neues Input-Ereignis und oeffnen bei Aenderungen
wieder. Titel ohne Inhalt erhalten `TITLE_MISSING` oder `TITLE_GENERIC`; ein im Snapshot
fehlender fertiger Vektor ergibt `EMBEDDING_NOT_READY`, auch wenn er beim Retry inzwischen
vorliegt. Bisherige Mitglieder mit einer Zeitkorrektur hinter dem Watermark werden ebenfalls
eingefroren und mit `AFTER_WATERMARK` abgemeldet. Shadow-Ergebnisse werden erst nach
expliziter Promotion produktsichtbar; danach aktualisieren weitere Laeufe die aktive Version.

Zusaetzlich melden `stories.publish.runs` (`result`), `stories.publish.conflicts` und
`stories.publish.results` (`kind`) Publikationen, Wiederholungen, verworfene Laeufe,
Konflikte sowie neue, erweiterte, geschlossene, wiedereroeffnete, gemergte, gesplittete und
abgeloeste Stories, Singletons, Mitgliedschaften und `UNASSIGNED` nach Grund. Ergebniszaehler
werden erst nach erfolgreichem Commit erhoeht und summieren die Ergebnisse je Lauf.

Nach einem JVM-Heap-Abbruch die Anwendung mit dem korrigierten Build neu starten.
Ein dabei hinterlassener `RUNNING`-Claim wird nach seinem regulaeren 30-Minuten-Timeout
automatisch erneut aufgenommen; der Timeout beginnt bei `started_at`, nicht beim Neustart.
Der Partitionscache ist auf 65.536 Scores begrenzt. Die Publikation clustert unabhaengige
Gruppen des exakten Kandidatengraphen separat und behaelt keine Merge-Diagnosehistorie im Heap.
Die Paarberechnung bleibt exakt; grosse zusammenhaengende Gruppen und dichte Paarmengen
muessen weiterhin hinsichtlich Laufzeit und Speicher vermessen werden.

### Aufbewahrung der Story-Snapshot-Daten

Die Aufbewahrung ist nach der [isolierten 107k-Pruefung](performance/story-snapshot-retention-2026-11-26.md)
standardmaessig eingeschaltet. `STORIES_SNAPSHOTS_RETENTION_ENABLED=false` schaltet
sie explizit aus. Der Job verdichtet ausschliesslich aeltere,
erfolgreich publizierte inkrementelle Snapshots. Die drei neuesten Publikationen pro
Version und Laeufe mit Abschluss in den letzten 30 Minuten bleiben vollstaendig erhalten.
Dasselbe gilt fuer die Freigabebasis, Backfill, Reprocessing und wiederholbare Laeufe.
Story-Zuordnungen und aktuelle Mitgliedschaften bleiben verfuegbar.

Ein verdichteter Snapshot behaelt maximal ein Diagnosepaar pro Assignment-Entscheidung
und die von diesen Entscheidungen und Paaren referenzierten Snapshot-Mitglieder.
Fuer die Artikelstatistik bleiben die Abstaende zwischen aufsteigend sortierten IDs
erhalten. Vor Kompression fallen acht Byte pro Artikel zuzueglich Array-Overhead an,
rund 0,4 MiB bei 50.000 Artikeln. Ein synthetischer PostgreSQL-17-Test mit 50.175 IDs
benoetigte nach Kompression je Snapshot 4,6 bis 6,3 KiB. Die Archivdaten wachsen mit der
Zahl der verdichteten Snapshots.
Beim regulaeren Fuenf-Minuten-Takt umfasst das 30-Minuten-Fenster rund sechs volle
Snapshots je Version. Freigabe-, Backfill- und Reprocessing-Snapshots, laufende Jobs und
ein eventueller Bereinigungsrueckstand kommen hinzu.

Der Job startet nach zwei Minuten und wartet eine Minute zwischen Durchlaeufen.
Standardmaessig verarbeitet er hoechstens acht Transaktionen je Durchlauf und entfernt
pro Transaktion bis zu 10.000 Zeilen je Tabelle. Das Log meldet Snapshot-ID und die
entfernten Zeilen sowie `archiveMs` und `elapsedMs`. Auch die atomare Archivvorbereitung
unterliegt Zeitgrenzen; sie schreibt keine teilweise sichtbare Artikelstatistik.

```properties
stories.snapshots.retention.statement-timeout=PT2S
stories.snapshots.retention.lock-timeout=PT0.25S
stories.snapshots.retention.transaction-timeout=PT5S
```

Diese Grenzen gelten lokal fuer die jeweilige Transaktion. Der Job versucht den
Versions-Lock ohne Wartezeit zu erwerben. Ein SQL- oder Lock-Timeout rollt den
gesamten aktuellen Batch einschliesslich einer neu angelegten Archivzeile zurueck.
Der naechste Poll setzt am letzten erfolgreich committeten Batch fort; ein
Prozessneustart braucht keinen zusaetzlichen Fortschrittsstand. Bei Zeitueberschreitung
meldet der Job `yielded`, gibt die Versionssperre frei und bearbeitet diese Version
erst im naechsten Poll weiter. Acht Batchversuche mit je fuenf Sekunden begrenzen
die Bereinigungsarbeit eines Polls; auch abgebrochene Versuche zaehlen, andere
Versionen teilen dieses Batchbudget. Leere Versionen werden nur kurz geprueft.
Wiederholte Timeouts sind kein erfolgreicher Abbau: Rueckstand und Archivkosten
pruefen, bei langsamen Loeschungen die Batchgroesse senken. Die Archivvorbereitung
muss im gemessenen Zeitbudget abschliessen, bevor eine Aktivierung freigegeben wird.

PostgreSQL kann freigegebene Seiten nach dem regulaeren Vacuum
wiederverwenden. Fuer die Messung des Speicherbudgets die physischen Tabellengroessen
ueber mehrere Laeufe beobachten. Die Migration legt vier Indizes an. Fuer den
Migrationsstart entsprechend Platz fuer Indizes und WAL vorhalten.

Die Artikelstatistik liest die archivierten ID-Abstaende im angefragten Zeitraum fuer
die exakte Zahl unterschiedlicher Artikel. Im synthetischen Test dauerte die Abfrage
mit 288 verdichteten Snapshots dieser Groesse rund neun Sekunden.

Vor dem ersten Betrieb mit vorhandenen Altdaten eine verifizierte Sicherung und
ausreichenden Platz fuer WAL und Archiv vorhalten. Beim Rollout vorhandene explizite
`STORIES_SNAPSHOTS_RETENTION_ENABLED=false`-Overrides pruefen: Sie bleiben wirksam.
Die alten Snapshots werden ueber dieselben begrenzten Batches abgebaut; keine
Einmalbereinigung, kein Reset und kein `VACUUM FULL` sind erforderlich.

Rueckweg: `STORIES_SNAPSHOTS_RETENTION_ENABLED=false` setzen und die Engine neu starten.
Bereits committete Verdichtungen bleiben fachlich gueltig; angefangene Batches werden
beim Abbruch vollstaendig zurueckgerollt. Das Abschalten stellt geloeschte redundante
Mitglieder/Paarentscheidungen nicht wieder her. Falls deren physische Vollkopien
benoetigt werden, die Sicherung in einer getrennten Datenbank wiederherstellen und
pruefen, bevor ein Datenbankwechsel erfolgt. Artikelhistorie, aktuelle Mitgliedschaften,
Assignments, Diagnosebelege und exakte Statistik bleiben auch ohne Restore erhalten.

Nach Aktivierung `yielded`, Archive, Loeschmengen und Tabellen-/Indexgroessen ueber
mehrere regulaere Zyklen beobachten. Der 107k-Test brauchte elf Batches pro
bereinigtem Snapshot, also zwei Standardpolls. Die 40 Batches im Test sind eine
simulierte Obergrenze. Beim echten `fixedDelay` kommt die Poll-Laufzeit zur
einminuetigen Wartezeit hinzu; weitere Scheduler-Arbeit kann den Takt verzoegern.
Das Altersfenster, geschuetzte Snapshots und andere Versionen kommen zum Bestand
hinzu. Vacuum ersetzt keine Kontrolle des Indexwachstums: Erhaltene Diagnosemitglieder
koennen duenn belegte Indexseiten zuruecklassen. Der Messbericht weist dieses
Restwachstum aus; sofort kleinere Dateien oder ein physisches Indexplateau werden
nicht zugesagt. Eine spaetere Indexwartung ist gesondert zu planen.

### Inkrementelle Berechnung unter Last pruefen

Mit einer isolierten, produktionsnahen Kopie und dem JVM-Heap-Limit des VPS pruefen.
Der erste Lauf enthaelt den Rueckstau. Als Lastreferenz dienen 50.175 Artikel und rund
48,3 Millionen Kandidatenvergleiche aus dem Vorfall vom 24. November 2026. Fuer mindestens
zwei weitere Laeufe jeweils neue verarbeitbare Inputs zufuehren und die neuen
`INCREMENTAL`-Laeufe anhand ihrer Startzeit und des Status `SUCCEEDED` nachweisen.
Heap-Auslastung in den GC-Logs, Kandidatenanzahl und die beiden Tabellengroessen vor und nach
den Laeufen erfassen. Fuer die Speicherpruefung die Snapshot-Aufbewahrung in der isolierten
Umgebung einschalten und bis zum Abschluss der Verdichtung messen.

Waehrenddessen Artikel- und Story-API ueber den Proxy in einer getrennten Shell abfragen.
`api_base` ist beim lokalen Compose-Stack `http://localhost:5173/api`, auf dem VPS
`https://sentinel-gem.com/api`.

```bash
api_base=${api_base:-http://localhost:5173/api}
while true; do
  for path in 'articles?limit=1' 'stories?limit=1'; do
    curl --fail --silent --show-error --max-time 5 --output /dev/null \
      --write-out '%{http_code} %{time_total}s %{url_effective}\n' "$api_base/$path" || exit 1
  done
  sleep 5
done
```

Fuer die Wiederaufnahme nach den Last- und Speicherpruefungen in der bestehenden
Host-Datei `$HOME/sentinel-gem-vps/.env` die Schalter
`STORIES_SNAPSHOTS_INCREMENTAL_ENABLED=true` und
`STORIES_SNAPSHOTS_RETENTION_ENABLED=true` setzen. `STORIES_VISIBLE_VERSION` auf den
Schluessel der freigegebenen `ACTIVE`-Version setzen. Das regulaere `deploy` liest
diese Datei bei jedem Compose-Start. Auf dem VPS die Compose-Befehle mit
`-f compose.yaml -f compose.vps.yaml` ausfuehren und nach dem Deployment einen
neu publizierten Story-Eintrag aus dieser Version pruefen.
Bei einer verorteten Story muss dieselbe ID auch in `/api/stories/map-positions` und
im entsprechenden Kartenzeitraum sichtbar sein.

## Importstatus

Letzte erfolgreich importierte Dateien:

```powershell
docker compose exec postgres psql -U gne -d gne -c "select dataset_type, source_timestamp, row_count, completed_at, source_file from gdelt_import_files where status = 'COMPLETED' order by completed_at desc limit 10;"
```

Letzte vollstaendig importierte Zeitfenster:

```powershell
docker compose exec postgres psql -U gne -d gne -c "select source_timestamp, count(*) as completed_files, sum(row_count) as rows from gdelt_import_files where status = 'COMPLETED' group by source_timestamp having count(*) = 3 order by source_timestamp desc limit 10;"
```

Fehlgeschlagene Dateien:

```powershell
docker compose exec postgres psql -U gne -d gne -c "select dataset_type, source_timestamp, status, error_message, completed_at, source_file from gdelt_import_files where status = 'FAILED' order by completed_at desc limit 20;"
```

Import-Zusammenfassung:

```powershell
docker compose exec postgres psql -U gne -d gne -c "select status, dataset_type, count(*) as files, sum(row_count) as rows from gdelt_import_files group by status, dataset_type order by status, dataset_type;"
```

## Pipeline-Health

EVENTS, MENTIONS und GKG verwenden ein gemeinsames Persistenzmodell: Die temporaeren Payloads
liegen in `gdelt_event_payloads`, `gdelt_mention_payloads` und `gdelt_gkg_payloads`; erfolgreich
geparste und normalisierte Fachzeilen liegen mit derselben ID in `gdelt_events`, `gdelt_mentions`
und `gdelt_gkg`. `gdelt_pipeline_health_view` weist Payloads, Payloads ohne Fachzeile, offene
Processing-Fehler und vorhandene Fachzeilen je Datensatztyp getrennt aus:

```powershell
docker compose exec postgres psql -U gne -d gne -c "select dataset_type, payload_rows, pending_payload_rows, open_processing_errors, domain_rows from gdelt_pipeline_health_view order by dataset_type;"
```

## Processing-Fehler

Jeder fehlgeschlagene Parsing-Versuch wird dauerhaft in `gdelt_processing_errors` protokolliert.
Die unveraenderte Quellzeile liegt bis zu einer erfolgreichen Verarbeitung und dem anschliessenden
Ablauf der Retention ausschliesslich in der jeweiligen Payload-Tabelle; sie wird nicht in der
Fehlerhistorie dupliziert. Der Parser versucht Payloads ohne Fachzeile bei spaeteren Laeufen erneut.
Zwischen zwei Versuchen derselben fehlerhaften Quellzeile liegt mindestens
`gdelt.staging.retry-delay` (Standard: `PT1M`), damit ein Scheduler-Lauf keine unmittelbare
Retry-Schleife erzeugt. Bei Erfolg erhalten alle offenen Fehler derselben Kombination aus
`dataset_type` und `source_row_id` einen Wert in `resolved_at`.

Offene Fehler:

```powershell
docker compose exec postgres psql -U gne -d gne -c "select dataset_type, failed_step, error_code, count(*) as attempts from gdelt_processing_errors where resolved_at is null group by dataset_type, failed_step, error_code order by dataset_type, failed_step, error_code;"
```

Fehlerhistorie einer Quellzeile:

```powershell
docker compose exec postgres psql -U gne -d gne -c "select id, dataset_type, source_row_id, failed_step, error_code, occurred_at, resolved_at from gdelt_processing_errors where dataset_type = 'EVENTS' and source_row_id = 4711 order by occurred_at, id;"
```

Historische und weiterhin offene Versuche:

```powershell
docker compose exec postgres psql -U gne -d gne -c "select dataset_type, resolved_at is null as open, count(*) as attempts from gdelt_processing_errors group by dataset_type, resolved_at is null order by dataset_type, open desc;"
```

Payloads ohne Fachzeile fuer alle Datensatztypen:

```powershell
docker compose exec postgres psql -U gne -d gne -c "select * from gdelt_pipeline_health_view where pending_payload_rows > 0 order by dataset_type;"
```


## Story-Version automatisiert pruefen und freigeben (ART-039, ART-049)

Der lokale Release-Befehl prueft zuerst Status, Wasserstaende, laufende Verarbeitung sowie
fehlende oder veraltete Inputs. Danach verarbeitet er ausschliesslich die Kandidatenversion in
begrenzten Embedding-Batches, publiziert einen idempotenten Snapshot auf dem Wasserstand der
aktiven Version und erzeugt die Review-Evidenz. Bereits fertige Inputs, Embeddings, Snapshots und
Runs werden wiederverwendet. `OPENAI_API_KEY` muss gesetzt sein, falls noch Embeddings fehlen.

Review ohne Statuswechsel:

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--spring.main.web-application-type=none --gdelt.ingestion.enabled=false --gdelt.staging.enabled=false --gdelt.retention.enabled=false --articles.enabled=false --stories.embeddings.incremental.enabled=false --stories.embeddings.repair.enabled=false --stories.snapshots.incremental.enabled=false --stories.snapshots.backfill.enabled=false --stories.release.candidate-version=story-mvp-title-embedding-24h-v1.1.0 --stories.release.holdout=../docs/analysis/ART-048-holdout-v1.json --stories.release.reviewed-by=Linus --stories.release.review-document=../docs/analysis/ART-048-holdout-review.md --stories.release.review-reason=Unveraenderter-Kandidat-fachlich-geprueft'
```

Die deaktivierten Hintergrundjobs halten den Datenstand waehrend des Release-Laufs stabil.
Metadatenwerte in `spring-boot.run.arguments` werden ohne Leerzeichen uebergeben.

Standardmaessig wird `../docs/analysis/ART-045-regression-cases-v1.json` getrennt vom unabhaengigen
Holdout ausgewertet. Das reproduzierbare Ergebnis landet in
`../docs/analysis/<candidate>-release-evidence.json`; beide Pfade koennen mit
`--stories.release.regression` und `--stories.release.evidence` ueberschrieben werden. Jede
Fortschrittsmeldung ist eine JSON-Logzeile mit `storyReleaseStep` und `elapsedMillis`. Ein Abbruch
nennt den Blocker; derselbe Befehl kann nach dessen Behebung erneut gestartet werden.

Die Evidenz bindet Holdout, Regressionen, Review, Zustimmung und Batch-Konfiguration ueber einen
Request-Hash an den Lauf. Vor der Statusaenderung wird atomar eine `PROMOTION_PENDING`-Evidenz
geschrieben. Falls der abschliessende Dateischreibvorgang scheitert, vervollstaendigt derselbe
Aufruf die Evidenz, ohne die Promotion zu wiederholen. Abweichende Eingaben werden nicht als
idempotente Wiederholung akzeptiert.

Eine Promotion erfordert zusaetzlich den ausdruecklichen Schalter und alle drei
Zustimmungsangaben:

```text
--stories.release.promote=true
--stories.release.approved-by=<fachlich Verantwortlicher>
--stories.release.approval-document=<Dokumentverweis>
--stories.release.approval-reason=<Begruendung>
```

Ohne diese Zustimmung bleibt der Kandidat `SHADOW`. Bei nicht bestandenen
`story-release-gates-v1` bleibt er ebenfalls unveraendert. Nach einer Promotion prueft der
Workflow genau eine `ACTIVE`-Version sowie eine erfolgreiche Story-Liste und Story-Detailabfrage.
Der Befehl stellt keine REST API bereit und beendet die Spring-Anwendung nach dem Lauf.

### Technischer Freigabevertrag

Der interne Spring-Service `StoryPromotionService` bietet `review(versionId, holdout)` und
`promote(review, holdout, approval)`. Es gibt dafuer keine REST API und keinen automatischen
Freigabejob. Ein administrativer Aufrufer verwendet denselben Service fuer Pruefung und Wechsel:

```java
var review = promotion.review(versionId, holdout);
// Diff und Evaluation fachlich pruefen und die Freigabe dokumentieren.
var approval = new StoryPromotionService.Approval(approvedBy, documentReference, reason);
var outcome = promotion.promote(review, holdout, approval);
```

Der Holdout enthaelt eine eigene Version, Herkunft, verantwortlichen Labelgeber, eine
Unabhaengigkeitserklaerung und begruendete Artikelpaare (`left`, `right`, `sameStory`, `rationale`).
Bei geaenderten Eingangsregeln enthaelt er zusaetzlich unabhaengige `inputQualityCases` mit
`articleRef`, `expectedDisposition`, optionalem `expectedExclusionReason` und `rationale`.
Die Artikelreferenzen sind vollstaendige URL-Hashes, pro Paar lexikalisch aufsteigend sortiert.
Nur eindeutige Labels verwenden; unsichere Paare vorher ausnehmen. Alle als `INCLUDE` erwarteten
Holdout-Artikel muessen im veroeffentlichten Kandidaten enthalten sein; `EXCLUDE`-Faelle duerfen
keine Story-Mitgliedschaft besitzen. Herkunft und Erklaerung muessen belegen, dass
weder diese Labels noch dieselben Ereignisse zur Schwellenwahl verwendet wurden. Dies ist
fachlich zu pruefen; das Programm kann eine falsche Unabhaengigkeitserklaerung nicht erkennen.

Die feste Gate-Version `story-release-gates-v1` verlangt mindestens 20 positive und 20 negative
Paare, Pairwise Precision >= 0,95 und Recall >= 0,90. Das sind konservative initiale
Freigabegrenzen, keine anhand eines neuen Holdouts optimierten Werte. Aenderungen erfordern
eine neue Gate-Version und einen weiteren unabhaengigen Holdout. URL- und Titel-Hashes aus
ART-032 werden durch `engine/src/main/resources/stories/art032-excluded-hashes.txt` ausgeschlossen;
ein Test gleicht dieses Manifest mit dem gesamten Korpus ab. Die Metriken werden aus den
veroeffentlichten Mitgliedschaften berechnet, nicht als fertige Erfolgswerte entgegengenommen.
Enthaelt der Holdout `inputQualityCases`, verwendet die Auswertung `story-release-gates-v2` und
verlangt zusaetzlich null Abweichungen bei Disposition, Ausschlussgrund und Story-Mitgliedschaft.

`review` prueft den erfolgreichen Publish-Run, aktuelle vollstaendige Inputs, Modellvertrag,
Embedding-Dimension und Vektorintegritaet sowie die Uebereinstimmung mit der rekonstruierten
Partition. Es liefert Storyanzahl, Singleton-Anteil, Mitgliedschaftsanzahl und geaenderte
Zuordnungen sowie Merge-/Split-Anzahl. `promote` wiederholt diese Pruefung und den ID-Abgleich.
Ein geaenderter Ausgangsstand oder Holdout verlangt ein neues Review samt Freigabe.

Promotionen werden serialisiert und sperren betroffene Versionen gegen normale Publisher.
Waehrend der Pruefung sind Input-Schreibzugriffe kurzzeitig gesperrt; bei grossen Datenmengen
kann die Rekonstruktion dauern. Public IDs, Identitaetsanker, urspruenglicher Entstehungszeitpunkt,
Versionsstatus und Audit werden gemeinsam committed. Alte Versionen werden `RETIRED`, die
neue Version `ACTIVE`; ein Unique-Index verhindert mehrere aktive Versionen. Erfolgreiche
Wiederholungen liefern `ALREADY_PROMOTED`, auch wenn die Version inzwischen wieder abgeloest ist.
Fehler rollen alle Aenderungen zurueck.

Das Audit in `story_clustering_version_status_history.reason` enthaelt JSON mit Review,
Gate-Version, Holdout samt Labels, Pruefergebnis und dokumentierter Freigabe. `changed_at`
protokolliert den Wechselzeitpunkt. `resolvePublicId` loest eine Public ID ausschliesslich
innerhalb der aktuellen aktiven Version auf. Oeffentliche Leser muessen Version und Story
in derselben SQL-Abfrage lesen. Interne Fremdschluessel verwenden weiterhin `stories.id`.

Die Promotion erfolgt nicht automatisch. Die erste fachliche Freigabe und Promotion der
24-Stunden-Version wurde mit ART-044 ausgefuehrt und ist in der
[Freigabeentscheidung](analysis/ART-044-release-decision.md) dokumentiert. Eine neue Version
benoetigt erneut einen unabhaengigen Holdout und eine dokumentierte fachliche Freigabe.
