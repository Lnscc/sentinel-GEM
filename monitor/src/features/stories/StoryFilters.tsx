import { Button, MenuItem, Stack, TextField } from "@mui/material";

export type StoryFilterValues = { query: string; hours: string; region: string };
type Props = { filters: StoryFilterValues; onChange: (filters: StoryFilterValues) => void };

export const DEFAULT_FILTERS: StoryFilterValues = { query: "", hours: "24", region: "" };

export default function StoryFilters({ filters, onChange }: Props) {
  const set = (name: keyof StoryFilterValues, value: string) => onChange({ ...filters, [name]: value });

  return (
    <Stack component="form" direction="row" spacing={1.25} sx={{ p: 1.25, bgcolor: "background.paper", border: 1, borderColor: "divider", borderRadius: 2, boxShadow: "0 3px 12px rgba(20,45,34,.04)" }} aria-label="Ereignisse filtern" onSubmit={(event) => event.preventDefault()}>
      <TextField label="Suche" type="search" size="small" value={filters.query} onChange={(event) => set("query", event.target.value)} placeholder="Thema, Ort oder Quelle" sx={{ flex: 1, minWidth: 220 }} />
      <TextField label="Region" size="small" value={filters.region} onChange={(event) => set("region", event.target.value)} placeholder="z. B. GM16" sx={{ width: 180 }} />
      <TextField select label="Zeitraum" size="small" value={filters.hours} onChange={(event) => set("hours", event.target.value)} sx={{ width: 180 }}>
        <MenuItem value="24">Letzte 24 Stunden</MenuItem><MenuItem value="12">Letzte 12 Stunden</MenuItem><MenuItem value="6">Letzte 6 Stunden</MenuItem>
      </TextField>
      <Button type="button" onClick={() => onChange(DEFAULT_FILTERS)}>Filter löschen</Button>
    </Stack>
  );
}
