import { Box, Button, Divider, Stack, TextField, Typography } from "@mui/material";
import { useLayoutEffect, useState, type FormEvent } from "react";
import { defaultFilters, periods, type ExploreFilters, type ExploreView } from "./exploreQuery";

export type ExploreCountry = { code: string; name: string };

const searchScope: Record<ExploreView, string> = {
  stories: "Repräsentativer Titel, repräsentative Domain und primäre Ortsangaben.",
  articles: "Titel, URL, Domain und zugeordnete GDELT-Ortsnamen.",
};

export default function ExploreFiltersForm({
  filters, view, countries, countryError, onApply,
}: {
  filters: ExploreFilters;
  view: ExploreView;
  countries: ExploreCountry[];
  countryError?: string;
  onApply: (filters: ExploreFilters) => void;
}) {
  const [draft, setDraft] = useState(filters);
  useLayoutEffect(() => setDraft(filters), [filters]);
  const options = draft.country && !countries.some(({ code }) => code === draft.country)
    ? [{ code: draft.country, name: draft.country }, ...countries]
    : countries;
  const update = (field: keyof ExploreFilters, value: string) => {
    setDraft((previous) => ({ ...previous, [field]: value }));
  };
  const submit = (event: FormEvent) => {
    event.preventDefault();
    const next = { ...draft, q: draft.q.trim(), domain: draft.domain.trim() };
    setDraft(next);
    onApply(next);
  };

  return (
    <Box component="form" aria-labelledby="explore-filters" onSubmit={submit}>
      <Typography id="explore-filters" component="h2" sx={{ mb: 2, fontWeight: 750, fontSize: "0.95rem" }}>Recherchefilter</Typography>
      <Stack spacing={1.5}>
        <TextField
          id="explore-q"
          label="Suchtext"
          type="search"
          size="small"
          value={draft.q}
          onChange={(event) => update("q", event.target.value)}
          helperText={`Wörtliche Suche. ${searchScope[view]}`}
        />
        <TextField
          id="explore-period"
          label="Zeitraum"
          size="small"
          select
          slotProps={{ select: { native: true }, inputLabel: { shrink: true } }}
          sx={{ "& .MuiNativeSelect-select": { fontSize: "0.8125rem" } }}
          value={draft.period}
          onChange={(event) => update("period", event.target.value)}
          helperText={view === "stories" ? "Überschneidung mit dem bekannten Story-Zeitraum." : "Zeitpunkt der ersten Erfassung."}
        >
          {Object.entries(periods).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </TextField>
        <TextField
          id="explore-country"
          label={view === "stories" ? "Land am primären Story-Ort" : "Erwähntes Land"}
          size="small"
          select
          slotProps={{ select: { native: true }, inputLabel: { shrink: true } }}
          value={draft.country}
          onChange={(event) => update("country", event.target.value)}
          helperText={countryError ? `Länderauswahl konnte nicht geladen werden. ${countryError}` : undefined}
        >
          <option value="">Alle Länder</option>
          {options.map(({ code, name }) => <option key={code} value={code}>{name}</option>)}
        </TextField>
        <TextField
          id="explore-domain"
          label="Quellendomain"
          size="small"
          value={draft.domain}
          onChange={(event) => update("domain", event.target.value)}
          helperText={view === "stories" ? "Exakte Domain eines Story-Artikels." : "Exakte Domain des Artikels."}
        />
      </Stack>
      <Stack spacing={0.5} sx={{ mt: 1.75 }}>
        <Button type="submit" variant="contained" disableElevation sx={{ textTransform: "none", fontWeight: 700 }}>Suchen</Button>
        <Button onClick={() => { setDraft(defaultFilters); onApply(defaultFilters); }} size="small" sx={{ textTransform: "none" }}>Filter zurücksetzen</Button>
      </Stack>
      <Divider sx={{ my: 1.5 }} />
      <Typography variant="caption" component="p" color="text.secondary" sx={{ m: 0, overflowWrap: "anywhere" }}>
        <strong>Aktiv</strong> · {[
          periods[filters.period],
          filters.q && `Suchtext „${filters.q}“`,
          filters.domain && `Quelle ${filters.domain}`,
          filters.country && (countries.find(({ code }) => code === filters.country)?.name ?? filters.country),
        ].filter(Boolean).join(" · ")}
      </Typography>
    </Box>
  );
}
