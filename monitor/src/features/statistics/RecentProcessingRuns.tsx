import { Box, Paper, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from "@mui/material";
import { formatTimestamp, type StatisticsReport } from "./statisticsTypes";

export default function RecentProcessingRuns({ runs }: { runs: StatisticsReport["recentRuns"] }) {
  return (
    <Box component="section" aria-label="Letzte Verarbeitungsläufe">
      <Typography variant="h2" sx={{ mb: 1 }}>Letzte Verarbeitungsläufe</Typography>
      <Typography color="text.secondary" sx={{ mb: 2 }}>Im gewählten Zeitraum gestartete Import- und Story-Läufe. Die Statuswerte entsprechen dem aktuellen Datenbankstand.</Typography>
      {runs.length === 0 ? <Typography>Keine Verarbeitungsläufe in diesem Zeitraum vorhanden.</Typography> : (
        <TableContainer component={Paper} variant="outlined">
          <Table size="small" aria-label="Verarbeitungsläufe">
            <TableHead><TableRow><TableCell>Verarbeitung</TableCell><TableCell>Status</TableCell><TableCell>Beginn</TableCell><TableCell>Abschluss</TableCell></TableRow></TableHead>
            <TableBody>{runs.map((run, index) => <TableRow key={`${run.kind}-${run.startedAt}-${index}`}>
              <TableCell>{run.kind === "IMPORT" ? "Import" : "Story"}</TableCell>
              <TableCell>{run.status}</TableCell>
              <TableCell><time dateTime={run.startedAt}>{formatTimestamp(run.startedAt)}</time></TableCell>
              <TableCell>{run.completedAt ? <time dateTime={run.completedAt}>{formatTimestamp(run.completedAt)}</time> : "Nicht abgeschlossen"}</TableCell>
            </TableRow>)}</TableBody>
          </Table>
        </TableContainer>
      )}
    </Box>
  );
}
