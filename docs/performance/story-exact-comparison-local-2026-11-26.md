# Exakter Story-Vergleich vom 26. November 2026

Die Vergleichsphase für denselben dichten synthetischen Bestand ist im isolierten
Linux-/Java-21-Container im Median von 68,37 auf 32,88 Sekunden gesunken:
51,9 Prozent weniger Zeit beziehungsweise Faktor 2,08. Eine native lokale
Kontrollmessung bestätigt die Verbesserung. Die fachliche Kandidatenmenge, die
ausgeführte Kandidatenarithmetik und der Ergebnis-Digest sind unverändert. Dies
ist ein Vergleichsphasen-Nachweis für GEM-127, kein vollständiger Pipeline- oder
VPS-Abnahmenachweis für GEM-124.

## Profil und Änderung

Die Baseline ist `7c577e26f72aaf826ad815328039641a531247d9`, der integrierte Stand
nach GEM-125 und GEM-126. Sie cached Vektoren und Normen bereits. Im nativen
JFR-Profil besitzen 11.678 von 12.259 `jdk.ExecutionSample`-Ereignissen einen
`ExactCosine.cosine`-Frame im Stack, entsprechend 95,3 Prozent der beobachteten
Samples. Sampling und Stack-Zuordnung sind keine exakte CPU-Zeitbuchhaltung.

`ExactCosine.cosines` berechnet vier Kandidaten mit vier unabhängigen
Double-Akkumulatoren in einem Dimensionsdurchlauf. Jeder Akkumulator addiert die
Produkte weiterhin in derselben Reihenfolge wie die skalare Baseline; die Addition
eines einzelnen Vektorpaares wird nicht neu gruppiert. Normen, Division, Begrenzung
und Rundung bleiben gleich. Die Suche verarbeitet die fertigen Scores weiterhin
in Kandidatenreihenfolge; Restgruppen mit weniger als vier Kandidaten verwenden
den skalaren Pfad. Die Änderung eröffnet unabhängige Rechenarbeit und teilt das
Laden des linken Vektorwertes zwischen vier Paaren.

Es gibt keine approximative Vorauswahl, Rangbegrenzung, Centroid-Regel, neue
Vektordatenbank oder ausgelassene Dimension. Alle 106.999.000 gerichteten
Kandidatenpaare bleiben erhalten, einschließlich Changed-Changed-Paaren. Die
Verbesserung stammt daher weder aus deren kleiner möglicher Dublettenersparnis
noch aus einer erneuten Einführung des bereits bestehenden Vektor-/Norm-Caches.

## Eingefrorener Vergleich

`StoryExactComparisonBenchmarkTests` erzeugt mit Seed `127_2026` 107.000 logische
Inputs mit je einem dichten Float32-Vektor mit 1.536 Dimensionen. Ihre Zeitpunkte
liegen innerhalb von knapp zwei Minuten. Die letzten 1.000 Inputs sind geändert;
alle übrigen besitzen eingefrorene Story-Zuordnungen. Jeder geänderte Input sieht
alle 106.999 anderen Inputs im fachlichen Zeitfenster.

Unabhängige zufällige Vektoren liegen überwiegend unter der Schwelle. Geänderte
Inputs erhalten zusätzlich bekannte ähnliche Partner; die ersten 100 Änderungen
teilen sich paarweise 50 bestehende Gruppen. Dadurch entstehen echte
Kohärenzprüfungen und Vertreter-Evidenz ohne einen einzigen künstlichen
107k-Komplettgraphen über der Schwelle. Der Ausgangsbestand enthält 657.408.000
Byte Vektornutzlast. Es werden keine privaten Daten verwendet, übertragen oder
Embedding-Aufrufe ausgeführt.

Jeder Prozess führt drei vollständige Vergleiche mit frischer Suchinstanz über
demselben Zustand aus. Alle drei Wiederholungen müssen denselben Ergebnis-Digest
liefern. Anschließend wird ein unveränderter Lauf geprüft. Der Benchmark misst
`incrementalPartition` einschließlich Kohärenz, Vertreter-Evidenz und
`search.result()`; der Ergebnis-Digest wird erst nach dem gemessenen Abschnitt
gebildet. Fixture-Erzeugung wird separat gemessen. Datenbankladung,
Snapshot-Persistenz und Veröffentlichung werden in diesem Benchmark nicht
ausgeführt und nicht als Nullkosten einer Pipeline ausgegeben.

| Unveränderte Größe | Beide Varianten, jeder geänderte Lauf |
| --- | --- |
| Fachliche Kandidaten / ausgeführte Kandidaten-Skalarprodukte | 106.999.000 |
| Dimensionale Produkte dieser Kandidaten | 164.350.464.000 |
| Betroffene Komponenten / Paarentscheidungen | 950 / 1.000 |
| Input-Hash | `02bb172298c9d7145e776bb04aa92ca1ac2e946a6183592ee65486e297c035c0` |
| Ergebnis-Digest | `4919045d53d979358551f0d7f2d0e47a28e2ac55601dcf53948e29352a3d053d` |

Die Produktzahl beschreibt exakt die Kandidatensuche. Zusätzliche Cosinuswerte
der Kohärenz- und Vertreterprüfung sind in der gemessenen Laufzeit enthalten,
aber nicht in diesem Zähler. Es wird keine Reduktion der Anzahl ausgeführter
Skalarprodukte behauptet. Die No-op-Läufe haben null Kandidaten,
Komponenten und Entscheidungen und denselben Digest
`4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945`.

## Native Windows-Messung

Der Host besitzt einen AMD Ryzen 9 9900X mit 12 Kernen und 24 logischen
Prozessoren. Beide Prozesse liefen nacheinander mit CPU-Affinität auf einem
logischen Prozessor, Java `22.0.2+9-70`, einem aktiven JVM-Prozessor und
konfiguriertem maximalem Heap von 1.536 MiB. Es lief keine zusätzliche lokale
Lastmessung parallel.

| Größe | Skalare Baseline | Vier Kandidaten |
| --- | --- | --- |
| Vergleichszeit, Lauf 1 / 2 / 3 | 66,97 / 66,55 / 66,22 s | 30,96 / 30,44 / 30,41 s |
| Median Vergleichszeit | 66,55 s | 30,44 s |
| Median CPU-Zeit des Vergleichsthreads | 65,13 s | 29,61 s |
| Median CPU-Zeit des gesamten Prozesses | 65,83 s | 29,98 s |
| Höchste beobachtete Heap-Belegung während Vergleich | 759,0 MiB | 753,1 MiB |
| Fixture-Erzeugung | 1,745 s | 1,747 s |
| No-op-Vergleich | 0,249 s | 0,237 s |

Die Heap-Belegung wird alle 5 ms während der Vergleichsphase abgetastet. Zusätzlich
enthalten die Rohberichte die Summe der Memory-Pool-Spitzen; diese Summe ist eine
obere Schranke und keine gleichzeitige Heap-Beobachtung. Prozess-CPU enthält auch
JVM-Hilfsthreads. No-op ist eine einzelne kleine Kontrollmessung und kein eigener
Geschwindigkeitsnachweis.

## Linux / Java 21 und Reproduktion

| Größe | Skalare Baseline | Vier Kandidaten |
| --- | --- | --- |
| Vergleichszeit, Lauf 1 / 2 / 3 | 69,19 / 68,35 / 68,37 s | 33,52 / 32,88 / 32,65 s |
| Median Vergleichszeit | 68,37 s | 32,88 s |
| Median CPU-Zeit des Vergleichsthreads | 67,24 s | 32,05 s |
| Median CPU-Zeit des gesamten Prozesses | 68,22 s | 32,87 s |
| Höchste beobachtete Heap-Belegung während Vergleich | 766,9 MiB | 752,3 MiB |
| Fixture-Erzeugung | 2,282 s | 2,549 s |
| No-op-Vergleich | 0,059 s | 0,053 s |

Alle drei geänderten Läufe jeder Variante ergeben die oben dokumentierten
Kandidaten-, Produkt- und Ergebniswerte. Input-Hash und No-op-Digest stimmen
auch mit den nativen Messungen überein.

Der reproduzierbare Runner ist
[`scripts/gem127-benchmark/run_local.ps1`](../../scripts/gem127-benchmark/run_local.ps1).
Er verwendet für beide Varianten denselben kompilierten Test und dieselben
übrigen Anwendungsklassen. Nur `ExactCosine` und `StoryIncrementalPairSearch`
werden für die Referenz aus dem unveränderlichen Baseline-Commit extrahiert und
mit `--release 21` separat kompiliert. Die Referenzklassen stehen beim
Baseline-Lauf zuerst im Classpath. Die Nachher-Variante verwendet die geänderten
Klassen. Damit sind weder alte Ladepfade noch unterschiedliche Datenbanken Teil
des Variantenvergleichs.

Aus dem Repository-Stamm unter PowerShell mit eingerichtetem JDK und Docker:

```powershell
./scripts/gem127-benchmark/run_local.ps1
```

Der Runner benötigt das vorhandene lokale Image
`sentinel-gem-engine:gem116-check`. Dessen verifizierte Identität ist
`sha256:6a6a80530536c9d8d20b247ebf28840643f194ca7f905c824a2a6f19f6ae70be`,
Linux/amd64 mit Temurin `21.0.13+9-LTS`. Beide Container laufen nacheinander mit
`--network none --cpus 1 --memory 2g --memory-swap 2g`,
`-Xms256m -Xmx1536m -XX:ActiveProcessorCount=1`.
Es steht kein zusätzlicher Containerswap zur Verfügung. Der Runner bricht ab,
wenn Input-Hash, Ergebnis-Digest, Kandidaten-/Produktzahlen, Komponenten oder
Entscheidungen der Varianten voneinander abweichen.

Die vollständigen JSON-Berichte und nativen JFR-Aufzeichnungen bleiben lokal im
ignorierten `engine/target/gem127-benchmark`. Der Runner schreibt
`linux-reference.json` und `linux-batched.json`; die native Kontrollmessung liegt
in `baseline.json` und `batched-four.json`. Bei den beiden gemessenen Linux-Läufen
scheiterte der abschließende JFR-Dump auf den Windows-Bind-Mount mit einem
I/O-Fehler. Das geschah nach Abschluss aller Messungen und nach Schreiben der
vollständigen JSON-Berichte; beide Prozesse endeten erfolgreich und der
Semantikvergleich des Runners bestand. Linux-JFR-Dateien werden daher nicht als
Profilnachweis verwendet. Die oben genannte Profilzuordnung stammt ausschließlich
aus dem nativen JFR. Auch ein kleiner Kontrolllauf mit containerlokalem
Dump-Ziel scheiterte beim JFR-Export. Der Runner lässt deshalb die optionale
Container-JFR-Aufzeichnung inzwischen weg; die hier dokumentierten Messungen
liefen in beiden Varianten mit identischen Profil-Einstellungen. Ein isolierter
Benchmark-Neustart erfordert keine Produktionsverbindung.

SHA-256 der gemessenen JSON-Berichte:

| Lokales Artefakt | SHA-256 |
| --- | --- |
| `baseline.json` | `27d7f7638d69c5002f730f989c9b0da18ce415585e9638e6890a002f2fb04bf0` |
| `batched-four.json` | `9c16c3aa679b76cac3cae46080a6f06c1802452e6ac298c0462d3be7f10a1975` |
| `linux-reference.json` | `358102703099559b0e451f445ece4e96a77cec6bf588ea430cd05db23e2d08bf` |
| `linux-batched.json` | `18603947e85a19503edfd758d415c1487431cc9cf45be94bd9bcf423122b6df7` |

## Fachliche Regression

Fünf neue Tests vergleichen den inkrementellen Pfad mit einer unabhängigen
skalaren Referenz. Sie prüfen bitgenaue Cosinuswerte für Batchgrößen null bis
vier, unterschiedliche Dimensionen einschließlich 1.536/1.537, Subnormalzahlen,
negative Werte und Auslöschung. Die Suchvergleiche decken gerundete Gleichstände,
den nächsten kompatiblen Treffer nach Ablehnung des stärksten Kandidaten,
ungerundete Unsicherheits-Ties, historische inklusive Zeitgrenzen, dichte Gruppen
und deterministische Wiederholung ab. Bestehende PostgreSQL-Tests prüfen weiterhin
Veröffentlichung, Story-IDs, Vertreter, Titel-/Zeitkorrekturen, vollständige Gruppen,
Pending-Ersetzungen, Backfill, Reprocessing, Promotion und Lease/Fencing.

Die vollständige lokale Engine-Verifikation gegen den dedizierten PostgreSQL-17-
Container bestand mit 171 Unit-Testfällen und 82 Integrationstestfällen, ohne
Fehler. Drei Unit- und zwei Integrations-Opt-ins wurden regulär übersprungen;
der neue dichte Benchmark wurde separat ausdrücklich ausgeführt. Für den
Spring-Kontext wurden die PostgreSQL-Verbindungsvariablen explizit gesetzt,
nachdem ein erster Lauf auf die ungeeignete H2-Vorgabe zurückgefallen war.

## Abnahmegrenzen und GEM-124

CPU-Affinität beziehungsweise eine CPU-Quote machen den lokalen Ryzen nicht zu
der Produktions-CPU. Der Linux-Container begrenzt ausschließlich den
Vergleichsprozess; er bildet weder Datenbank und Systemreserve noch den gesamten
4-GiB-/2-vCPU-Rechner nach. Auch synthetische ähnliche Partner bilden die reale
Verteilung von Kohärenzprüfungen und Gruppen nicht vollständig nach. Laufzeiten
dürfen nicht als VPS-Laufzeit oder Fünf-Minuten-SLA hochgerechnet werden.

Die gemeinsame Abnahme von GEM-124 bleibt offen. Es fehlt eine dedizierte
VPS-nahe Gesamtumgebung mit 2 vCPU und rund 4 GiB einschließlich Datenbank,
Engine/API und Betriebssystemreserve sowie geeigneten repräsentativen Daten für
mindestens rund 107.000 logische Inputs und mehrere aufeinanderfolgende Intervalle
mit je rund 1.000 Änderungen. Die vorhandene autorisierte lokale Sicherung
lieferte nur 51.587 Snapshot-Mitglieder. Die 107k-/127k-Nachweise von GEM-125 und
GEM-126 verwenden andere, für ihre Teilabnahmen geeignete synthetische
beziehungsweise zeitlich dünne Fälle. Sie ersetzen den dichten integrierten
Pipeline-Nachweis nicht.

Dieser Vergleich führt deshalb keine neue Produktionslast oder private
Datenübertragung aus. Er verlagert keine Sucharbeit in die Datenbank. Laden,
Veröffentlichung, mehrere reale Zuflussintervalle, zusätzliche Altgeschichte,
Wartungs-/Importlast, API-Latenzen, Archiv-/Indexwachstum und ein ausbleibender
Rückstand müssen für GEM-124 gemeinsam auf dem integrierten Stand nachgewiesen
werden. Bestandene Teiltests schließen GEM-124 nicht ab.
