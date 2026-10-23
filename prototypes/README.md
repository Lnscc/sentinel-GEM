# Prototypen

Dieser Ordner enthält isolierte, zeitlich begrenzte Experimente. Prototypen
beantworten konkrete Fragen und sind kein vorgezogener Produktionscode.

## Grundsätze

- Jeder Prototyp prüft genau eine klar formulierte Hypothese.
- Prototypen bleiben vollständig von `monitor/src` und `engine/src` getrennt.
- Sie sind ohne produktives Backend und mit statischen Beispieldaten nutzbar.
- Sie werden nicht vom Produktions-Build ausgeliefert.
- Erkenntnisse werden anschließend regulär implementiert; Prototyp-Code wird
  nicht schrittweise in die Anwendung übernommen.
- Git übernimmt die Versionierung. Ordner wie `v1`, `v2` oder `final` sind nicht
  vorgesehen.

## Struktur

Jede eigenständige Hypothese erhält einen sprechend benannten Unterordner:

```text
prototypes/
├── README.md
├── navigation-concept/
│   ├── README.md
│   └── index.html
└── dashboard-layout/
    ├── README.md
    └── index.html
```

Ein Prototyp soll direkt im Browser oder mit genau einem dokumentierten Befehl
startbar sein. Zusätzliche Werkzeuge und Abhängigkeiten werden nur eingesetzt,
wenn sie für die zu prüfende Hypothese notwendig sind.

## Dokumentation

Die README eines Prototyps enthält nur:

- Hypothese oder Fragestellung
- bewussten Umfang und Abgrenzung
- Startanweisung
- Ergebnis oder getroffene Entscheidung
- Link zum zugehörigen Linear-Ticket

Linear bleibt die Quelle für Aufgaben, Status und Entscheidungen. Im Repository
werden keine lokalen Ticketdateien geführt.

## Abschluss

Jeder Prototyp endet mit einer Entscheidung:

- **Angenommen:** Die Erkenntnisse fließen über ein reguläres Ticket in eine
  saubere Implementierung ein.
- **Verworfen:** Der Prototyp wird gelöscht oder nur bei dauerhaftem
  Dokumentationswert behalten.
- **Unklar:** Eine neue, konkretere Hypothese wird formuliert, statt den
  bestehenden Prototyp unbegrenzt weiterzuentwickeln.
