import { Box, Paper, Typography } from "@mui/material";
import { formatTimestamp, type StatisticMetric } from "./statisticsTypes";

type Props = { title: string; description: string; metrics: StatisticMetric[] };

export default function StatisticSection({ title, description, metrics }: Props) {
  return (
    <Box component="section" aria-label={title} sx={{ mb: 4 }}>
      <Typography variant="h2" sx={{ mb: 0.5 }}>{title}</Typography>
      <Typography color="text.secondary" sx={{ mb: 2 }}>{description}</Typography>
      <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(min(100%, 280px), 1fr))", gap: 2 }}>
        {metrics.map((metric) => (
          <Paper component="article" variant="outlined" key={metric.id} sx={{ p: 2.5, overflowWrap: "anywhere" }}>
            <Typography component="h3" sx={{ fontWeight: 700 }}>{metric.label}</Typography>
            <Typography sx={{ fontSize: "1.8rem", fontWeight: 700, my: 1 }}>
              <span>{metric.value === null ? "Nicht verfügbar" : metric.value.toLocaleString("de-DE", { maximumFractionDigits: 3 })}</span>
              {metric.value !== null && <Typography component="span" color="text.secondary" sx={{ fontSize: "0.9rem", ml: 1 }}>{metric.unit}</Typography>}
            </Typography>
            <Typography variant="body2" color="text.secondary">{metric.definition}</Typography>
            {metric.value === null && <Typography variant="body2" sx={{ mt: 1 }}>{metric.unavailableReason || "Für diese Kennzahl liegt kein Messwert vor."} Einheit: {metric.unit}.</Typography>}
            {metric.from && metric.to && <Typography variant="caption" component="p" sx={{ mt: 1 }}>
              Von <time dateTime={metric.from}>{formatTimestamp(metric.from)}</time> bis <time dateTime={metric.to}>{formatTimestamp(metric.to)}</time>
            </Typography>}
          </Paper>
        ))}
      </Box>
    </Box>
  );
}
