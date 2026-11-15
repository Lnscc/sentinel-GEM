# Agent-Engineering-Workflow

Dieser Workflow automatisiert die Umsetzung, Tests und Code-Reviews. Menschen
entscheiden über fachliche oder technische Fragen und geben in Phase 1 den
Merge frei; sie müssen keinen Code reviewen.

## Freigabe und Aufnahme

Ein Ticket ist nur ausführbar, wenn es im Linear-Team `Sentinel GEM` den Status
`Todo` und das Label `agent-ready` hat. `Backlog` ist immer ausgeschlossen.

Vor dem Start prüft der Orchestrator:

- klare Anforderungen und überprüfbare Akzeptanzkriterien,
- einen grünen Stand von Remote-`main`,
- abgeschlossene Abhängigkeiten,
- fehlende Datei- oder Funktionsüberschneidungen mit laufender Arbeit.

Höchstens zwei unabhängige Tickets dürfen parallel laufen. Bei erfolgreicher
Aufnahme entfernt der Orchestrator `agent-ready`, setzt das Ticket auf
`In Progress` und dokumentiert Branch und später den Pull Request. Fehlen
Voraussetzungen, entfernt er das Label und nennt den Grund im Ticket.

## Umsetzung und Nachweis

Jedes Ticket erhält einen eigenen Worktree und einen Branch nach dem Muster
`codex/gem-123-kurzbeschreibung`. Der Agent erstellt fokussierte Tests, einen
Commit und einen Draft-PR. Der PR enthält:

- die Verhaltensänderung und explizite Nicht-Ziele,
- automatisierte Prüfungen und CI-Ergebnisse,
- risikobasierte manuelle Tests mit erwartetem und tatsächlichem Ergebnis,
- API-/Postman-, Migrations- und Betriebsfolgen.

Manuelle Tests decken je nach Änderung den Hauptablauf, einen wichtigen Fehler-
oder Leerzustand und einen angrenzenden kritischen Ablauf ab. UI-Änderungen
benötigen visuelle Evidenz; API-Änderungen mindestens Happy Path und relevante
Fehlerantworten. Nicht erforderliche manuelle Tests müssen begründet werden.

## Unabhängiger Review

Nach grüner CI prüft ein unabhängiger Read-only-Agent Anforderungen, Diff und
Testnachweise. Er erhält nicht die Schlussfolgerungen des Implementierers.

- P0/P1: Der PR bleibt Draft und wird nicht automatisch nachgebessert.
- P2/P3: Höchstens zwei Nachbesserungs- und Review-Schleifen.
- Verbleibende Findings: Der PR bleibt Draft und benötigt eine Entscheidung.
- Keine Findings: Der PR wird für die Integrationsfreigabe bereitgestellt.

Menschen werden nur bei unklaren Produktanforderungen, Architektur- oder
Technologieentscheidungen, Breaking Changes, Datenmigrationen, Sicherheits-
oder erheblichen Betriebsrisiken einbezogen. Die Merge-Freigabe erfolgt über
das GitHub-Label `merge-approved`, nicht über eine menschliche Code-Review.

## Integration und Abschluss

In Phase 1 führt der Orchestrator nach `merge-approved`, grüner CI und
erfolgreichem Agent-Review einen Squash-Merge aus. Eine Merge Queue wird erst
eingeführt, wenn reale Parallelitätskonflikte sie rechtfertigen.

Das Linear-Ticket bleibt bis dahin `In Progress`. Erst wenn der Merge-Commit in
Remote-`main` enthalten und dessen CI grün ist, ergänzt der Orchestrator die
Implementierungs- und Testevidenz im Ticket und setzt es auf `Done`.
