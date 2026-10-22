import { Box } from "@mui/material";

function httpUrl(value: string | null | undefined) {
  if (!value) return undefined;

  try {
    const url = new URL(value);
    return url.protocol === "http:" || url.protocol === "https:" ? url.href : undefined;
  } catch {
    return undefined;
  }
}

export default function StoryImage({ src, variant }: { src: string | null | undefined; variant: "card" | "detail" }) {
  const url = httpUrl(src);
  if (!url) return null;

  return (
    <Box
      component="img"
      src={url}
      alt=""
      loading="lazy"
      onError={(event) => { event.currentTarget.hidden = true; }}
      sx={{
        display: "block",
        width: "100%",
        aspectRatio: "16 / 9",
        objectFit: "cover",
        ...(variant === "detail" ? { mt: 3, borderRadius: 1.5 } : {}),
      }}
    />
  );
}
