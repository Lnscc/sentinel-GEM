# GEM-080: pgvector-HNSW-Shadow-Benchmark

Der Benchmark bestimmt zuerst das dichteste vorhandene 24-h-Fenster und kopiert
nur dessen vorhandene 1.536-dimensionale Float32-Embeddings in eine getrennte,
unlogged Shadow-Tabelle. Erst darauf erzeugt er den HNSW-Index. Damit wird der
fachliche Zeitfilter vor der ANN-Suche angewendet und zugleich ein belastbares
Bestandsfenster statt eines moeglicherweise duennen Randfensters gemessen. Der
Benchmark schreibt weder Story-Zuordnungen noch Clustering-Versionen und erzeugt
keine neuen Embeddings.

## Festgelegtes Ziel und Parameter

- Kandidaten-Recall: mindestens 0,98 gegen exakte Top-50 auf einer
  deterministischen Stichprobe von 100 Artikeln
- fachliches Fenster: vor der ANN-Suche auf das feste 24-h-Snapshot-Fenster begrenzt
- HNSW: `m=16`, `ef_construction=128`, `ef_search=400`
- HNSW holt intern 201 Treffer aus dem bereits zeitlich begrenzten Index,
  entfernt den Suchartikel selbst und uebergibt bis zu 200 Kandidaten an die
  exakte Nachrechnung; erst diese liefert die deterministischen Top-50
- finale Wertung: bestehendes Java-Verfahren `ExactCosine`, absteigend nach
  ungerundetem Cosine und danach aufsteigend nach Artikelreferenz

Die Rohmesswerte stehen in `GEM-080-pgvector-hnsw-results.json`. Eine produktive
Aktivierung ist nur bei erreichtem Recall-Ziel eine Option und nicht Teil dieses
Tickets.

## Ergebnis vom 31. Oktober 2026

Gemessen wurde das dichteste 24-h-Fenster des aktuellen Bestands:

| Messwert | Ergebnis |
|---|---:|
| Artikel im Gesamtbestand | 36.892 |
| Artikel im 24-h-Fenster | 15.389 |
| Kandidaten-Recall | 0,9994 |
| Recall-Ziel | 0,98 (erreicht) |
| Kleinste finale Kandidatenmenge | 50 |
| Shadow-Daten laden | 4,171 s |
| HNSW-Index bauen | 16,172 s |
| Exakte Referenz, 100 Abfragen | 2,470 s |
| HNSW plus exaktes Reranking, 100 Abfragen | 0,704 s |
| HNSW-Index | 84,7 MiB |

Mit fachlicher Vorfilterung und internem Top-200-Pool ist die Kandidatensuche
etwa 3,5-mal schneller als die exakte In-Memory-Referenz und uebertrifft das
Recall-Ziel deutlich. Der einmalige Aufbau des Shadow-Indexes ist nicht Teil der
Suchlaufzeit. Eine produktive Aktivierung bleibt ausserhalb dieses Tickets; der
naechste Schritt waere ein paralleler Shadow-Lauf auf denselben eingefrorenen
Clustering-Eingaben.

Die fruehere Messung eines globalen HNSW-Indexes mit nachgelagertem Zeitfilter
ist nicht mit diesem fachlich vorgesehenen Ablauf vergleichbar.

## Reproduktion

```powershell
$cfg = @{}; Get-Content ..\.env | Where-Object { $_ -match '^[^#=]+=' } | ForEach-Object { $pair = $_ -split '=', 2; $cfg[$pair[0]] = $pair[1] }
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://localhost:5432/' + $cfg['POSTGRES_DB']
$env:SPRING_DATASOURCE_USERNAME = $cfg['POSTGRES_USER']
$env:SPRING_DATASOURCE_PASSWORD = $cfg['POSTGRES_PASSWORD']
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--spring.main.web-application-type=none --gdelt.ingestion.enabled=false --gdelt.staging.enabled=false --gdelt.retention.enabled=false --articles.enabled=false --stories.embeddings.incremental.enabled=false --stories.embeddings.repair.enabled=false --stories.snapshots.incremental.enabled=false --stories.snapshots.backfill.enabled=false --stories.hnsw-benchmark.enabled=true'
```
