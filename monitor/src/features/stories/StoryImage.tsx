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

  return (
    <Box
      className="story-image"
      sx={{
        position: "relative",
        overflow: "hidden",
        width: "100%",
        aspectRatio: "16 / 9",
        background: "linear-gradient(135deg, #d7e4de, #a8c4b7)",
        ...(variant === "detail" ? { mt: 3, borderRadius: 1.5 } : {}),
      }}
    >
      <Box
        component="span"
        className="story-image__placeholder"
        aria-hidden="true"
        sx={{ position: "absolute", inset: 0, display: "grid", placeItems: "center", color: "rgba(255,255,255,.8)", fontSize: 40 }}
      >
        ▧
      </Box>
      {url && (
        <Box
          key={url}
          component="img"
          src={url}
          alt=""
          loading="lazy"
          onError={(event) => { event.currentTarget.hidden = true; }}
          sx={{ position: "absolute", inset: 0, display: "block", width: "100%", height: "100%", objectFit: "cover", "&[hidden]": { display: "none" } }}
        />
      )}
    </Box>
  );
}
