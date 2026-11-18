import { Autocomplete, Button, MenuItem, Stack, TextField } from "@mui/material";

export type StoryFilterValues = { query: string; hours: string; country: string };
export type CountryOption = { code: string; name: string };
type Props = { filters: StoryFilterValues; countries: CountryOption[]; error?: string; onChange: (filters: StoryFilterValues) => void };

export const DEFAULT_FILTERS: StoryFilterValues = { query: "", hours: "24", country: "" };

export default function StoryFilters({ filters, countries, error, onChange }: Props) {
  const set = (name: keyof StoryFilterValues, value: string) => onChange({ ...filters, [name]: value });
  const selectedCountry = countries.find(({ code }) => code === filters.country)
    ?? (filters.country ? { code: filters.country, name: "Land nicht verfügbar" } : null);

  return (
    <Stack component="form" direction="row" sx={{ flexWrap: "wrap", gap: 1.25, p: 1.25, bgcolor: "background.paper", border: 1, borderColor: "divider", borderRadius: 2, boxShadow: "0 3px 12px rgba(20,45,34,.04)" }} aria-label="Ereignisse filtern" onSubmit={(event) => event.preventDefault()}>
      <TextField label="Suche" type="search" size="small" value={filters.query} onChange={(event) => set("query", event.target.value)} placeholder="Thema, Ort oder Quelle" sx={{ flex: "1 1 180px", minWidth: 0 }} />
      <Autocomplete
        options={countries}
        value={selectedCountry}
        onChange={(_, country) => set("country", country?.code ?? "")}
        getOptionLabel={({ name }) => name}
        clearText="Land entfernen"
        openText="Länder anzeigen"
        closeText="Länder schließen"
        isOptionEqualToValue={(option, value) => option.code === value.code}
        noOptionsText="Kein Land gefunden"
        sx={{ flex: "1 1 200px", minWidth: 0 }}
        renderInput={(params) => <TextField {...params} error={Boolean(error)} helperText={error} label="Land" placeholder="Alle Länder" size="small" />}
      />
      <TextField select label="Zeitraum" size="small" value={filters.hours} onChange={(event) => set("hours", event.target.value)} sx={{ width: 180, flexShrink: 0 }}>
        <MenuItem value="24">Letzte 24 Stunden</MenuItem><MenuItem value="12">Letzte 12 Stunden</MenuItem><MenuItem value="6">Letzte 6 Stunden</MenuItem>
      </TextField>
      <Button type="button" onClick={() => onChange(DEFAULT_FILTERS)}>Filter löschen</Button>
    </Stack>
  );
}
