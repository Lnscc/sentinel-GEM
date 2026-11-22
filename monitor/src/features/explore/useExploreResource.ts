import { useEffect, useState } from "react";

type Resource<T> = { url: string; data?: T; error?: string };

export default function useExploreResource<T>(url: string) {
  const [resource, setResource] = useState<Resource<T>>();
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    fetch(url, { signal: controller.signal })
      .then((response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return response.json() as Promise<T>;
      })
      .then((data) => {
        if (!controller.signal.aborted) setResource({ url, data });
      })
      .catch((reason: unknown) => {
        if (controller.signal.aborted) return;
        const error = reason instanceof Error ? reason.message : "Unbekannter Fehler";
        setResource({ url, error });
      });
    return () => controller.abort();
  }, [url, attempt]);

  return {
    data: resource?.url === url ? resource.data : undefined,
    error: resource?.url === url ? resource.error : undefined,
    retry: () => {
      setResource(undefined);
      setAttempt((previous) => previous + 1);
    },
  };
}
