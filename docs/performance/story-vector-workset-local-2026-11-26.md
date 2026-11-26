# Selektive Vektorladung (GEM-126)

Isolierter lokaler Nachweis vom 26. November 2026 auf Basis des integrierten
GEM-125-Stands `e15be1a`. [GEM-126](https://linear.app/sentinel-gem/issue/GEM-126)
begrenzt die Vektorübertragung und -dekodierung; die vollständige logische
Snapshot-Menge, Identität und exakten Zuordnungsregeln bleiben erhalten.

## Verhalten

Der inkrementelle Lauf ermittelt Änderungen und bisherige Zuordnungen vor der
Vektorladung. Er liest weiterhin alle eingefrorenen, suchfähigen Metadaten.
Vektoren lädt er anschließend ausschließlich für die Vereinigung der historischen,
inklusiven ±Zeitfenster aller geänderten Inputs und für die vollständigen bisherigen
Quell-/möglichen Zielgruppen. Vertreter und Gruppenmitglieder außerhalb der
unmittelbaren Suchfenster sind eingeschlossen. Ein No-op lädt keine Vektoren.
Die Auswahl verwendet keine aktuelle Uhrzeit und kein Top-K.

Backfill, Reprocessing, Promotion und geschützte vollständige Replay-Fälle
verwenden weiterhin den vollständigen Ladepfad. Snapshot-Erstellung, Hashes,
eingefrorene Titel, Embedding-Erzeugung, Veröffentlichung und Lease/Fencing werden
nicht geändert. Pending-/unbrauchbare Ersetzungen behalten das bestehende
inkrementelle Verhalten; es entsteht keine neue Bereinigungsregel.

## Messaufbau

- Dedizierter lokaler Container `gem126-postgres`, PostgreSQL 17/pgvector,
  1 CPU, 2 GiB RAM, ausschließlich `127.0.0.1:55426`. Keine Produktionszugriffe.
- Pro Messfall ein frischer Test-JVM-Prozess (JDK 22, Release 21),
  `-Xms256m -Xmx1536m`, eigenes vollständig migriertes und danach entferntes Schema.
- 107.000 logische Inputs: 105.000 unbeteiligte historische Inputs und 1.000
  historische Paare aus je einem geänderten und einem zugeordneten Artikel.
  Paare liegen jeweils 72 Stunden auseinander; 1.000 exakte Kandidaten entstehen.
  Der zweite Bestand fügt ausschließlich 20.000 weiter entfernte alte Inputs hinzu.
  Im No-op sind alle Inputs bereits zugeordnet und die Änderungsmenge ist leer.
- Synthetische Daten, 1.536 Dimensionen, gültige identische Vektoren aus einem
  unveränderlichen Artefakt; keine zusätzlichen privaten Daten oder Embedding-Aufrufe.
  Auch bei gemeinsamem Artefakt überträgt und dekodiert die Referenz pro Input.
- Die Referenz ist der unveränderte bisherige vollständige inkrementelle Loader.
  Beide Varianten behalten alle Metadaten und verwenden anschließend dieselbe
  exakte inkrementelle Partitionierung mit denselben bisherigen Zuordnungen.
- Gemessen wird die gesamte Loader-Transaktion inklusive Commit. Vektorbytes sind
  die gelesene/dekodierte Nutzlast (Anzahl × 1.536 × 4), ohne PostgreSQL-Protokoll,
  Bytea-Textkodierung und Metadaten. Die Referenz überträgt alle `vector_bytes`;
  der neue Metadaten-SELECT projiziert `NULL::bytea`, der nachfolgende SELECT nur
  die ausgewählten eingefrorenen Input-IDs.
- Heap-Spitze: nach Vorbereitungs-GC alle 5 ms während der Ladephase abgetastete
  gesamte Heap-Belegung, einschließlich verbleibender Zuordnungsbasis. Zusätzlich
  werden zurückgesetzte MemoryPool-Peaks erfasst; deren Summe ist eine obere
  Schranke, keine gleichzeitig beobachtete Heap-Belegung. Einzelmessungen,
  keine statistische Latenz- oder Hardwaregarantie.

## Ergebnisse

| Inputs | Änderungen | Loader | Vektoren | Vektornutzlast, Bytes | Laden, ms | Heap-Spitze, MiB | Summe Pool-Peaks, MiB |
|---:|---:|---|---:|---:|---:|---:|---:|
| 107.000 | 1.000 | Referenz | 107.000 | 657.408.000 | 6.450,9 | 888,9 | 1.012,0 |
| 107.000 | 1.000 | Selektiv | 2.000 | 12.288.000 | 710,0 | 165,5 | 231,5 |
| 127.000 | 1.000 | Referenz | 127.000 | 780.288.000 | 7.816,4 | 1.060,0 | 1.131,6 |
| 127.000 | 1.000 | Selektiv | 2.000 | 12.288.000 | 814,3 | 176,1 | 258,6 |
| 107.000 | 0 | Referenz | 107.000 | 657.408.000 | 6.400,5 | 895,7 | 997,8 |
| 107.000 | 0 | Selektiv | 0 | 0 | 540,7 | 149,1 | 204,1 |
| 127.000 | 0 | Referenz | 127.000 | 780.288.000 | 7.667,3 | 1.059,0 | 1.126,0 |
| 127.000 | 0 | Selektiv | 0 | 0 | 630,6 | 132,7 | 157,7 |

Alle acht Läufe bestanden. Die zusätzliche Historie erhöht die selektive
Vektorarbeit weder bei Änderungen noch bei No-op. Metadatenarbeit bleibt vom
logischen Gesamtbestand abhängig; ein konstanter Gesamtheap wird nicht behauptet.

Das Vergleichsergebnis umfasst unverändert 1.000 Kandidaten und 1.000
Zweierkomponenten mit Score `1.000000`, beziehungsweise null bei No-op.
Der Runner prüft pro Bestand/Fall identische Snapshot-Hashes und -Keys sowie
identische semantische Digests (Vertreter, Mitgliedschaften/Fingerprints,
Artefakt-/Vektoridentität, Scores, Zeitabstände, Entscheidungsregeln, Ränge und
Kandidatenzahlen). Der Ergebnisdigest bleibt auch nach zusätzlicher Historie gleich:

- Änderungen: `ce1336c4479cfbee6967a9e30f54119b4d0362510eac1e3d1dea81da0608a55b`
- No-op: `954ef003beb814a9805b8e35fcd089c05dcda4beb6f1e6a0bc6408db36dffe03`

Die Vergleichsphase dauerte hier 136–168 ms bei Änderungen und 75–127 ms bei
No-op. Diese dünne synthetische Kandidatenverteilung belegt die Ladeoptimierung,
ersetzt aber weder den dichten Vergleichsnachweis von GEM-127 noch die gemeinsame
Fünf-Minuten-Abnahme auf 2 vCPU/4 GiB einschließlich Veröffentlichung von GEM-124.

## Reproduktion und Regression

```powershell
docker run --name gem126-postgres --cpus 1 --memory 2g `
  -e POSTGRES_DB=gne -e POSTGRES_USER=gne -e POSTGRES_PASSWORD=gne `
  -p 127.0.0.1:55426:5432 -d pgvector/pgvector:pg17
./scripts/gem126-benchmark/run_local.ps1
docker stop gem126-postgres
```

Der Runner führt die acht Fälle sequenziell in getrennten JVMs aus und prüft die
Referenz-/Historiengleichheit. Rohberichte und Logs liegen lokal, nicht eingecheckt,
unter `engine/target/gem126-benchmark`. Die Testklasse ist standardmäßig Opt-in und
weist fremde Datenbankadressen ab.

`StorySnapshotServicePostgresIT` vergleicht zusätzlich vollständige und selektive
Metadaten/Hashes, Partitionen und Entscheidungsevidenz an realen Repository-Pfaden:
mehrere historische Änderungsfenster, inklusive Grenzen, Titel-/Zeitkorrekturen,
eingefrorene Titel, Quell-/Zielgruppen und Vertreter außerhalb des Fensters,
Ablehnung des besten Kandidaten durch ein entferntes Gruppenmitglied und Auswahl
der nächsten kompatiblen Story, Pending-/unbrauchbare Ersetzungen, No-op und Retry.
Die vorhandenen Veröffentlichungsprüfungen kontrollieren Story-IDs und Vertreter;
Promotion-/Backfill-/Reprocessing- und Retention-Tests bleiben Teil der Verifikation.

Die vollständige lokale `mvnw verify`-Prüfung mit PostgreSQL war erfolgreich:
165 Unit-Testfälle (zwei Opt-in-Fälle übersprungen) und 82 Integrationstestfälle
(zwei Opt-in-Skalentests übersprungen), ohne Fehler. Der neue Vektorskalentest wurde
zusätzlich in allen acht oben dokumentierten Fällen ausdrücklich ausgeführt.

Keine REST-/Postman-, Schema-, Konfigurations- oder Produktionswartungsänderung.
Ein Rückweg ist das Zurücknehmen des Code-Commits; die gespeicherten fachlichen
Daten und Snapshot-Verträge bleiben kompatibel.
