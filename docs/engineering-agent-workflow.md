# Agent-Engineering-Workflow

Dieser Workflow automatisiert Umsetzung, Tests, Code-Reviews und Integration.
Menschen entscheiden über fachliche oder technische Fragen; sie müssen weder
Code reviewen noch einen fertigen Pull Request separat zum Merge freigeben.

## Freigabe und Aufnahme

Ein Ticket ist nur ausführbar, wenn es im Linear-Team `Sentinel GEM` den Status
`Todo` und das Label `agent-ready` hat. `Backlog` ist immer ausgeschlossen.
Das Label autorisiert den gesamten hier beschriebenen Ablauf bis einschließlich
Squash-Merge. Diese Freigabe bleibt nach der Aufnahme bestehen, obwohl der
Orchestrator das Label zur exklusiven Beanspruchung entfernt.

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

Nach grünen Implementierungs-Checks prüft Codex Cloud Code Review als separate
GitHub-App Anforderungen, Diff und Testnachweise des aktuellen Head-Commits. Der
Orchestrator akzeptiert nur ein abgeschlossenes Review von
`chatgpt-codex-connector[bot]`, dessen Commit dem aktuellen PR-Head entspricht
und das keine offenen Findings meldet. Reviewer, Commit, Ergebnis und
Schleifenzahl werden im PR dokumentiert.

- P0/P1: Der PR bleibt Draft und wird nicht automatisch nachgebessert.
- P2/P3: Höchstens zwei Nachbesserungs- und Review-Schleifen.
- Verbleibende Findings: Der PR bleibt Draft und benötigt eine Entscheidung.
- Keine Findings: Der Orchestrator veröffentlicht den head-spezifischen
  Commit-Status `Agent review gate` mit Link auf die Bot-Evidenz und aktiviert
  den GitHub-Squash-Auto-Merge.

Menschen werden nur bei unklaren Produktanforderungen, Architektur- oder
Technologieentscheidungen, Breaking Changes, Datenmigrationen, Sicherheits-
oder erheblichen Betriebsrisiken einbezogen. Das Bot-Review des aktuellen
Head-Commits ist der technische Nachweis der unabhängigen Agentenprüfung. Jeder
neue Commit macht das vorherige Ergebnis ungültig und wird automatisch erneut
geprüft; bis zum neuen Ergebnis darf Auto-Merge nicht aktiviert werden.

Änderungen an der Review-Steuerung selbst dürfen sich nicht automatisch
freigeben. Dazu zählen insbesondere `AGENTS.md`, Agenten-Skills und
-Instruktionen, dieser Workflow und das vorliegende Prozessdokument. Das Gate
blockiert solche Control-Plane-Änderungen als technische Entscheidung; ihre
Integration benötigt eine explizite separate Freigabe.

## Integration und Abschluss

Nach einem abgeschlossenen Codex-Review ohne offene Findings veröffentlicht der
Orchestrator den Required Check `Agent review gate` für den geprüften Head und
aktiviert Auto-Merge. GitHub führt den Squash-Merge aus, sobald alle
Pflichtchecks grün sind. Eine Merge Queue wird erst eingeführt, wenn reale
Parallelitätskonflikte sie rechtfertigen. Menschliche Code-Reviews sind kein
Bestandteil dieses Ablaufs.

Das Linear-Ticket bleibt bis dahin `In Progress`. Erst wenn der Merge-Commit in
Remote-`main` enthalten und dessen CI grün ist, ergänzt der Orchestrator die
Implementierungs- und Testevidenz im Ticket und setzt es auf `Done`.
