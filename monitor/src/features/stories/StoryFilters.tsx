import { Autocomplete, Button, MenuItem, Stack, TextField } from "@mui/material";

export type StoryFilterValues = { query: string; hours: string; region: string };
export type RegionOption = { code: string; name: string };
type Props = { filters: StoryFilterValues; regions: RegionOption[]; onChange: (filters: StoryFilterValues) => void };

export const DEFAULT_FILTERS: StoryFilterValues = { query: "", hours: "24", region: "" };

export default function StoryFilters({ filters, regions, onChange }: Props) {
  const set = (name: keyof StoryFilterValues, value: string) => onChange({ ...filters, [name]: value });
  const selectedRegion = regions.find(({ code }) => code === filters.region)
    ?? (filters.region ? { code: filters.region, name: filters.region } : null);

  return (
    <Stack component="form" direction="row" spacing={1.25} sx={{ p: 1.25, bgcolor: "background.paper", border: 1, borderColor: "divider", borderRadius: 2, boxShadow: "0 3px 12px rgba(20,45,34,.04)" }} aria-label="Ereignisse filtern" onSubmit={(event) => event.preventDefault()}>
      <TextField label="Suche" type="search" size="small" value={filters.query} onChange={(event) => set("query", event.target.value)} placeholder="Thema, Ort oder Quelle" sx={{ flex: 1, minWidth: 220 }} />
      <Autocomplete
        options={regions}
        value={selectedRegion}
        onChange={(_, region) => set("region", region?.code ?? "")}
        getOptionLabel={({ code, name }) => `${name} (${code})`}
        isOptionEqualToValue={(option, value) => option.code === value.code}
        noOptionsText="Keine Region gefunden"
        sx={{ width: 230 }}
        renderInput={(params) => <TextField {...params} label="Region" placeholder="Alle Regionen" size="small" />}
      />
      <TextField select label="Zeitraum" size="small" value={filters.hours} onChange={(event) => set("hours", event.target.value)} sx={{ width: 180 }}>
        <MenuItem value="24">Letzte 24 Stunden</MenuItem><MenuItem value="12">Letzte 12 Stunden</MenuItem><MenuItem value="6">Letzte 6 Stunden</MenuItem>
      </TextField>
      <Button type="button" onClick={() => onChange(DEFAULT_FILTERS)}>Filter löschen</Button>
    </Stack>
  );
}
