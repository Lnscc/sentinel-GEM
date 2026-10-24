import { useEffect } from "react";
import { writeLagebildUrl, type LagebildUrlState } from "./lagebildUrlState";

export default function useLagebildUrlSync(state: LagebildUrlState) {
  useEffect(() => {
    const search = writeLagebildUrl(state);
    window.history.replaceState(null, "", `${window.location.pathname}${search ? `?${search}` : ""}${window.location.hash}`);
  }, [state.filters, state.mapBounds, state.selectedId]);
}
