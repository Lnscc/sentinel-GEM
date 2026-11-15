import type { ReactNode } from "react";
import { Box } from "@mui/material";

const dateTime = new Intl.DateTimeFormat("de-DE", { dateStyle: "medium", timeStyle: "short" });

export function DateRange({ from, to }: { from: string; to: string }) {
  return (
    <>
      <time dateTime={from}>{dateTime.format(new Date(from))}</time>
      <span aria-hidden="true"> – </span>
      <time dateTime={to}>{dateTime.format(new Date(to))}</time>
    </>
  );
}

export function FormattedDate({ value }: { value: string }) {
  return <time dateTime={value}>{dateTime.format(new Date(value))}</time>;
}

export default function StoryMetadata({
  children,
  columns = "minmax(0, 1fr) auto",
}: {
  children: ReactNode;
  columns?: string;
}) {
  return (
    <Box
      component="dl"
      sx={{
        display: "grid",
        gridTemplateColumns: { xs: "1fr", sm: columns },
        minWidth: 0,
        gap: 2,
        m: 0,
        pt: 2,
        borderTop: 1,
        borderColor: "divider",
        "& > *": { minWidth: 0 },
        "& dt": { mb: 0.5, color: "text.secondary", fontSize: "0.68rem", fontWeight: 700, letterSpacing: "0.08em", textTransform: "uppercase" },
        "& dd": { m: 0, overflowWrap: "anywhere", color: "text.secondary", fontSize: "0.9rem", lineHeight: 1.45 },
      }}
    >
      {children}
    </Box>
  );
}
