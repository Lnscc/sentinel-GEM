# Statistik

Der Monitor zeigt die Übersicht direkt unter `/statistics` (lokal
`http://localhost:5173/statistics`). Es gibt keinen zusätzlichen Menüeintrag.
Die Seite verwendet `GET /api/statistics`; der Monitor-Proxy leitet dies an
`GET /statistics` der Engine weiter.

## REST-Vertrag

`GET /statistics?from=2026-11-17T00:00:00Z&to=2026-11-18T00:00:00Z`

Alternativ: `GET /statistics?hours=24`, mit ganzzahligem `hours` von 1 bis 744,
ausschließlich ohne `from` oder `to`. Beide Grenzen werden dann aus der
Serverzeit berechnet; der Monitor nutzt diese Variante, damit die Rechneruhr
des Browsers keinen Einfluss auf den Zeitraum hat.

`from` und `to` sind optionale ISO-8601-Zeitpunkte mit Zeitzone. Ohne `to`
gilt die aktuelle Serverzeit, ohne `from` gelten die letzten 24 Stunden vor
`to`. Der Zeitraum ist halboffen `[from, to)`, höchstens 31 Tage lang und darf
nicht in der Zukunft oder vor 1970 liegen. Andere Parameter und ungültige Grenzen ergeben
HTTP 400 mit `{ "code": "invalid_request", "message": "…" }`.

HTTP 200 liefert:

- `generatedAt`: Zeitpunkt der Abfrage; keine zugesicherte Aktualität der
  vorgelagerten GDELT-Quelle.
- `period`: die tatsächlich verwendeten Grenzen `from` und `to`.
- `visibleVersion`: konfigurierte, im Monitor sichtbare Clustering-Version.
- `visibleVersionStatus`: Status dieser Version (`ACTIVE`, `SHADOW`, `RETIRED`)
  oder `null`, falls sie nicht vorhanden ist; in diesem Fall fehlen die
  versionsabhängigen Kennzahlen ausdrücklich statt einen Nullbestand zu melden.
- `metrics`: Kennzahlen mit stabiler `id`, `group`, `label`, `value`, `unit`,
  `scope`, `definition`, `from`, `to` und `unavailableReason`.
- `recentRuns`: jüngste Import- und Story-Läufe im Bezugszeitraum mit
  `kind`, `status`, `startedAt` und optionalem `completedAt`.

`scope` unterscheidet `INVENTORY` (aktueller Bestand unabhängig vom
gewählten Zeitraum), `PERIOD` (gespeicherte Werte mit den angegebenen
Zeitgrenzen) und `RUNTIME` (Zähler dieser Engine-Instanz seit Beginn der
Erfassung). Bedeutung und maßgebliche Zeitspalte stehen in `definition`.
Einheiten werden mitgeliefert. `value: null` bedeutet fehlende Messwerte,
niemals Nullverbrauch; `unavailableReason` erklärt den Grund.

HTTP 503 mit `{ "code": "statistics_unavailable", "message": "…" }`
bedeutet einen Datenbank-/Ladefehler. Die API liefert dann keine scheinbar
leeren Statistiken. HTTP-Antworten werden nicht zwischengespeichert.

## Messgrenzen

Story-Bestand und Story-Verarbeitung beziehen sich auf die sichtbare Version;
historische und Shadow-Versionen werden nicht zusammengezählt. Import- und
Artikelbestand sowie Provider-Laufzeitzähler gelten systemweit. Quellen
bezeichnet unterschiedliche Artikel-Domains, keine redaktionell bereinigten
Medienorganisationen. Story-Größen beziehen sich auf aktuelle Mitgliedschaften.

Importdateien speichern den letzten Versuch; erneute Versuche überschreiben
dessen Status und Zeitstempel. Die Übersicht ist kein vollständiges
Importversuchsarchiv. Verarbeitete Artikel im Zeitraum sind unterschiedliche
Artikel aus Snapshots erfolgreicher Clustering-Läufe mit Abschluss im Zeitraum,
keine Aussage über deren erstmalige Verarbeitung. Laufzeiten werden aus
gespeicherten Start- und Abschlusszeitpunkten beziehungsweise vorhandenen
Provider-Latenzmessungen berechnet.

Tokenverbrauch stammt aus `usage.total_tokens` der Embedding-Antwort und wird
einmal je Provider-Antwort erfasst, auch bei Batch-Aufrufen. Erfassung beginnt
mit diesem Softwarestand und der jeweiligen Engine-Instanz; es gibt keine
historische Rekonstruktion und keine Persistenz über Neustarts. Fehlt für einen
Aufruf eine verwendbare Usage-Angabe (auch bei Fehlern oder laufenden Aufrufen),
ist der Gesamtverbrauch unbekannt. Ein Neustart setzt die Laufzeitzähler zurück.
Kosten bleiben ohne einen hinterlegten, nachvollziehbaren Tarif unbekannt;
es wird keine kostenlose Nutzung suggeriert.

Der Monitor erlaubt Zeitraumwechsel und manuelles Aktualisieren; Ladefehler,
fehlende Werte und leere Laufhistorien werden getrennt dargestellt.
Die Postman-Sammlung `docs/postman/Statistics-API.postman_collection.json`
enthält Vertragsprüfungen für HTTP 200, 400 und einen optionalen 503-Test
gegen eine gezielt nicht verfügbare Testdatenbank.
