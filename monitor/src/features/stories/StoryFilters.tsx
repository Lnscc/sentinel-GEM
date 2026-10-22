import { useState, type FormEvent } from "react";
import { Button, Stack, TextField } from "@mui/material";

export type StoryFilterValues = {
  from?: string;
  to?: string;
  domain?: string;
};

type Props = {
  onApply: (filters: StoryFilterValues) => void;
};

const toInstant = (value: string) => value ? new Date(value).toISOString() : undefined;

export default function StoryFilters({ onApply }: Props) {
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [domain, setDomain] = useState("");

  const apply = (event: FormEvent) => {
    event.preventDefault();
    onApply({ from: toInstant(from), to: toInstant(to), domain: domain.trim() || undefined });
  };

  const reset = () => {
    setFrom("");
    setTo("");
    setDomain("");
    onApply({});
  };

  return (
    <Stack component="form" onSubmit={apply} direction={{ xs: "column", md: "row" }} spacing={2}
      sx={{ mb: 3 }} aria-label="Story-Filter">
      <TextField label="Von" type="datetime-local" size="small" value={from}
        onChange={(event) => setFrom(event.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
      <TextField label="Bis" type="datetime-local" size="small" value={to}
        onChange={(event) => setTo(event.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
      <TextField label="Artikeldomain" size="small" value={domain}
        onChange={(event) => setDomain(event.target.value)} placeholder="example.org" />
      <Button type="submit" variant="contained">Filter anwenden</Button>
      <Button type="button" variant="text" onClick={reset}>Zurücksetzen</Button>
    </Stack>
  );
}
