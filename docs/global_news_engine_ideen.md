# Sentinel-GEM – Produktideen

**Stand:** 21. Oktober 2026

**Status:** Diskussionsgrundlage, keine Roadmap oder Zusage zur Umsetzung

## 1. Produktkern

Sentinel-GEM macht globale Nachrichtenereignisse geografisch sichtbar:

- Was ist passiert?
- Wo ist es passiert?
- Welche Berichte gehören zum selben Ereignis?
- Wie hat sich die Berichterstattung entwickelt?

Artikel und Quellen sollen dabei jederzeit nachvollziehbar bleiben. Analysen,
Trends und KI-generierte Inhalte müssen als solche gekennzeichnet werden.

### Festgelegte Richtung

Sentinel-GEM wird zunächst als geografische Ereignisübersicht entwickelt. Eine
breitere News-Intelligence-Plattform ist eine mögliche spätere Ausbaustufe und
bestimmt nicht den aktuellen Produktumfang.

Die Umsetzung folgt dieser Reihenfolge:

1. verlässliche Artikel- und Story-Daten
2. geografische Zuordnung von Ereignissen
3. Live Map mit Filtern
4. Story-Details mit Artikeln, Quellen und Timeline
5. Suche und Explorer
6. erst danach zusätzliche Intelligence-Funktionen

## 2. Vorhandene Grundlage

Das Projekt besitzt bereits die technische Grundlage für:

- Import von GDELT Events, Mentions und GKG-Daten
- normalisierte Artikel und zusammengeführte GDELT-Signale
- versionierte Story-Snapshots auf Basis von Titel-Embeddings
- REST-Endpunkte für Artikel, Stories und Pipeline-Zustand
- einen React-Monitor für die vorhandenen Story-Daten

Die geografische Produktansicht ist das Ziel, aber noch nicht umgesetzt. Der
aktuelle Stand ist in der [Projektübersicht](../README.md) und der
[Architekturdokumentation](global_event_analysis_platform_architecture.md)
beschrieben.

## 3. Naheliegende Produktfähigkeiten

Diese Funktionen unterstützen den Produktkern unmittelbar.

### 3.1 Live Map

- Ereignisse und zugehörige Berichte auf einer Weltkarte darstellen.
- Nach Zeitraum, Region, Thema und Quelle filtern.
- Zwischen Ereignissen, Erwähnungen und weiteren Signalen unterscheiden.
- Von einem Kartenpunkt zu Story, Artikeln und Quellen navigieren.

### 3.2 News Explorer

- Artikel und Stories durchsuchen.
- Nach Zeitraum, Sprache, Region, Thema und Quelle filtern.
- Zusammengehörige Berichte und Ereignisse sichtbar machen.

### 3.3 Event Timeline

- Die Entwicklung einer Story chronologisch darstellen.
- Neue Berichte, wichtige Änderungen und Quellen zeitlich einordnen.

### 3.4 Country Intelligence

- Nachrichten, Ereignisse und Trends eines Landes bündeln.
- Von der Länderansicht zu Stories, Artikeln und Kartenpositionen führen.

## 4. Mögliche spätere Erweiterungen

Diese Ideen können nützlich werden, sobald Karte, Artikel und Stories stabil
funktionieren. Sie sind derzeit nicht priorisiert.

### Trending Topics und Historical Analytics

- Stark wachsende Aufmerksamkeit erkennen.
- Berichterstattung über längere Zeiträume vergleichen.

### Breaking News Radar

- Ungewöhnliche Anstiege der Berichterstattung erkennen.
- Beobachtungen nach Region, Thema und Aktualität sortieren.

### AI Daily Briefing

- Wichtige Entwicklungen mit Quellenverweisen zusammenfassen.
- Regionen und Themen auswählbar machen.

### Watchlists

- Länder, Themen, Organisationen oder Stories beobachten.

### News Connection Graph

- Beziehungen zwischen Personen, Organisationen, Ländern, Themen und Stories
  darstellen.
- Jede Verbindung durch Artikel oder andere gespeicherte Signale belegen.

## 5. Ideen mit Klärungsbedarf

Diese Funktionen erweitern den Produktumfang deutlich oder benötigen besonders
belastbare Bewertungsmethoden. Vor einer Umsetzung braucht es eine bewusste
Produktentscheidung.

### Crisis Monitor

Ein eigener Krisenmonitor könnte Konflikte, Naturkatastrophen oder Proteste
hervorheben. Zu klären ist, ob Sentinel-GEM Ereignisse nur sichtbar macht oder
auch Risiken bewertet und Warnungen ausspricht.

### KI-Stimmungsanalyse

Tonalität kann eine ergänzende Perspektive sein, ist aber keine objektive
Bewertung eines Landes, Unternehmens oder Ereignisses. Aussagekraft,
Fehlergrenzen und Darstellung müssten vorab definiert werden.

### Media Bias Comparison

Unterschiede in Wortwahl und Schwerpunktsetzung könnten sichtbar gemacht
werden. Pauschale oder unbelegte Bewertungen einzelner Medien sind kein Ziel.

### Source Reliability

Nachvollziehbare Quelleninformationen und Verifizierungsstatus sind sinnvoll.
Ein allgemeiner Zuverlässigkeitsscore ist ohne transparente, belastbare Methode
nicht vorgesehen.

### AI News Chat

Fragen zu den gesammelten Nachrichten könnten mit Quellen beantwortet werden.
Der Nutzen gegenüber Suche, Filtern und Story-Ansichten muss die zusätzliche
Komplexität und die Gefahr unbelegter Antworten rechtfertigen.

## 6. Mögliche Produktoberfläche

Eine erste Oberfläche könnte aus drei Bereichen bestehen:

1. **Map** – geografischer Einstieg in aktuelle Ereignisse
2. **Stories** – Ereignisse, Verlauf und zugehörige Berichte
3. **Explore** – Suche und Filter für Artikel und Stories

Spätere Funktionen sollten erst dann eigene Navigationspunkte erhalten, wenn
sie tatsächlich umgesetzt werden. Ein datenreiches Dark-Mode-Dashboard ist eine
mögliche Gestaltung, aber keine Produktanforderung.

## 7. Offene Entscheidungen

- Was ist die kleinste nützliche Version der Live Map?
- Welche Rolle spielen Topics und Themes oberhalb der bestehenden Stories?
- Welche Funktionen benötigen echte Live-Aktualisierung?
- Soll das Produkt Beobachtungen zeigen oder auch Risiken bewerten und warnen?
- Welche KI-Funktionen bieten einen belegbaren Nutzen zu vertretbaren Kosten?

## 8. Leitplanken

- Gemeldete Ereignisse, berechnete Signale und KI-Einschätzungen unterscheiden.
- Aussagen auf Artikel und Quellen zurückführen.
- Zeitstempel, Aktualität und Unsicherheit sichtbar machen.
- Trends und Risikoindikatoren nicht als Tatsachen darstellen.
- Neue Funktionen nur aufnehmen, wenn sie den Produktkern erkennbar stärken.
