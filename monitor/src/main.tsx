import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createTheme, CssBaseline, ThemeProvider } from "@mui/material";
import App from "./App";

const theme = createTheme({
  palette: {
    primary: { main: "#39735a" },
    background: { default: "#f6f4ed", paper: "#fffefa" },
    text: { primary: "#1e2823", secondary: "#647269" },
    divider: "#dddcd4",
  },
  typography: {
    fontFamily: 'Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
    h1: {
      fontFamily: 'Georgia, "Times New Roman", serif',
      fontSize: "clamp(2.6rem, 8vw, 5rem)",
      fontWeight: 500,
      letterSpacing: "-0.05em",
      lineHeight: 0.95,
      margin: "0.3rem 0 0.8rem",
    },
    h2: { fontFamily: 'Georgia, "Times New Roman", serif', fontSize: "1.55rem", fontWeight: 500, lineHeight: 1.25 },
    h3: { fontFamily: 'Georgia, "Times New Roman", serif', fontSize: "1.25rem", fontWeight: 500, lineHeight: 1.3 },
    body1: { fontSize: "1.05rem", lineHeight: 1.6 },
    overline: { color: "#647269", fontSize: "0.78rem", fontWeight: 700, letterSpacing: "0.08em" },
  },
  shape: { borderRadius: 12 },
});

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <App />
    </ThemeProvider>
  </StrictMode>,
);
