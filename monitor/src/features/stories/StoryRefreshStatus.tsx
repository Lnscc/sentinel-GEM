import { Button, Stack, Typography } from "@mui/material";

type Props = {
  isLoading: boolean;
  lastLoadedAt?: Date;
  onRefresh: () => void;
};

export default function StoryRefreshStatus({ isLoading, lastLoadedAt, onRefresh }: Props) {
  return (
    <Stack component="section" direction="row"
      sx={{ flexWrap: "wrap", gap: 1, alignItems: "center", justifyContent: "space-between", mb: 3 }}
      aria-label="Aktualität der Story-Liste">
      <Typography color="text.secondary" aria-live="polite">
        {lastLoadedAt
          ? <>Zuletzt geladen: <time dateTime={lastLoadedAt.toISOString()}>{lastLoadedAt.toLocaleString()}</time></>
          : "Noch nicht erfolgreich geladen"}
        {isLoading && " · Aktualisierung läuft …"}
      </Typography>
      <Button variant="outlined" onClick={onRefresh} disabled={isLoading}>Aktualisieren</Button>
    </Stack>
  );
}
