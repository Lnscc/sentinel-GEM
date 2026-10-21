import type { ReactNode } from "react";
import { Paper } from "@mui/material";

export default function StateMessage({ children }: { children: ReactNode }) {
  return <Paper role="status" variant="outlined" sx={{ p: 2.5 }}>{children}</Paper>;
}
