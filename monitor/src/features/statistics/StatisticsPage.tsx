import { useEffect, useState } from "react";
import { Alert, Box, Button, CircularProgress, Stack, Typography } from "@mui/material";
import RecentProcessingRuns from "./RecentProcessingRuns";
import StatisticSection from "./StatisticSection";
import { formatTimestamp, type StatisticsReport } from "./statisticsTypes";

export default function StatisticsPage() {
  const [hours, setHours] = useState(24);
  const [refresh, setRefresh] = useState(0);
  const [report, setReport] = useState<StatisticsReport>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    setReport(undefined);
    setError(undefined);
    setLoading(true);
    const parameters = new URLSearchParams({ hours: String(hours) });
    void (async () => {
      try {
        const response = await fetch(`/api/statistics?${parameters}`, { signal: controller.signal });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const data = await response.json() as StatisticsReport;
        if (active) setReport(data);
      } catch (cause) {
        if (active) setError(`Die Statistik konnte nicht geladen werden. ${cause instanceof Error ? cause.message : "Unbekannter Fehler"}`);
      } finally {
        if (active) setLoading(false);
      }
    })();
    return () => { active = false; controller.abort(); };
  }, [hours, refresh]);

  return (
    <Box component="main" sx={{ flex: 1, overflow: "auto", p: { xs: 2, md: 4 } }}>
      <Box sx={{ maxWidth: 1440, mx: "auto" }}>
        <Typography variant="overline">Sentinel GEM · Betrieb</Typography>
        <Typography component="h1" variant="h2" sx={{ fontSize: "2.3rem", my: 1 }}>Statistik</Typography>
        <Typography color="text.secondary" sx={{ mb: 3 }}>Datenbestand, Verarbeitung und Aufwand mit denselben Definitionen wie in der Statistik-API.</Typography>
        <Stack direction="row" sx={{ alignItems: "center", gap: 2, flexWrap: "wrap", mb: 2 }}>
          <Box component="label" htmlFor="statistics-period" sx={{ display: "flex", alignItems: "center", gap: 1 }}>
            Zeitraum
            <Box component="select" id="statistics-period" value={hours} onChange={(event) => setHours(Number(event.target.value))}
              sx={{ font: "inherit", p: 1, bgcolor: "background.paper", color: "text.primary", border: "1px solid", borderColor: "divider", borderRadius: 1 }}>
              <option value={1}>Letzte Stunde</option>
              <option value={6}>Letzte 6 Stunden</option>
              <option value={24}>Letzte 24 Stunden</option>
              <option value={168}>Letzte 7 Tage</option>
              <option value={720}>Letzte 30 Tage</option>
            </Box>
          </Box>
          <Button variant="outlined" disabled={loading} onClick={() => setRefresh((value) => value + 1)}>Aktualisieren</Button>
        </Stack>
        {loading && <Stack role="status" direction="row" sx={{ alignItems: "center", gap: 1 }}><CircularProgress size={18} />Statistik wird geladen …</Stack>}
        {error && <Alert severity="error" action={<Button color="inherit" onClick={() => setRefresh((value) => value + 1)}>Erneut versuchen</Button>}>{error}</Alert>}
        {report && <>
          <Box sx={{ mb: 3, overflowWrap: "anywhere" }}>
            <Typography color="text.secondary">Stand der Abfrage: <time dateTime={report.generatedAt}>{formatTimestamp(report.generatedAt)}</time></Typography>
            <Typography color="text.secondary">Zeitraum: <time dateTime={report.period.from}>{formatTimestamp(report.period.from)}</time> bis <time dateTime={report.period.to}>{formatTimestamp(report.period.to)}</time> (Beginn einschließlich, Ende ausschließlich).</Typography>
            <Typography color="text.secondary">Sichtbare Clustering-Version: {report.visibleVersion}{report.visibleVersionStatus ? ` (${report.visibleVersionStatus})` : ""}</Typography>
          </Box>
          <StatisticSection title="Bestand" description="Aktueller Datenbankbestand zum oben genannten Abfragestand; unabhängig vom gewählten Zeitraum." metrics={report.metrics.filter((metric) => metric.scope === "INVENTORY")} />
          <StatisticSection title="Zeitraum" description="Gespeicherte Messwerte im gewählten Zeitraum. Die jeweilige Definition benennt das maßgebliche Ereignis." metrics={report.metrics.filter((metric) => metric.scope === "PERIOD")} />
          <StatisticSection title="Laufzeit" description="Messwerte seit Beginn der jeweiligen Erfassung in dieser Engine-Instanz. Sie werden bei einem Neustart zurückgesetzt und sind keine historischen Zeitraumwerte." metrics={report.metrics.filter((metric) => metric.scope === "RUNTIME")} />
          <RecentProcessingRuns runs={report.recentRuns} />
        </>}
      </Box>
    </Box>
  );
}
