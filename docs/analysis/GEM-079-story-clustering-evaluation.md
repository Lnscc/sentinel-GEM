# GEM-079: Skalierbares Story-Clustering

## Ergebnis

Die vorhandenen Titel-Embeddings reichen fuer eine skalierbare Kandidatensuche,
nicht aber als alleiniger Merge-Beweis. Empfohlen wird eine zweistufige Loesung:

1. pro Artikel die ungefaehr 50 naechsten Embedding-Nachbarn aus einem ANN-Index
   suchen und anschliessend auf ein 24-Stunden-Fenster begrenzen;
2. nur diese Kandidaten exakt mit Cosine bewerten und mit der bestehenden
   Medoid-Radius-Regel clustern.

Der kleinste sinnvolle Umsetzungsschritt ist ein Shadow-Benchmark eines HNSW-
Indexes auf `story_embedding_artifacts`, der Kandidaten-Recall und Laufzeit gegen
eine exakte Stichprobe misst. Die produktive Aktivierung bleibt ausserhalb dieses
Tickets.

## Datengrundlage und Grenze

Gemessen wurde am 30. Oktober 2026 auf dem lokalen PostgreSQL-Bestand:

- 36.892 eindeutige, aktuell verwendete Artikelreferenzen mit fertigem Embedding;
- 68.899 versionierte `READY`-Inputs;
- 25.340 deduplizierte 1.536-dimensionale Vektoren;
- 148,48 MiB reine Vektordaten und 221 MiB fuer die gesamte Artefakttabelle.

Das Ticket nennt rund 43.000 Artikel. Der aktuelle Bestand ist kleiner; Werte fuer
43.000 Artikel sind deshalb als Hochrechnung gekennzeichnet.

Das manuell gepruefte ART-032-Korpus bleibt die Qualitaetsreferenz: 108 reale
Paare, davon 33 positive, 70 negative (27 harte Negativpaare) und 5 mehrdeutige
Grenzfaelle. Seine 46 Titel-Hashes sind im aktuellen Embeddingbestand nicht mehr
vorhanden. Da GEM-079 neue Embedding-Erzeugung ausschliesst, konnten skalierbare
Kandidatensuchen nicht erneut auf genau diesen Vektoren ausgefuehrt werden. Die
Qualitaetswerte unten stammen daher aus der eingefrorenen ART-032-Auswertung;
Bestands- und Skalierungsmessungen stammen aus dem aktuellen Bestand.

## Qualitaet

Auf dem disjunkten ART-032-Evaluationssplit ergaben sich:

| Verfahren | Precision | Recall | F1 | False Merges |
|---|---:|---:|---:|---:|
| Token-Jaccard, 24 h / Top-50 | 0,5135 | 1,0000 | 0,6786 | 18 |
| Titel-Embedding, 24 h / Top-50 | 0,6786 | 1,0000 | 0,8085 | 9 |

Alle neun Embedding-Fehl-Merges betreffen die gepruefte Hormus-Ereigniskette.
Damit ist die Embedding-Suche die bessere Kandidatenquelle, ein einzelner
Similarity-Schwellwert bleibt aber fachlich unzureichend.

## Skalierung

Das 24-Stunden-Fenster allein erzeugt im aktuellen Bestand 238.476.121 gerichtete
Vergleiche; ein Artikel hat dabei maximal 15.388 fruehere Kandidaten. Das Zaehlen
dieser Fenster auf PostgreSQL 17 dauerte warm 4,741 Sekunden, bewertet aber noch
keinen einzigen 1.536-dimensionalen Cosine-Wert.

| Verfahren | Kandidaten bei 36.892 Artikeln | Einordnung |
|---|---:|---|
| Exakt, 24-Stunden-Fenster | 238.476.121 | Referenz, fuer wiederholte Vollberechnung zu teuer |
| Exakt, danach Top-50 | gleich viele Scores | spart keine Suche, nur Clusterarbeit |
| ANN Top-50, danach exakt | hoechstens 1.844.600 gerichtete Treffer | rund 129-mal weniger exakte Bewertungen |

Bei gleichbleibender zeitlicher Dichte ergeben sich fuer 43.000 Artikel rund
324,0 Millionen Fenstervergleiche. Reine Float32-Vektoren benoetigen ohne
Deduplizierung etwa 252 MiB. ANN-Index und Laufzeit fuer 43.000 Artikel muessen im
Shadow-Benchmark real gemessen werden; eine Scheingenauigkeit aus fremder Hardware
wird nicht angegeben.

## Parameter

- **Schwelle:** ART-032s kalibrierter Cosine-Schwellwert 0,224867 maximiert dort
  F1, erzeugt aber neun Fehl-Merges. Der produktive Bestand verwendet 0,700000;
  diese Schwelle ist konservativer, aber auf dem alten Korpus nicht eingefroren
  evaluiert.
- **Zeitfenster:** ART-031 erreichte mit 24 h / Top-50 vollen positiven
  Kandidaten-Recall. 72 h brachte keinen Recall-Gewinn. Im aktuellen Bestand
  steigen die Rohvergleiche von 238,5 Mio. bei 24 h auf 410,4 Mio. bei 72 h.
- **Top-k:** 24 h / Top-10 verfehlte in ART-031 zwei von zwanzig gerichteten
  Positivsuchen; Top-50 traf alle. Top-50 bleibt deshalb der Startwert.
- **Kettenbildung:** Connected Components kann Folgeereignisse transitiv
  zusammenziehen. Die vorhandene Medoid-Radius-Regel begrenzt diesen Effekt,
  weil jedes Mitglied nach jedem Merge den Schwellwert zum neuen Medoid und das
  Zeitfenster einhalten muss.

## Empfehlung und bekannte Grenzen

HNSW ist der bevorzugte Kandidatenindex, weil der Bestand klein genug fuer einen
In-Memory-Index ist und laufend neue Artikel hinzukommen. IVF ist nur sinnvoll,
wenn Build-Zeit oder Indexspeicher spaeter wichtiger werden als Recall. Der Index
darf keine Merge-Entscheidung treffen: Die finalen Top-50-Kandidaten werden exakt
nachgerechnet und deterministisch nach Cosine und Artikelreferenz sortiert.

### Festgehaltener Zielablauf

1. Das fachliche Zeitfenster grenzt die zulaessigen Artikel ein.
2. Ein pgvector-HNSW-Index liefert daraus die wahrscheinlichsten Kandidaten. Falls
   PostgreSQL den Zeitfilter erst nach der ANN-Suche anwendet, wird die interne
   Treffermenge erhoeht oder iterativ weitergesucht, bis 50 gueltige Kandidaten
   vorliegen.
3. Die Anwendung berechnet fuer diese Kandidaten den exakten Cosine-Wert.
4. Das bestehende Medoid-Radius-Verfahren entscheidet ueber den Cluster-Merge.
   Der Medoid wird nach jedem Merge aus den verbleibenden Repraesentanten neu
   bestimmt.
5. Artikel mit identischem `title_input_hash` bleiben vollstaendige
   Story-Mitglieder, zaehlen bei Medoidwahl und Merge-Pruefung aber nur einmal.
   Dadurch verzerren mehrfach syndizierte, identische Titel den Cluster nicht.

Der Medoid ist damit ein wechselnder Repraesentant und keine Story-Identitaet;
die stabile Story-ID bleibt davon unabhaengig.

Vor einer produktiven Umsetzung fehlen noch zwei Nachweise:

1. ANN-Kandidaten-Recall gegen exakte Top-50-Nachbarn auf einer aktuellen,
   repräsentativen Stichprobe;
2. erneute manuelle Bewertung aktueller Titel, weil die ART-032-Vektoren nicht
   mehr im Bestand liegen und der Schwellenwert 0,700000 dort nicht getestet ist.

## Reproduktion

Die Rohwerte stehen in `GEM-079-story-clustering-results.json`. Die Bestands- und
Fensterzahlen lassen sich read-only mit PostgreSQL reproduzieren:

```sql
WITH current_inputs AS (
    SELECT DISTINCT ON (article_ref) article_ref, effective_at
    FROM story_article_inputs
    WHERE embedding_status = 'READY' AND current_marker = 1
    ORDER BY article_ref, clustering_version_id DESC
), ordered AS (
    SELECT effective_at,
           count(*) OVER (
               ORDER BY effective_at
               RANGE BETWEEN INTERVAL '24 hours' PRECEDING AND CURRENT ROW
               EXCLUDE CURRENT ROW
           ) AS candidates
    FROM current_inputs
)
SELECT count(*), sum(candidates), max(candidates) FROM ordered;
```

