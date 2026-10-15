# Sentinel GEM – Projektübersicht

## Zweck

Sentinel GEM ist eine lesende Weboberfläche für veröffentlichte Stories der
[Global News Engine](https://github.com/Lnscc/global-news-engine-v1). Sie soll Menschen helfen,
aktuelle Ereignisse zu überblicken, die zugehörigen Artikel zu prüfen und die Qualität der
Story-Gruppierung manuell zu beurteilen.

## MVP

- aktuelle Stories über `lng` und `lat` auf einer 2D-Karte anzeigen;
- eine Story über ihren Kartenmarker mit ihren Artikeln öffnen;
- Stories zusätzlich in einer einfachen Liste zugänglich machen;
- Originalquellen öffnen;
- Lade-, Leer- und Fehlerzustände verständlich darstellen;
- auf Desktop und Mobilgeräten funktionieren.

Noch nicht zum MVP gehören Anmeldung, Bearbeitung, Administration, Topics, Themes und eigene
Datenverarbeitung.

## Systemgrenze

```text
GDELT -> Global News Engine -> REST API -> Sentinel GEM -> Browser
```

Die Global News Engine besitzt und verarbeitet die Daten. Sie übernimmt Import, Normalisierung,
Embeddings, Clustering, Versionierung und Veröffentlichung. Sentinel GEM liest ausschließlich die
aktuelle `ACTIVE`-Version über:

- `GET /stories?offset={offset}&limit={limit}`
- `GET /stories/{id}`

Sentinel GEM erhält keine eigene Datenbank und bildet keine fachliche Logik der Engine nach.

## Frontend-Architektur

Die Anwendung wird als clientseitige React-Anwendung mit TypeScript und Vite gebaut. MUI stellt
Komponenten, Theme, Layout und responsives Verhalten bereit. Es gibt keine eigenen CSS-Dateien;
anwendungsspezifisches Styling erfolgt über das MUI Theme und `sx`.

Die Anwendung besteht aus vier Bereichen:

| Bereich | Verantwortung |
|---|---|
| Karte | Stories mit MapLibre GL über `lng` und `lat` darstellen und auswählbar machen |
| Story-Liste | Geladene Stories auch ohne Karteninteraktion zugänglich machen |
| Story-Detail | Metadaten, repräsentativen Artikel und Mitglieder in einem MUI Drawer darstellen |
| API-Zugriff | Basis-URL, HTTP-Aufrufe, Antworttypen und Fehlerbehandlung bündeln |

Der Datenfluss bleibt direkt:

```text
Ansicht -> API-Zugriff -> Global News Engine
        <- UI-Zustand  <- JSON-Antwort
```

Ein eigener Backend-for-Frontend-Dienst wird erst nötig, wenn Authentifizierung, serverseitiges
Caching oder mehrere Datenquellen ihn tatsächlich erfordern.

## Kartenansicht

Die MapLibre-GL-Karte ist die Hauptansicht des MVP. Die Global News Engine liefert pro Story
höchstens einen Ort mit `lng` und `lat`; Sentinel GEM übernimmt weder Geocoding noch fachliche
Ortsableitung. Stories ohne Ort werden auf der Karte nicht dargestellt.

Nach einer abgeschlossenen Kartenbewegung lädt die Anwendung die aktuellen Stories für den
sichtbaren Kartenausschnitt neu. Eine geplante Erweiterung von `GET /stories` nimmt dafür mindestens
die Begrenzung des Ausschnitts und das Zoomlevel entgegen. Die GNE filtert und priorisiert
serverseitig; der Browser erhält nur die darzustellenden Stories.

```text
Kartenbewegung abgeschlossen
    -> Ausschnitt + Zoomlevel an GET /stories
    -> GNE filtert nach Ort, Wichtigkeit und Aktualität
    -> Sentinel GEM ersetzt die sichtbaren Marker
```

In der Weltansicht gilt eine hohe Wichtigkeitsschwelle. Beim Hineinzoomen sinkt die Schwelle, damit
zusätzlich regionale und lokale Stories erscheinen. Das genaue Mapping zwischen Zoomlevel und
Wichtigkeit liegt in der GNE und wird nicht im Frontend dupliziert.

Auch die Aktualität hängt von der Wichtigkeit ab: Sehr wichtige Stories bleiben länger sichtbar,
weniger wichtige Stories nur für ein kurzes Zeitfenster. Die GNE wertet diese Zeitfenster gegen
ihre eigene aktuelle Zeit aus. Das Frontend übermittelt kein `now` und berechnet keine
Wichtigkeits- oder Zeitregeln. Mehrere schnelle Kartenbewegungen dürfen nur die letzte Anfrage
wirksam werden lassen.

MapLibre GL darf sein mitgeliefertes Basis-Stylesheet laden. Eigene Oberflächen- und Layoutstyles
bleiben vollständig in MUI.

## Konfiguration und Betrieb

Die Weboberfläche benötigt zunächst nur eine konfigurierbare API-Basis-URL. Geheimnisse dürfen
nicht im Browser liegen. In der lokalen Entwicklung leitet Vite `/api` an die GNE weiter. Im
Produktivbetrieb liegen Weboberfläche und API unter derselben Domain: die Oberfläche unter `/` und
die GNE unter `/api`. Dadurch ist keine CORS-Freigabe nötig.

## Reihenfolge

1. API-Vertrag für Kartenausschnitt, Zoomlevel, Wichtigkeit, Aktualität, `lng` und `lat` festhalten.
2. Vite-Grundgerüst mit React, TypeScript, MUI Theme und API-Zugriff.
3. MapLibre-GL-Karte mit Stories aus `GET /stories`.
4. Story-Detail gegen `GET /stories/{id}`.
5. Zugängliche Story-Liste sowie responsive Lade-, Leer- und Fehlerzustände.

## Offene Entscheidungen

- Welche Importance-Stufen gelten für welche Zoomlevel?
- Wie lang bleibt eine Story je Importance-Stufe sichtbar?
- Welche konkreten Query-Parameter erhält `GET /stories` für den Kartenausschnitt?
